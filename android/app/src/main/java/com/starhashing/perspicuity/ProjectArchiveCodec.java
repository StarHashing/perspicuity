package com.starhashing.perspicuity;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 项目归档（.zip）的编解码工具。
 *
 * 与 WorkspacePlugin 的关系：插件只负责弹选择器、拿 URI、调度线程，真正的
 * zip 读写落在本类。这样做的理由有三：
 *   1. 中文文件名必须走 UTF-8，java.util.zip 的 ZipOutputStream 默认就是 UTF-8，
 *      比让 JS 端拼二进制再喂回来可靠得多；
 *   2. 整个归档不需要塞进 JS 内存 —— JS 只给「条目列表」，原生日出日落；
 *   3. 不引任何第三方 zip 库，符合项目「零额外依赖」的洁身自好。
 *
 * 归档结构由前端 projectArchive.ts 定义，两种格式（nested / flat）在这里都
 * 只是「一串路径 + 内容」，本类不关心语义。
 */
final class ProjectArchiveCodec {

    private static final String TAG = "MarkTextAndroid";
    /** 单个条目内容上限：与 MarkdownCodec 一致，8 MB。 */
    private static final long MAX_ENTRY_BYTES = 8L * 1024L * 1024L;
    /** 单次导入的条目数上限，防御 zip 炸弹式的超多小文件。 */
    private static final int MAX_ENTRIES = 20000;
    /** 导入时单次读取归档的整体上限，防止用户误选一个巨大压缩包。 */
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;

    private ProjectArchiveCodec() {}

    // ---------------------------------------------------------------------
    // 导出：把「条目列表」写成 zip，落在用户选中的目录（树 URI）里
    // ---------------------------------------------------------------------

    /**
     * 在 {@code treeUri} 指向的目录下创建 {@code fileName} 并写入所有条目。
     *
     * @return 新 zip 的 document URI 字符串。
     */
    static String writeArchive(
        ContentResolver resolver,
        Uri treeUri,
        String fileName,
        List<ArchiveEntry> entries
    ) throws IOException {
        Uri target = createFileInTree(resolver, treeUri, fileName);
        try (OutputStream raw = resolver.openOutputStream(target, "w")) {
            if (raw == null) {
                throw new IOException("Cannot open the archive for writing");
            }
            try (ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                for (ArchiveEntry entry : entries) {
                    writeEntry(zip, entry);
                }
                zip.finish();
            }
        }
        return target.toString();
    }

    private static void writeEntry(ZipOutputStream zip, ArchiveEntry entry) throws IOException {
        String name = entry.path;
        if (entry.isDirectory && !name.endsWith("/")) {
            name = name + "/";
        }
        ZipEntry zipEntry = new ZipEntry(name);
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
        zip.putNextEntry(zipEntry);
        if (!entry.isDirectory && entry.content != null && !entry.content.isEmpty()) {
            zip.write(entry.content.getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
    }

    /** 用 DocumentsContract 在树目录里建一个文件。同名已存在时先删掉。 */
    private static Uri createFileInTree(ContentResolver resolver, Uri treeUri, String fileName)
        throws IOException {
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
        Uri existing = findChild(resolver, treeUri, parentId, fileName);
        if (existing != null) {
            DocumentsContract.deleteDocument(resolver, existing);
        }
        Uri created = DocumentsContract.createDocument(
            resolver,
            parentDocUri,
            "application/zip",
            fileName
        );
        if (created == null) {
            throw new IOException("The provider refused to create the archive");
        }
        return created;
    }

    /** 在指定目录下按显示名找子项，找不到返回 null。 */
    private static Uri findChild(
        ContentResolver resolver,
        Uri treeUri,
        String parentDocumentId,
        String displayName
    ) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        );
        String[] projection = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (displayName.equals(name)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not enumerate the target folder", ex);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // 导入：读一个 zip，还原成「条目列表」交给前端
    // ---------------------------------------------------------------------

    /**
     * 读取 {@code archiveUri} 指向的 zip，返回全部条目。
     *
     * 这里刻意**不用 {@code ZipInputStream} 顺序读**。
     *
     * 原因：{@code ZipInputStream} 依赖本地文件头（local file header）按顺序解析，
     * 一旦某个条目头部置了 data descriptor 标志（通用位标记 bit 3），或目录条目
     * 后面紧跟另一个头，它就容易在 {@code getNextEntry()} 处定位错位，抛
     * {@code EOFException} 或 {@code ZipException}。实测中「带目录条目 + 多级嵌套」
     * 的 zip（用 python {@code zipfile} / 系统压缩工具打出来的包）最容易踩到——
     * 而本 App 自己导出的包恰好看不出问题，于是表现为「分享进来能导入、在选择器里
     * 选同一个文件却失败」这种极难排查的现象。
     *
     * 稳健做法：整体读进内存，解析 **central directory**（每个 ZIP 的权威目录，
     * 位于文件尾部），按其中的 offset 定位每条数据再解压。central directory 不依赖
     * 顺序、不依赖 data descriptor，对任何标准 zip 都成立。
     *
     * 保护：整包 > {@link #MAX_ARCHIVE_BYTES} 直接拒绝；条目数、单条目大小也各有上限。
     */
    static List<ArchiveEntry> readArchive(ContentResolver resolver, Uri archiveUri)
        throws IOException {
        byte[] data = readAllBytes(resolver, archiveUri);
        Log.i(
            TAG,
            "Archive import: uri=" + archiveUri
                + " scheme=" + archiveUri.getScheme()
                + " authority=" + archiveUri.getAuthority()
                + " bytes=" + data.length
        );

        // 首选：解析 central directory（不依赖顺序，对任何标准 zip 都成立）。
        try {
            List<ArchiveEntry> viaDirectory = readViaCentralDirectory(data);
            Log.i(TAG, "Archive import: central-directory parsed " + viaDirectory.size() + " entries");
            return viaDirectory;
        } catch (IOException directoryFailure) {
            Log.w(
                TAG,
                "Archive import: central-directory parse failed, trying stream fallback",
                directoryFailure
            );
        }

        // 兜底：退回顺序流式读。有些 provider 吐出的包 central directory 位置异常，
        // 但本地文件头仍是顺序可读的——两条路都试，能救一个是一个。
        List<ArchiveEntry> viaStream = readViaStream(data);
        Log.i(TAG, "Archive import: stream fallback parsed " + viaStream.size() + " entries");
        return viaStream;
    }

    /** 走 central directory 的稳健解析（主力路径）。 */
    private static List<ArchiveEntry> readViaCentralDirectory(byte[] data) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        long total = 0;

        List<CentralEntry> directory = parseCentralDirectory(data);
        if (directory.isEmpty()) {
            throw new java.util.zip.ZipException("The archive has no usable directory");
        }
        if (directory.size() > MAX_ENTRIES) {
            throw new IOException("The archive contains too many entries");
        }

        for (CentralEntry item : directory) {
            String name = item.name;
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (isDirectoryName(name)) {
                entries.add(new ArchiveEntry(name, "", true));
                continue;
            }
            byte[] content = extractEntry(data, item);
            total += content.length;
            if (total > MAX_ARCHIVE_BYTES) {
                throw new IOException("The archive is too large to import");
            }
            entries.add(new ArchiveEntry(name, new String(content, StandardCharsets.UTF_8), false));
        }
        return entries;
    }

    /**
     * 兜底路径：用 {@code ZipInputStream} 顺序读。
     *
     * 仅在 central directory 解析失败时使用。因为整包已在内存里，这里用
     * {@code ByteArrayInputStream} 包一层即可，不会再有 IO 边界问题。
     */
    private static List<ArchiveEntry> readViaStream(byte[] data) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        long total = 0;
        try (
            java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(data),
                StandardCharsets.UTF_8
            )
        ) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= MAX_ENTRIES) {
                    throw new IOException("The archive contains too many entries");
                }
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (entry.isDirectory() || isDirectoryName(name)) {
                    entries.add(new ArchiveEntry(name, "", true));
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                long entryTotal = 0;
                int read;
                while ((read = zip.read(chunk)) != -1) {
                    entryTotal += read;
                    if (entryTotal > MAX_ENTRY_BYTES) {
                        throw new IOException("An entry in the archive is too large");
                    }
                    buffer.write(chunk, 0, read);
                }
                byte[] content = buffer.toByteArray();
                total += content.length;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new IOException("The archive is too large to import");
                }
                entries.add(new ArchiveEntry(name, new String(content, StandardCharsets.UTF_8), false));
            }
        }
        if (entries.isEmpty()) {
            throw new java.util.zip.ZipException("The archive has no usable entries");
        }
        return entries;
    }

    /** 把整个归档流读进内存，超限直接拒绝。 */
    private static byte[] readAllBytes(ContentResolver resolver, Uri archiveUri)
        throws IOException {
        if (archiveUri == null) {
            throw new IOException("Cannot open the archive for reading");
        }
        try (InputStream raw = resolver.openInputStream(archiveUri)) {
            if (raw == null) {
                throw new IOException("Cannot open the archive for reading");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = raw.read(chunk)) != -1) {
                total += read;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new IOException("The archive is too large to import");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /** central directory 里一条记录的要点（只保留解压需要的字段）。 */
    private static final class CentralEntry {
        final String name;
        final int method;
        final long compressedSize;
        final long localHeaderOffset;

        CentralEntry(String name, int method, long compressedSize, long localHeaderOffset) {
            this.name = name;
            this.method = method;
            this.compressedSize = compressedSize;
            this.localHeaderOffset = localHeaderOffset;
        }
    }

    /**
     * 解析 central directory。
     *
     * 步骤：从尾部向前找 End Of Central Directory（EOCD，签名 PK\x05\x06），读出
     * central directory 起始偏移与条目数，然后逐条读取 CD 记录（签名 PK\x01\x02）。
     * ZIP64 的 EOCD64 暂不支持——归档有 64MB 上限，正常不会触及。
     */
    private static List<CentralEntry> parseCentralDirectory(byte[] data) throws IOException {
        int eocd = findEndOfCentralDirectory(data);
        if (eocd < 0) {
            throw new java.util.zip.ZipException("Not a ZIP archive (no end record)");
        }
        int totalEntries = readU16(data, eocd + 10);
        long cdOffset = readU32(data, eocd + 16);

        List<CentralEntry> result = new ArrayList<>(Math.max(totalEntries, 8));
        long cursor = cdOffset;
        for (int index = 0; index < totalEntries; index++) {
            if (cursor + 46 > data.length || readU32(data, (int) cursor) != 0x02014b50L) {
                // 目录长度与声明不符：容错停止，而不是整包失败。
                break;
            }
            int method = readU16(data, (int) cursor + 10);
            long compressedSize = readU32(data, (int) cursor + 20);
            int nameLen = readU16(data, (int) cursor + 28);
            int extraLen = readU16(data, (int) cursor + 30);
            int commentLen = readU16(data, (int) cursor + 32);
            long localOffset = readU32(data, (int) cursor + 42);

            long nameStart = cursor + 46;
            if (nameStart + nameLen > data.length) {
                break;
            }
            String name = new String(
                data,
                (int) nameStart,
                nameLen,
                StandardCharsets.UTF_8
            );
            result.add(new CentralEntry(name, method, compressedSize, localOffset));
            cursor = nameStart + nameLen + extraLen + commentLen;
        }
        return result;
    }

    /** 从文件尾向前扫描 EOCD 签名，返回其起始下标；找不到返回 -1。 */
    private static int findEndOfCentralDirectory(byte[] data) {
        int limit = Math.max(0, data.length - (22 + 0xFFFF));
        for (int i = data.length - 22; i >= limit; i--) {
            if (readU32(data, i) == 0x06054b50L) {
                return i;
            }
        }
        return -1;
    }

    /** 按 central directory 记录解压单条数据；只支持 STORED(0) 与 DEFLATED(8)。 */
    private static byte[] extractEntry(byte[] data, CentralEntry item) throws IOException {
        int offset = (int) item.localHeaderOffset;
        if (offset + 30 > data.length || readU32(data, offset) != 0x04034b50L) {
            throw new java.util.zip.ZipException("Corrupt local header for " + item.name);
        }
        int nameLen = readU16(data, offset + 26);
        int extraLen = readU16(data, offset + 28);
        long dataStart = (long) offset + 30 + nameLen + extraLen;
        if (dataStart > data.length) {
            throw new java.util.zip.ZipException("Corrupt local header for " + item.name);
        }

        long compressed = item.compressedSize;
        if (compressed < 0 || dataStart + compressed > data.length) {
            throw new java.util.zip.ZipException("Truncated entry " + item.name);
        }
        if (compressed == 0) {
            // 允许零长度文件。
            return new byte[0];
        }

        int start = (int) dataStart;
        int length = (int) compressed;
        if (item.method == 0) {
            if (length > MAX_ENTRY_BYTES) {
                throw new IOException("An entry in the archive is too large");
            }
            byte[] out = new byte[length];
            System.arraycopy(data, start, out, 0, length);
            return out;
        }
        if (item.method != 8) {
            throw new IOException("Unsupported compression method in archive: " + item.method);
        }

        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try {
            inflater.setInput(data, start, length);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(32, length * 2));
            byte[] out = new byte[8192];
            long entryTotal = 0;
            while (!inflater.finished()) {
                int produced = inflater.inflate(out);
                if (produced == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                entryTotal += produced;
                if (entryTotal > MAX_ENTRY_BYTES) {
                    throw new IOException("An entry in the archive is too large");
                }
                buffer.write(out, 0, produced);
            }
            return buffer.toByteArray();
        } catch (java.util.zip.DataFormatException ex) {
            java.util.zip.ZipException failure = new java.util.zip.ZipException(
                "Corrupt entry " + item.name
            );
            failure.initCause(ex);
            throw failure;
        } finally {
            inflater.end();
        }
    }

    /** 目录判定：ZIP 约定目录条目以 `/` 结尾（central directory 里同样如此标记）。 */
    private static boolean isDirectoryName(String name) {
        return name.endsWith("/") || name.endsWith("\\");
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
            | ((data[offset + 1] & 0xFFL) << 8)
            | ((data[offset + 2] & 0xFFL) << 16)
            | ((data[offset + 3] & 0xFFL) << 24);
    }

    // ---------------------------------------------------------------------
    // 与 JS 的桥接：条目列表 ⇄ JSArray
    // ---------------------------------------------------------------------

    /** 把 JS 传来的 [{path, content, isDirectory}] 解析成内部结构。 */
    static List<ArchiveEntry> fromJsArray(JSArray array) throws JSONException {
        List<ArchiveEntry> entries = new ArrayList<>();
        if (array == null) {
            return entries;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject raw = array.getJSONObject(i);
            String path = raw.optString("path", "");
            if (path.isEmpty()) {
                continue;
            }
            entries.add(
                new ArchiveEntry(
                    path,
                    raw.optString("content", ""),
                    raw.optBoolean("isDirectory", false)
                )
            );
        }
        return entries;
    }

    /** 把内部条目列表转成 JS 可用的 JSArray。 */
    static JSArray toJsArray(List<ArchiveEntry> entries) {
        JSArray array = new JSArray();
        for (ArchiveEntry entry : entries) {
            JSObject item = new JSObject();
            item.put("path", entry.path);
            item.put("content", entry.content);
            item.put("isDirectory", entry.isDirectory);
            array.put(item);
        }
        return array;
    }

    /** 归档里的一个条目。目录用 {@code isDirectory=true} 且内容为空表示。 */
    static final class ArchiveEntry {

        final String path;
        final String content;
        final boolean isDirectory;

        ArchiveEntry(String path, String content, boolean isDirectory) {
            this.path = path;
            this.content = content;
            this.isDirectory = isDirectory;
        }
    }
}

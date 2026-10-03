package com.starhashing.perspicuity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Perspicuity 字体导入插件。
 *
 * 设计原则（重要，关系到开源合规）：
 *   本插件只负责「把用户自己在设备上选中的字体文件复制进 App 私有目录」，
 *   App 与仓库本身不携带任何字体文件。字体文件的来源、版权、授权全部由
 *   用户自己负责，因而项目开源时不存在字体版权纠纷。
 *
 * 支持两种导入方式：
 *   1. pickFontFiles()     -- 单选/多选具体字体文件（ttf/otf/ttc/woff/woff2）
 *   2. pickFontDirectory() -- 选一个文件夹，递归扫描其中所有字体文件并全部导入
 *
 * 字体落盘位置：files/fonts/（App 私有，卸载即清除，不进 assets、不进版本库）。
 */
@CapacitorPlugin(name = "FontImport")
public class FontImportPlugin extends Plugin {
    private static final String TAG = "MarkTextAndroid";
    private static final String FONT_DIRECTORY = "fonts";

    /**
     * 显示名清单。
     *
     * 落盘文件名被 sanitize 成了 [A-Za-z0-9._-]，中文/特殊字符的原始名会丢。
     * 该清单把「落盘名 -> 用户原始文件名」记下来，listImportedFonts() 才有
     * 可读的 displayName 可以展示；没有它，字体列表里全是 a1b2c3.ttf 这种乱码名。
     */
    private static final String FONT_MANIFEST = "font-manifest.json";
    /** 单文件上限 30 MB：中文字体动辄 10~20 MB，放宽到该值足够覆盖绝大多数场景。 */
    private static final long MAX_FONT_BYTES = 30L * 1024L * 1024L;
    /** 一次导入的文件数上限，避免用户误选超大目录导致卡死。 */
    private static final int MAX_FONT_FILES = 500;

    /**
     * 传给系统文件选择器的 MIME 白名单。
     *
     * 注意与 {@link FontFileTypes} 的分工：这里是「希望系统选择器预筛选出什么」，
     * 属于提示性参数（很多 provider 不严格遵守，所以仍然保留单选/多选的后续复核）；
     * 那边是「落盘前真正接受什么」，属于硬门槛。两者不合并，因为选择器还需要
     * application/octet-stream 这类泛 MIME 才能让用户看到那些没正确标注类型的字体。
     */
    private static final String[] PICKER_FONT_MIME_TYPES = {
        "font/ttf",
        "font/otf",
        "font/collection",
        "font/woff",
        "font/woff2",
        "application/x-font-ttf",
        "application/x-font-otf",
        "application/font-woff",
        "application/font-sfnt",
        "application/octet-stream",
    };

    private static final String CALLBACK_PICK_FONT_FILES = "pickFontFilesResult";
    private static final String CALLBACK_PICK_FONT_DIRECTORY = "pickFontDirectoryResult";

    // ---------------------------------------------------------------------
    // 导入：单个 / 多个文件
    // ---------------------------------------------------------------------

    @PluginMethod
    public void pickFontFiles(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, PICKER_FONT_MIME_TYPES);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        try {
            startActivityForResult(call, intent, CALLBACK_PICK_FONT_FILES);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "No Android font picker is available", ex);
            call.reject("No Android font picker is available", "FONT_PICKER_UNAVAILABLE", ex);
        }
    }

    @ActivityCallback
    private void pickFontFilesResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            Log.w(TAG, "Missing plugin call for Android font picker result");
            return;
        }

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            resolveCanceled(call);
            return;
        }

        Intent data = result.getData();
        List<Uri> uris = new ArrayList<>();
        // 多选：先看 clipData；单选：退回 data.getData()。
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int index = 0; index < count; index++) {
                Uri uri = data.getClipData().getItemAt(index).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            resolveCanceled(call);
            return;
        }

        // 复制字体是重 IO（单文件上限 30 MB、一次最多 500 个），
        // 绝不能在主线程上跑，否则用户选一个大目录就是一次 ANR。
        final Uri[] picked = uris.toArray(new Uri[0]);
        runImportOffMainThread(call, () -> importFonts(picked, null));
    }

    // ---------------------------------------------------------------------
    // 导入：整个文件夹（递归扫描）
    // ---------------------------------------------------------------------

    @PluginMethod
    public void pickFontDirectory(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );

        try {
            startActivityForResult(call, intent, CALLBACK_PICK_FONT_DIRECTORY);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "No Android folder picker is available", ex);
            call.reject("No Android folder picker is available", "FOLDER_PICKER_UNAVAILABLE", ex);
        }
    }

    @ActivityCallback
    private void pickFontDirectoryResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            Log.w(TAG, "Missing plugin call for Android folder picker result");
            return;
        }

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            resolveCanceled(call);
            return;
        }

        Uri treeUri = result.getData().getData();
        if (treeUri == null) {
            resolveCanceled(call);
            return;
        }

        try {
            // 持有该树 URI 的读权限，进程重启后仍能读。
            try {
                getContext()
                    .getContentResolver()
                    .takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
            } catch (SecurityException ex) {
                Log.w(TAG, "Could not persist font directory permission", ex);
            }

            // 扫描（SAF 递归查询）+ 复制整个过程都在后台线程做：
            // 一个上百文件的字体目录，主线程跑完必然 ANR。
            final Uri tree = treeUri;
            runImportOffMainThread(call, () -> {
                List<Uri> fontUris = FontDocumentScanner.collectFontUris(getContext(), tree);
                if (fontUris.isEmpty()) {
                    throw new NoFontsFoundException();
                }
                // 目录名只用于界面上的「来源」提示，拿不到也不影响导入。
                String folderName = FontDocumentScanner.treeDisplayName(getContext(), tree);
                return importFonts(fontUris.toArray(new Uri[0]), folderName);
            });
        } catch (SecurityException ex) {
            Log.w(TAG, "Android font folder permission is no longer available", ex);
            call.reject("Android font folder permission is no longer available", "FONT_PERMISSION_LOST", ex);
        }
    }

    // ---------------------------------------------------------------------
    // 实际落盘
    // ---------------------------------------------------------------------

    private JSObject importFonts(Uri[] uris, String sourceFolderName) throws IOException {
        File outputDirectory = getFontDirectoryFile();
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Could not create the font import directory");
        }

        JSArray imported = new JSArray();
        JSArray skipped = new JSArray();
        long totalBytes = 0;

        // 以「落盘文件名 -> 已存在」去重：同名文件视为重复导入，直接跳过，
        // 这样用户第二次选同一个文件夹时不会塞进一堆副本。
        Map<String, File> existingByName = new LinkedHashMap<>();
        // 本次导入涉及的显示名（含复用的旧文件），导入结束后统一并回清单。
        Map<String, String> displayNames = readDisplayNames();
        File[] existingFiles = outputDirectory.listFiles();
        if (existingFiles != null) {
            for (File file : existingFiles) {
                if (file.isFile()) {
                    existingByName.put(file.getName(), file);
                }
            }
        }

        for (Uri uri : uris) {
            String displayName = safeFontDisplayName(getDisplayName(uri));
            if (displayName == null) {
                skipped.put(describeSkipped(uri, "unsupported-name"));
                continue;
            }

            String storedName = buildStoredFontName(displayName);
            File targetFile = existingByName.get(storedName);
            if (targetFile != null && targetFile.isFile() && targetFile.length() > 0) {
                // 已导入过：直接复用，不重复复制。
                JSObject entry = buildFontEntry(targetFile, displayName, targetFile.length());
                entry.put("alreadyImported", true);
                imported.put(entry);
                displayNames.put(storedName, displayName);
                continue;
            }

            if (targetFile == null) {
                targetFile = new File(outputDirectory, storedName);
            }

            try {
                long bytes = copyFont(uri, targetFile);
                existingByName.put(storedName, targetFile);
                totalBytes += bytes;
                imported.put(buildFontEntry(targetFile, displayName, bytes));
                displayNames.put(storedName, displayName);
            } catch (IOException ex) {
                Log.w(TAG, "Skipped font during import: " + safeForLog(displayName), ex);
                skipped.put(describeSkipped(uri, ex.getMessage() == null ? "copy-failed" : ex.getMessage()));
            }
        }

        JSObject result = new JSObject();
        result.put("canceled", false);
        result.put("sourceFolderName", sourceFolderName == null ? "" : sourceFolderName);
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("rejected", new JSArray());
        result.put("totalCount", imported.length());
        result.put("totalBytes", totalBytes);
        writeDisplayNames(displayNames);
        Log.i(
            TAG,
            "Imported " + imported.length() + " font file(s), skipped " + skipped.length()
        );
        return result;
    }

    /**
     * 把导入放后台线程执行。
     *
     * 复制字体（单文件最高 30 MB、一次最多 500 个）以及 SAF 目录递归扫描都是
     * 重 IO，放主线程上跑一个大目录就是 ANR —— 这也是用户反馈「导入多了会卡死」
     * 的直接原因。
     *
     * 线程模型：用一个短生命周期线程跑任务，任务结束后回主线程 resolve/reject。
     * 不引入线程池：导入是低频操作，且同一时刻只可能有一次（前端 busy 互斥）。
     */
    private void runImportOffMainThread(PluginCall call, ImportTask task) {
        new Thread(() -> {
            try {
                JSObject payload = task.run();
                getActivity().runOnUiThread(() -> call.resolve(payload));
            } catch (NoFontsFoundException ex) {
                getActivity().runOnUiThread(() ->
                    call.reject("No font files were found in the selected folder", "NO_FONTS_FOUND")
                );
            } catch (SecurityException ex) {
                Log.w(TAG, "Android font permission is no longer available", ex);
                getActivity().runOnUiThread(() ->
                    call.reject("Android font permission is no longer available", "FONT_PERMISSION_LOST", ex)
                );
            } catch (IOException ex) {
                Log.e(TAG, "Failed to import Android font files", ex);
                getActivity().runOnUiThread(() ->
                    call.reject("Failed to import the selected fonts", "FONT_IMPORT_FAILED", ex)
                );
            } catch (RuntimeException ex) {
                Log.e(TAG, "Unexpected failure while importing Android fonts", ex);
                getActivity().runOnUiThread(() ->
                    call.reject("Could not import the selected fonts", "FONT_IMPORT_FAILED", ex)
                );
            }
        }, "perspicuity-font-import").start();
    }

    /** 后台导入任务的返回值载体；用函数式接口避免为一次导入单开一个类。 */
    private interface ImportTask {
        JSObject run() throws IOException;
    }

    /** 目录里一个字体都没找到：走 NO_FONTS_FOUND 的专用信号。 */
    private static final class NoFontsFoundException extends RuntimeException {
        NoFontsFoundException() {
            super("No font files were found in the selected folder");
        }
    }

    private long copyFont(Uri uri, File outputFile) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            throw new IOException("Content resolver returned no font stream");
        }
        long totalBytes = 0L;
        try (InputStream in = input; OutputStream out = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > MAX_FONT_BYTES) {
                    throw new IOException("Font is larger than the 30 MB import limit");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException ex) {
            // 写入失败时清掉半截文件，避免留下损坏字体占据选择列表。
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Could not remove partial font file: " + safeForLog(outputFile.getName()));
            }
            throw ex;
        }
        if (totalBytes <= 0L) {
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Could not remove empty font file: " + safeForLog(outputFile.getName()));
            }
            throw new IOException("Font file is empty");
        }
        return totalBytes;
    }

    private JSObject buildFontEntry(File file, String displayName, long bytes) {
        JSObject entry = new JSObject();
        String stored = file.getName();
        entry.put("displayName", displayName == null ? stored : displayName);
        entry.put("fileName", stored);
        entry.put("identity", stored);
        // 族名从落盘名去掉扩展名派生：@font-face 用它绑定真实文件，
        // 前端再统一加「Perspicuity*」前缀，不会和系统同名字体撞车。
        entry.put("familyName", FontFileTypes.stripExtensionOf(stored));
        entry.put("fileUri", "file://" + file.getAbsolutePath());
        entry.put("bytes", bytes);
        entry.put("importedAt", file.lastModified());
        return entry;
    }

    private JSObject describeSkipped(Uri uri, String reason) {
        JSObject entry = new JSObject();
        entry.put("sourceUri", uri == null ? "" : uri.toString());
        entry.put("reason", reason == null ? "unknown" : reason);
        return entry;
    }

    // ---------------------------------------------------------------------
    // 辅助：显示名清单（font-manifest.json）
    // ---------------------------------------------------------------------
    /**
     * 读取「落盘名 -> 原始显示名」映射。
     *
     * 清单损坏（手改、写到一半断电）时直接当成空表：宁可显示文件名，
     * 也不能因为一个坏 json 让整个字体列表打不开。
     */
    private Map<String, String> readDisplayNames() {
        Map<String, String> names = new LinkedHashMap<>();
        File manifest = new File(getFontDirectoryFile(), FONT_MANIFEST);
        if (!manifest.isFile()) {
            return names;
        }
        try {
            String raw = readFileText(manifest);
            JSONObject json = new JSONObject(raw);
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, null);
                if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
                    names.put(key, value);
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "Font manifest unreadable; falling back to stored file names", ex);
        }
        return names;
    }

    /** 全量重写显示名清单。 */
    private void writeDisplayNames(Map<String, String> names) {
        File directory = getFontDirectoryFile();
        if (!directory.exists() && !directory.mkdirs()) {
            return;
        }
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (Exception ex) {
                Log.w(TAG, "Could not record font display name", ex);
            }
        }
        File target = new File(directory, FONT_MANIFEST);
        File temporary = new File(directory, FONT_MANIFEST + ".tmp");
        try (OutputStream out = new FileOutputStream(temporary)) {
            out.write(json.toString().getBytes("UTF-8"));
        } catch (IOException ex) {
            Log.w(TAG, "Could not persist the font manifest", ex);
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return;
        }
        // 先写 .tmp 再改名，避免写一半的清单被下次启动当成有效数据读进来。
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Could not replace the font manifest");
        }
        if (!temporary.renameTo(target)) {
            Log.w(TAG, "Could not finalize the font manifest");
        }
    }

    private String readFileText(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, "UTF-8"));
            }
        }
        return builder.toString();
    }

    /** 落盘名 -> 该文件在磁盘上真实存在且是字体。 */
    private Map<String, String> readDisplayNamesForExistingFonts() {
        Map<String, String> stored = readDisplayNames();
        Map<String, String> usable = new LinkedHashMap<>();
        File directory = getFontDirectoryFile();
        File[] files = directory.listFiles();
        if (files == null) {
            return usable;
        }
        for (File file : files) {
            if (!file.isFile() || !FontFileTypes.isFontCandidate(file.getName(), null)) {
                continue;
            }
            String name = stored.get(file.getName());
            usable.put(file.getName(), name == null || name.isEmpty() ? file.getName() : name);
        }
        return usable;
    }

    private void resolveCanceled(PluginCall call) {
        JSObject canceled = new JSObject();
        canceled.put("canceled", true);
        call.resolve(canceled);
    }

    // ---------------------------------------------------------------------
    // 辅助：目录 / 命名 / 判定
    // ---------------------------------------------------------------------

    /** 字体目录：files/fonts/。 */
    private File getFontDirectoryFile() {
        return new File(getContext().getFilesDir(), FONT_DIRECTORY);
    }

    /** 返回用户「已导入」的字体清单（供设置页在启动时恢复列表）。 */
    @PluginMethod
    public void listImportedFonts(PluginCall call) {
        File directory = getFontDirectoryFile();
        JSArray fonts = new JSArray();
        JSObject result = new JSObject();
        Map<String, String> displayNames = readDisplayNamesForExistingFonts();
        List<String> storedNames = new ArrayList<>(displayNames.keySet());
        java.util.Collections.sort(storedNames, String.CASE_INSENSITIVE_ORDER);
        for (String storedName : storedNames) {
            File file = new File(directory, storedName);
            if (file.isFile()) {
                fonts.put(buildFontEntry(file, displayNames.get(storedName), file.length()));
            }
        }
        result.put("fonts", fonts);
        result.put("fileUri", "file://" + directory.getAbsolutePath());
        call.resolve(result);
    }

    /** 删除一个已导入字体（按落盘文件名）。 */
    @PluginMethod
    public void deleteImportedFont(PluginCall call) {
        String fileName = call.getString("fileName", "");
        if (fileName == null || fileName.trim().isEmpty() || fileName.contains("/") || fileName.contains("..")) {
            call.reject("A valid font file name is required", "INVALID_FONT_NAME");
            return;
        }

        File fontFile = new File(getFontDirectoryFile(), fileName.trim());
        boolean removed = !fontFile.exists() || fontFile.delete();
        JSObject result = new JSObject();
        result.put("removed", removed);
        result.put("fileName", fileName.trim());
        if (!removed) {
            call.reject("Could not remove the font file", "FONT_DELETE_FAILED");
            return;
        }
        // 同步把该文件的显示名从清单里去掉，否则「删了又导入同名文件」会读到旧名。
        Map<String, String> displayNames = readDisplayNames();
        if (displayNames.remove(fileName.trim()) != null) {
            writeDisplayNames(displayNames);
        }
        Map<String, String> remaining = readDisplayNamesForExistingFonts();
        result.put("totalCount", remaining.size());
        long totalBytes = 0L;
        for (String name : remaining.keySet()) {
            totalBytes += new File(getFontDirectoryFile(), name).length();
        }
        result.put("totalBytes", totalBytes);
        call.resolve(result);
    }

    /**
     * 重命名一个已导入字体的「显示名」。
     *
     * 关键设计：只改清单里的显示名，不动物理文件。
     *
     * 为什么不改文件名：{@code familyName} 由落盘名去掉扩展名派生，
     * {@code fileUri} 也指向落盘名，而 @font-face 的 src 就绑在这两者上。
     * 一旦改物理文件名，那些已经写进排版设置里的字体族名会全部失效
     * （用户选好的字体突然变成 fallback），并且还要重写 @font-face。
     * 显示名是纯展示信息，改它风险为零。
     */
    @PluginMethod
    public void renameImportedFont(PluginCall call) {
        String fileName = call.getString("fileName", "");
        String newDisplayName = call.getString("displayName", "");

        if (fileName == null || fileName.trim().isEmpty()
            || fileName.contains("/") || fileName.contains("..")) {
            call.reject("A valid font file name is required", "INVALID_FONT_NAME");
            return;
        }
        if (newDisplayName == null || newDisplayName.trim().isEmpty()) {
            call.reject("A display name is required", "INVALID_FONT_NAME");
            return;
        }
        String trimmedFileName = fileName.trim();
        // 显示名允许任意字符，但要压掉换行（否则列表里会撑出多行）并限长。
        String trimmedName = newDisplayName.replace('\n', ' ').replace('\r', ' ').trim();
        if (trimmedName.length() > 120) {
            trimmedName = trimmedName.substring(0, 120).trim();
        }
        if (trimmedName.isEmpty()) {
            call.reject("A display name is required", "INVALID_FONT_NAME");
            return;
        }

        File fontFile = new File(getFontDirectoryFile(), trimmedFileName);
        if (!fontFile.isFile()) {
            call.reject("This font is no longer imported", "FONT_NOT_FOUND");
            return;
        }

        Map<String, String> displayNames = readDisplayNames();
        displayNames.put(trimmedFileName, trimmedName);
        writeDisplayNames(displayNames);

        JSObject result = new JSObject();
        result.put("fileName", trimmedFileName);
        result.put("displayName", trimmedName);
        call.resolve(result);
    }

    /**
     * 规范化落盘文件名。
     *
     * 关键约束：@font-face 的 src 会带这个文件名，Chrome 对 URL 里的特殊字符
     * 容忍度有限，因此这里只保留 [A-Za-z0-9._-]，其余折叠成 '_'，中文名同样
     * 会被折叠，但原始显示名会通过 displayName 字段单独返回，界面不受影响。
     */
    private String buildStoredFontName(String displayName) {
        String base = stripExtension(displayName);
        String extension = FontFileTypes.extensionOf(displayName);
        String sanitized = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.length() > 60) {
            sanitized = sanitized.substring(0, 60);
        }
        if (sanitized.isEmpty()) {
            sanitized = "font";
        }
        // 加短随机前缀，避免不同来源的同名字体互相覆盖。
        String token = UUID.randomUUID().toString().substring(0, 8);
        return token + "-" + sanitized + extension;
    }

    /**
     * 从 SAF URI 读显示名。
     *
     * 名字是给用户看的，拿不到不算失败，所以只在真的抛异常时才吞掉并返回 null；
     * 调用方（importFonts）会在拿到 null 时回退到 URI 末段。
     */
    private String getDisplayName(Uri uri) {
        if (uri == null) {
            return null;
        }
        try (
            android.database.Cursor cursor = getContext()
                .getContentResolver()
                .query(uri, new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)
        ) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && name.trim().length() > 0) {
                        return name.trim();
                    }
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not read display name for imported font", ex);
        }
        // 兜底：从 URI 末段推断（SAF 提供者不保证有 DISPLAY_NAME 列）。
        String path = uri.getLastPathSegment();
        if (path != null && path.contains("/")) {
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        return path;
    }

    private String safeFontDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        String trimmed = displayName.trim();
        return FontFileTypes.isFontExtension(FontFileTypes.extensionOf(trimmed)) ? trimmed : null;
    }

    private String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String safeForLog(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}

package com.starhashing.perspicuity;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 在 SAF 目录树中递归收集 Markdown / 文本文件。
 *
 * 与 {@link FontDocumentScanner} 同一思路：刻意不引入 androidx.documentfile，
 * 只用 DocumentsContract 查询子项，少一条运行时依赖就少一个离线构建失败的理由。
 *
 * 纯静态、无状态，便于单元测试。
 */
final class WorkspaceDocumentScanner {
    private static final String[] CHILD_PROJECTION = new String[] {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    /**
     * 扫描时整体跳过的目录名（小写比较）。
     *
     * `.versions` 是「版本快照」目录：里面每个 `vN.md` 都是历史副本，
     * 既不是用户要编辑的正文，也不该出现在项目树里。在扫描最底层直接
     * 跳过它及其整棵子树——前端镜像树因此「不读、不显示」，同时也省掉
     * 一棵可能很大的子树的遍历开销。
     */
    private static final String[] IGNORED_DIRECTORY_NAMES = new String[] {
        ".versions",
    };

    private WorkspaceDocumentScanner() {}

    /** 该目录名是否属于「扫描时整体跳过」的忽略目录。 */
    static boolean isIgnoredDirectoryName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        for (String ignored : IGNORED_DIRECTORY_NAMES) {
            if (lower.equals(ignored)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从树 URI 出发递归收集文档。
     *
     * @param context    上下文
     * @param treeUri    用户绑定的目录
     * @param extensions 视为文档的扩展名（小写，含点，如 ".md"）
     * @param limit      收集上限
     * @param maxDepth   递归深度上限
     */
    static JSArray collectDocuments(
        Context context,
        Uri treeUri,
        String[] extensions,
        int limit,
        int maxDepth
    ) {
        JSArray out = new JSArray();
        if (context == null || treeUri == null) {
            return out;
        }
        String rootId = treeDocumentId(treeUri);
        if (rootId == null) {
            return out;
        }
        List<JSObject> sink = new ArrayList<>();
        collect(
            context.getContentResolver(),
            treeUri,
            rootId,
            "",
            extensions,
            limit,
            maxDepth,
            0,
            sink
        );
        for (JSObject entry : sink) {
            out.put(entry);
        }
        return out;
    }

    static String treeDocumentId(Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(treeUri);
            if (id != null && id.length() > 0) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // 非标准树 URI，走下面的末段解析兜底。
        }
        String last = treeUri.getLastPathSegment();
        return (last != null && last.length() > 0) ? last : null;
    }

    /** 目录显示名：优先 provider 给的，拿不到就退回 URI 末段。 */
    static String treeDisplayName(Context context, Uri treeUri) {
        if (context == null || treeUri == null) {
            return "";
        }
        String rootId = treeDocumentId(treeUri);
        if (rootId == null) {
            return "";
        }
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        try (Cursor cursor = context.getContentResolver().query(
            documentUri,
            new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
            // 名称只用于展示，拿不到不致命。
        }
        return "";
    }

    private static void collect(
        ContentResolver resolver,
        Uri treeUri,
        String parentDocumentId,
        String relativeParent,
        String[] extensions,
        int limit,
        int maxDepth,
        int depth,
        List<JSObject> sink
    ) {
        if (sink.size() >= limit || depth > maxDepth) {
            return;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        );
        List<String> childDocumentIds = new ArrayList<>();
        List<String> childNames = new ArrayList<>();
        try (Cursor cursor = resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext() && sink.size() + childDocumentIds.size() < limit) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (documentId == null || name == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    // 忽略目录（如 .versions 版本快照）：既不输出、也不递归，
                    // 整棵子树对项目树不可见。
                    if (isIgnoredDirectoryName(name)) {
                        continue;
                    }
                    // 目录：既排进递归队列，也作为一条「目录条目」输出。
                    // 输出目录条目的目的是让前端镜像树能记住每个目录的
                    // document URI —— 双向可写时要在这个 URI 下 createDocument，
                    // 空目录也要能显示、能往里放东西，所以不能只当中间节点跳过。
                    String dirRelative = relativeParent.isEmpty()
                        ? name
                        : relativeParent + "/" + name;
                    sink.add(buildDirectoryEntry(treeUri, documentId, name, dirRelative));
                    childDocumentIds.add(documentId);
                    childNames.add(name);
                    continue;
                }
                if (!isDocumentName(name, extensions)) {
                    continue;
                }
                long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                long modified = cursor.isNull(4) ? -1L : cursor.getLong(4);
                String relativePath = relativeParent.isEmpty()
                    ? name
                    : relativeParent + "/" + name;
                sink.add(buildEntry(treeUri, documentId, name, relativePath, size, modified));
                if (sink.size() >= limit) {
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // provider 可能在扫描中途失联；已收集到的结果仍然有效。
            return;
        }

        String relativeBase = relativeParent;
        for (int index = 0; index < childDocumentIds.size(); index++) {
            String childName = childNames.get(index);
            String childRelative = relativeBase.isEmpty()
                ? childName
                : relativeBase + "/" + childName;
            collect(
                resolver,
                treeUri,
                childDocumentIds.get(index),
                childRelative,
                extensions,
                limit,
                maxDepth,
                depth + 1,
                sink
            );
            if (sink.size() >= limit) {
                return;
            }
        }
    }

    private static JSObject buildEntry(
        Uri treeUri,
        String documentId,
        String name,
        String relativePath,
        long size,
        long modified
    ) {
        JSObject entry = new JSObject();
        entry.put("uri", DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString());
        entry.put("name", name);
        entry.put("relativePath", relativePath);
        entry.put("size", size);
        entry.put("modified", modified);
        entry.put("extension", documentExtension(name));
        entry.put("isDirectory", false);
        return entry;
    }

    /** 目录条目：带自己的 document URI，供前端镜像树记录目录地址。 */
    private static JSObject buildDirectoryEntry(
        Uri treeUri,
        String documentId,
        String name,
        String relativePath
    ) {
        JSObject entry = new JSObject();
        entry.put("uri", DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString());
        entry.put("name", name);
        entry.put("relativePath", relativePath);
        entry.put("size", -1L);
        entry.put("modified", -1L);
        entry.put("extension", "");
        entry.put("isDirectory", true);
        return entry;
    }

    static boolean isDocumentName(String name, String[] extensions) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    static String documentExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }
}

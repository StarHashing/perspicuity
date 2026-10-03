package com.starhashing.perspicuity;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 SAF 目录树中递归收集字体文件。
 *
 * 刻意不使用 androidx.documentfile 的 DocumentFile：那会给 app 引入一条新的
 * 运行时依赖（androidx.documentfile:documentfile），而这里需要的只是
 * 「列出子项 + 判断是不是目录」两件事，直接用 DocumentsContract 查询即可。
 * 少一条依赖，就少一个离线/镜像环境下构建失败的理由。
 *
 * 纯静态、无状态，便于单元测试。
 */
final class FontDocumentScanner {

    /** 递归深度上限，防止异常目录结构（或恶意构造的 provider）导致栈溢出。 */
    static final int MAX_SCAN_DEPTH = 8;

    /** 单次扫描上限，防止用户选中整个存储根目录时把内存吃满。 */
    static final int MAX_SCAN_FILES = 500;

    private static final String[] CHILD_PROJECTION = new String[] {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    };

    private FontDocumentScanner() {}

    /**
     * 从树 URI 出发递归收集字体候选文件。
     *
     * @param treeUri 用户通过 ACTION_OPEN_DOCUMENT_TREE 选中的目录
     * @return 收集到的字体文件 URI（最多 {@link #MAX_SCAN_FILES} 个）
     */
    static List<Uri> collectFontUris(Context context, Uri treeUri) {
        List<Uri> out = new ArrayList<>();
        if (context == null || treeUri == null) {
            return out;
        }
        String rootId = treeDocumentId(treeUri);
        if (rootId == null) {
            return out;
        }
        ContentResolver resolver = context.getContentResolver();
        collect(context, resolver, treeUri, rootId, out, 0);
        return out;
    }

    /**
     * 从树 URI 反查根 documentId。
     *
     * 树 URI 形如 content://provider/tree/<documentId>，文档 URI 才是
     * content://provider/document/<documentId>。优先用 framework 的
     * getTreeDocumentId（API 24+），失败时回退到解析末段。
     */
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

    /**
     * 取用户选中目录的显示名，用作来源信息；拿不到就返回 null。
     * 只做一次查询，不递归，失败是常态（有些 provider 不返回 DISPLAY_NAME）。
     */
    static String treeDisplayName(Context context, Uri treeUri) {
        String documentId = treeDocumentId(treeUri);
        if (context == null || treeUri == null || documentId == null) {
            return null;
        }
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (Cursor cursor = context
            .getContentResolver()
            .query(documentUri, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && name.trim().length() > 0) {
                    return name.trim();
                }
            }
        } catch (RuntimeException ex) {
            // 忽略：目录名只是锦上添花的信息。
        }
        return null;
    }

    private static void collect(
        Context context,
        ContentResolver resolver,
        Uri treeUri,
        String directoryId,
        List<Uri> out,
        int depth
    ) {
        if (depth > MAX_SCAN_DEPTH || out.size() >= MAX_SCAN_FILES) {
            return;
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId);
        List<String> childDirectories = new ArrayList<>();

        try (
            Cursor cursor = resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        ) {
            if (cursor == null) {
                return;
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            if (idIndex < 0) {
                return;
            }

            while (cursor.moveToNext()) {
                if (out.size() >= MAX_SCAN_FILES) {
                    return;
                }
                String childId = cursor.getString(idIndex);
                if (childId == null || childId.length() == 0) {
                    continue;
                }
                String name = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                String mime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : null;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    childDirectories.add(childId);
                    continue;
                }
                if (FontFileTypes.isFontCandidate(name, mime)) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId));
                }
            }
        } catch (RuntimeException ex) {
            // provider 拒绝查询某个子目录是正常的（权限、同步中的目录等），
            // 跳过这一支继续扫，不因为一个坏目录放弃整次导入。
            return;
        }

        for (String childId : childDirectories) {
            if (out.size() >= MAX_SCAN_FILES) {
                return;
            }
            collect(context, resolver, treeUri, childId, out, depth + 1);
        }
    }
}

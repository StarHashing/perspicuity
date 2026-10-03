package com.starhashing.perspicuity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Perspicuity 工作区插件。
 *
 * 职责：让用户可以「绑定」设备上的一个目录（SAF 树 URI），把它当作一个写作
 * 工作区（项目）。绑定后：
 *   1. 目录的读权限被持久化（takePersistableUriPermission），重启 App 后依然有效；
 *   2. 可以递归列出该目录下的 Markdown / 文本文件清单，供前端做搜索；
 *   3. 可以直接打开其中某个文件（返回 document URI + 内容），交给编辑器。
 *
 * 设计原则与 FontImportPlugin 一致：插件只碰用户自己选中的目录，App 不内建、
 * 不携带任何文档内容；所有文件都在用户的存储上，卸载即失联。
 *
 * 「服务器目录」不在本插件范围内 —— 那是后续多人共享编辑的后端能力，前端目前
 * 只保留禁用态入口。
 */
@CapacitorPlugin(name = "Workspace")
public class WorkspacePlugin extends Plugin {
    private static final String TAG = "MarkTextAndroid";
    private static final String CALLBACK_PICK_DIRECTORY = "pickWorkspaceDirectoryResult";
    private static final String CALLBACK_PICK_DOCUMENT = "pickWorkspaceDocumentResult";
    /**
     * 从外部应用（QQ / 微信 / 文件管理器「打开方式」）带进来的项目归档事件。
     * 前端收到后走 parseProjectArchive 判断格式，格式对就导入。
     */
    private static final String EVENT_PROJECT_ARCHIVE_OPENED = "projectArchiveOpened";
    /** 同一个 Intent 可能被 load() 与 handleOnNewIntent() 各处理一次，用 id 去重。 */
    private String lastHandledIncomingIntentId = "";
    /** 与 SAF / 云文档插件一致：绑定目录里的文件也用同一套编码嗅探与默认编码策略，
     *  否则同一个 .md 在「最近文档」路径和「工作区」路径下会被解出不同内容。 */
    private String defaultMarkdownEncoding = "utf8";
    private boolean autoDetectMarkdownEncoding = true;
    private final CharsetSniffer workspaceCharsetSniffer = new IcuCharsetSniffer();
    /** 单次列目录的文件数上限，避免用户误选存储根目录时卡死/吃满内存。 */
    private static final int MAX_WORKSPACE_FILES = 2000;
    /** 递归深度上限，防御异常目录结构或恶意 provider。 */
    private static final int MAX_SCAN_DEPTH = 10;
    /** 视为「可编辑文档」的扩展名。 */
    private static final String[] DOCUMENT_EXTENSIONS = {
        ".md", ".markdown", ".mdx", ".mdown", ".txt", ".text"
    };
    /** 单次读取的文件上限 8 MB：Markdown 文档不该有这么大。 */
    private static final long MAX_DOCUMENT_BYTES = 8L * 1024L * 1024L;

    // ---------------------------------------------------------------------
    // 外部分享 / 打开方式：项目归档导入入口
    //
    // 场景：用户在 QQ / 微信里长按一个 .zip → 打开方式 → 选本 App。
    // 系统会把 zip 以 VIEW 或 SEND 的 Intent 递给我们，这里只负责把它识别出来
    // 并把临时 URI 交给前端；「是不是合法项目归档」由前端 parseProjectArchive
    // 判断（压缩包内部结构才是唯一可靠依据，扩展名 / MIME 都能造假）。
    //
    // 为什么不在 AndroidDocumentsPlugin 里做：那套管线是给 Markdown「打开方式」
    // 用的，isOpenWithMarkdownCandidate 会因为 zip 没有 markdown 扩展名而直接
    // 拒绝。两边职责不同，这里独立一条管线，互不干扰。
    // ---------------------------------------------------------------------

    @Override
    public void load() {
        super.load();
        Activity activity = getActivity();
        if (activity != null) {
            // 冷启动：App 就是被这个 Intent 拉起来的。
            handleIncomingIntent(activity.getIntent());
        }
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        // 热启动：App 已在后台，用户又从别处「打开方式」把 zip 递进来。
        handleIncomingIntent(intent);
    }

    /**
     * 判断这个 Intent 是不是「把某个 zip 归档交给我们打开」，是则 emit 事件。
     *
     * 这里刻意不做扩展名/MIME 的严格校验：国产 ROM 和各家 IM 对 zip 的 MIME
     * 报法五花八门（application/zip、application/x-zip-compressed、
     * application/octet-stream，甚至空 MIME），只按扩展名筛会漏掉一大片。
     * 所以策略是——只要能取到一个内容 URI，就先收下，让前端拆包去判真假。
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) {
            return;
        }
        Uri uri = extractArchiveUri(intent);
        if (uri == null) {
            return;
        }
        if (!isArchiveCandidate(uri, intent)) {
            return;
        }
        if (!markIncomingIntentForHandling(intent)) {
            return;
        }
        // 分享进来的 URI 只带一次性读授权，不持久化也无法持久化——
        // 前端会立刻读取，读完即弃，正合这条授权生命周期的意。
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        JSObject event = new JSObject();
        event.put("uri", uri.toString());
        event.put("name", queryDisplayName(getContext().getContentResolver(), uri));
        event.put("mimeType", intent.getType() == null ? "" : intent.getType());
        event.put("persisted", hasReadPermission(uri));
        Log.i(TAG, "Received project archive from external app: " + safeForLog(uri.toString()));
        notifyListeners(EVENT_PROJECT_ARCHIVE_OPENED, event, true);
    }

    /** VIEW 取 data，SEND 取 EXTRA_STREAM（TIRAMISU+ 用带 Class 的重载）。 */
    private Uri extractArchiveUri(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            return intent.getData();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        @SuppressWarnings("deprecation")
        Uri legacy = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return legacy;
    }

    /**
     * 粗略判断这个 URI 像不像归档：content 协议 + （zip 扩展名 或 zip MIME）。
     *
     * 只作为「值不值得冒泡给前端」的粗筛，不是可信校验——真正判断在 JS 侧
     * 拆 manifest.json 时完成。宽松一点没关系，误收最多是前端提示「不是项目归档」。
     */
    private boolean isArchiveCandidate(Uri uri, Intent intent) {
        if (!"content".equalsIgnoreCase(uri.getScheme())) {
            // 分享进来的可能是 file:// 临时路径，交给前端前先确认能打开。
            return "file".equalsIgnoreCase(uri.getScheme());
        }
        String name = queryDisplayName(getContext().getContentResolver(), uri)
            .toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".zip")) {
            return true;
        }
        String mime = intent.getType();
        if (mime == null) {
            return false;
        }
        mime = mime.toLowerCase(java.util.Locale.ROOT);
        return mime.contains("zip") || mime.equals("application/octet-stream");
    }

    private boolean markIncomingIntentForHandling(Intent intent) {
        String intentId = intent.getAction() + ":" + System.identityHashCode(intent);
        if (intentId.equals(lastHandledIncomingIntentId)) {
            return false;
        }
        lastHandledIncomingIntentId = intentId;
        return true;
    }

    private static String safeForLog(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 160) {
            return value;
        }
        return value.substring(0, 160) + "…";
    }

    // ---------------------------------------------------------------------
    // 目录绑定
    // ---------------------------------------------------------------------

    @PluginMethod
    public void pickDirectory(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        try {
            startActivityForResult(call, intent, CALLBACK_PICK_DIRECTORY);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "No Android folder picker is available", ex);
            call.reject("No Android folder picker is available", "FOLDER_PICKER_UNAVAILABLE", ex);
        }
    }

    @ActivityCallback
    private void pickWorkspaceDirectoryResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            Log.w(TAG, "Missing plugin call for Android workspace folder picker result");
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
            // 同时拿读+写权限：绑定目录的最终目的是在 App 里编辑并写回原文件。
            getContext()
                .getContentResolver()
                .takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
        } catch (SecurityException ex) {
            // 某些 provider 只给读权限。降级为只读绑定，而不是整个绑定失败。
            Log.w(TAG, "Could not persist write access for workspace directory", ex);
            try {
                getContext()
                    .getContentResolver()
                    .takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException readEx) {
                Log.w(TAG, "Could not persist read access for workspace directory", readEx);
            }
        }

        JSObject payload = new JSObject();
        payload.put("canceled", false);
        payload.put("treeUri", treeUri.toString());
        payload.put("displayName", WorkspaceDocumentScanner.treeDisplayName(getContext(), treeUri));
        payload.put("canWrite", hasWritePermission(treeUri));
        call.resolve(payload);
    }

    /** 绑定是否仍然有效（用户可能在系统设置里撤销了授权）。 */
    @PluginMethod
    public void checkPermission(PluginCall call) {
        String raw = call.getString("treeUri");
        JSObject payload = new JSObject();
        if (raw == null || raw.isEmpty()) {
            payload.put("granted", false);
            payload.put("canWrite", false);
            call.resolve(payload);
            return;
        }
        Uri uri = Uri.parse(raw);
        payload.put("granted", hasReadPermission(uri));
        payload.put("canWrite", hasWritePermission(uri));
        call.resolve(payload);
    }

    /**
     * 真实的「写权限」探测：在绑定目录下创建一个临时文件，随即删除。
     *
     * 为什么光看 {@link #hasWritePermission} 不够：那只是「授权记录」的静态
     * 判断，覆盖不了两种现实：
     *   1) 授权记录被判错（历史 bug，字符串比对过严）——此时探测能证明「其实能写」；
     *   2) 授权记录说是可写，但 provider 实际拒绝（只读挂载、网盘只读套餐、
     *      SD 卡写保护）——此时探测能证明「其实不能写」。
     *
     * 返回：
     *   canWrite  —— 探测结论，前端据此修正 UI 的只读态；
     *   grantWrite—— 授权记录的静态结论，仅供诊断；
     *   reason    —— 失败原因分类，前端可据此给不同文案。
     */
    @PluginMethod
    public void probeWrite(PluginCall call) {
        String raw = call.getString("treeUri");
        if (raw == null || raw.isEmpty()) {
            call.reject("A tree URI is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri treeUri = Uri.parse(raw);
        runOffMainThread(call, () -> {
            JSObject payload = new JSObject();
            // 诊断信息：把实际拿到的 treeUri 回传，方便识别 provider / 路径差异。
            payload.put("treeUri", treeUri.toString());
            payload.put("grantWrite", hasWritePermission(treeUri));
            payload.put("grantRead", hasReadPermission(treeUri));
            String rootId = WorkspaceDocumentScanner.treeDocumentId(treeUri);
            if (rootId == null) {
                payload.put("canWrite", false);
                payload.put("reason", "INVALID_TREE");
                payload.put("detail", "treeDocumentId returned null");
                return payload;
            }
            payload.put("rootDocumentId", rootId);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            payload.put("parentUri", parent.toString());
            // 探测文件名带前缀，万一删除失败也便于用户辨认并手动清理。
            String probeName = ".perspicuity-write-probe";
            Uri created = null;
            try {
                created = DocumentsContract.createDocument(
                    getContext().getContentResolver(),
                    parent,
                    "application/octet-stream",
                    probeName
                );
            } catch (SecurityException ex) {
                payload.put("canWrite", false);
                payload.put("reason", "PERMISSION_DENIED");
                payload.put("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                return payload;
            } catch (RuntimeException ex) {
                payload.put("canWrite", false);
                payload.put("reason", "PROVIDER_REJECTED");
                payload.put("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                return payload;
            }
            if (created == null) {
                payload.put("canWrite", false);
                payload.put("reason", "PROVIDER_REJECTED");
                payload.put("detail", "createDocument returned null");
                return payload;
            }
            payload.put("createdUri", created.toString());
            // 创建成功即视为可写；立刻清理探测文件（删不掉也不算失败）。
            try {
                boolean removed = DocumentsContract.deleteDocument(
                    getContext().getContentResolver(),
                    created
                );
                if (!removed) {
                    Log.w(TAG, "Write probe file reported not removed: " + created);
                }
            } catch (RuntimeException ignored) {
                Log.w(TAG, "Write probe file could not be removed: " + created);
            }
            payload.put("canWrite", true);
            payload.put("reason", "OK");
            return payload;
        });
    }

    /**
     * 列出已绑定目录下的文档清单。
     *
     * 返回结构刻意做得很薄（uri / name / relativePath / size / modified），
     * 因为前端只需要「做一个搜索列表」，不需要完整文件树。
     */
    @PluginMethod
    public void listDocuments(PluginCall call) {
        String raw = call.getString("treeUri");
        if (raw == null || raw.isEmpty()) {
            call.reject("A workspace directory URI is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri treeUri = Uri.parse(raw);
        final int limit = clampLimit(call.getInt("limit", MAX_WORKSPACE_FILES));

        runOffMainThread(call, () -> {
            if (!hasReadPermission(treeUri)) {
                throw new WorkspacePermissionLostException();
            }
            JSArray files = WorkspaceDocumentScanner.collectDocuments(
                getContext(),
                treeUri,
                DOCUMENT_EXTENSIONS,
                limit,
                MAX_SCAN_DEPTH
            );
            JSObject payload = new JSObject();
            payload.put("canceled", false);
            payload.put("treeUri", treeUri.toString());
            payload.put("displayName", WorkspaceDocumentScanner.treeDisplayName(getContext(), treeUri));
            payload.put("files", files);
            payload.put("truncated", files.length() >= limit);
            return payload;
        });
    }

    /** 打开绑定目录内的某个文档，返回其内容供编辑器加载。 */
    @PluginMethod
    public void openDocument(PluginCall call) {
        String raw = call.getString("fileUri");
        if (raw == null || raw.isEmpty()) {
            call.reject("A document URI is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri fileUri = Uri.parse(raw);
        // 写权限来自绑定目录那棵树（FLAG_GRANT_PREFIX_URI_PERMISSION 会对树内
        // 所有后代生效），所以这里按「文档所属的树」判定，而不是按单个文件 URI。
        final Uri rootUri = documentsRoot(fileUri);
        runOffMainThread(call, () -> readDocument(fileUri, rootUri));
    }

    // ---------------------------------------------------------------------
    // 单文件索引（项目树的「添加已有文件」）
    //
    // 与目录绑定不同：这里只要一条文档 URI 的持久化授权。文件留在用户原处，
    // App 不复制内容，只在项目树里登记「它在哪」。这也是为什么不走
    // pickDirectory + 前缀权限——用户可能只想引用散落在不同目录里的几个 .md。
    // ---------------------------------------------------------------------

    /**
     * 弹系统文件选择器，拿一条可持久化的单文件授权。
     *
     * 入参 accept：
     *   - "document"（默认）：只筛可编辑的 Markdown / 文本；
     *   - "archive"：不筛类型，任意文件都能选（项目归档 zip 导入用）。
     *
     * 为什么需要 archive 模式：Android 的 EXTRA_MIME_TYPES 是不可靠的白名单，
     * 国产 ROM 的文件管理器会严格按它灰掉「不在名单里」的文件——结果就是
     * .zip 在「导入项目」里根本点不动。对归档导入来说我们本来就要自己在 JS
     * 侧校验 manifest，所以直接放开选择，把判断权拿回自己手里。
     */
    @PluginMethod
    public void pickDocument(PluginCall call) {
        String accept = call.getString("accept", "document");
        boolean anyFile = "archive".equals(accept);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (!anyFile) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "text/markdown", "text/plain", "text/x-markdown", "application/octet-stream"
            });
        }
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        try {
            startActivityForResult(call, intent, CALLBACK_PICK_DOCUMENT);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "No Android document picker is available", ex);
            call.reject("No Android document picker is available", "FOLDER_PICKER_UNAVAILABLE", ex);
        }
    }

    @ActivityCallback
    private void pickWorkspaceDocumentResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            Log.w(TAG, "Missing plugin call for Android workspace document picker result");
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            resolveCanceled(call);
            return;
        }
        Uri fileUri = result.getData().getData();
        if (fileUri == null) {
            resolveCanceled(call);
            return;
        }
        int flags = result.getData().getFlags()
            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        // 有些 provider 只给读——写权限不是索引的硬前提，降级为只读。
        try {
            getContext()
                .getContentResolver()
                .takePersistableUriPermission(
                    fileUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
        } catch (SecurityException ex) {
            Log.w(TAG, "Could not persist write access for workspace document", ex);
            try {
                getContext()
                    .getContentResolver()
                    .takePersistableUriPermission(fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException readEx) {
                Log.w(TAG, "Could not persist read access for workspace document", readEx);
            }
        }

        ContentResolver resolver = getContext().getContentResolver();
        JSObject payload = new JSObject();
        payload.put("canceled", false);
        payload.put("uri", fileUri.toString());
        payload.put("name", queryDisplayName(resolver, fileUri));
        payload.put("providerName", fileUri.getAuthority() == null ? "" : fileUri.getAuthority());
        payload.put("canWrite", hasWritePermission(fileUri));
        payload.put("persisted", hasReadPermission(fileUri));
        payload.put("extension", extensionOf(fileUri, resolver));
        long[] stats = querySizeAndModified(resolver, fileUri);
        payload.put("size", stats[0]);
        payload.put("modified", stats[1]);
        // flags 在降级场景下可能比实际权限更宽，这里仅作诊断信息回传。
        payload.put("grantFlags", flags);
        call.resolve(payload);
    }

    /** 通过持久化的单文件授权读取内容（复用目录路径同一套解码策略）。 */
    @PluginMethod
    public void readByUri(PluginCall call) {
        String raw = call.getString("fileUri");
        if (raw == null || raw.isEmpty()) {
            call.reject("A document URI is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri fileUri = Uri.parse(raw);
        runOffMainThread(call, () -> readDocument(fileUri, fileUri));
    }

    /** 通过持久化的单文件授权写回内容（带 backup + rollback 的原子写）。 */
    @PluginMethod
    public void writeByUri(PluginCall call) {
        String raw = call.getString("fileUri");
        String markdown = call.getString("markdown");
        if (raw == null || raw.isEmpty()) {
            call.reject("A document URI is required", "INVALID_ARGUMENT");
            return;
        }
        if (markdown == null) {
            call.reject("Markdown content is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri fileUri = Uri.parse(raw);
        final String content = markdown;
        final String encoding = normalizeRequestedEncoding(call.getString("encoding"));

        runOffMainThread(call, () -> {
            if (!hasWritePermission(fileUri)) {
                throw new WorkspacePermissionLostException();
            }
            byte[] bytes = MarkdownCodec.encode(
                content,
                new MarkdownWriteOptions(encoding, false)
            );
            // 覆盖已有文件：protectExisting=true 时 SafeDocumentWriter 会先备份，
            // 写失败自动回滚，绝不把用户的原文截断。
            SafeDocumentWriter.write(
                new ContentResolverDocumentIo(
                    getContext().getContentResolver(),
                    fileUri,
                    MarkdownCodec.MAX_MARKDOWN_BYTES
                ),
                bytes,
                true
            );
            JSObject payload = new JSObject();
            payload.put("ok", true);
            payload.put("size", bytes.length);
            return payload;
        });
    }

    // ---------------------------------------------------------------------
    // 目录级写操作（双向可写）
    //
    // 「绑定实体目录」不能只是只读镜像——用户要求在绑定后的项目树里
    // 新建目录 / 新建文件 / 重命名 / 删除 / 移动，而且这些操作必须真正写回
    // 用户的硬盘，而不是只改内存里的虚拟树。
    //
    // 全部走 DocumentsContract（SAF 标准文档操作），对本地存储、SD 卡、
    // 以及支持 DocumentsProvider 的网盘统一生效。写权限来自绑定目录那棵树
    // 的 FLAG_GRANT_PREFIX_URI_PERMISSION：对树内所有后代都有效。
    //
    // 约定：这些方法都返回「操作后该文档的 URI / 名称」，前端拿来做局部更新；
    // 若返回 null 表示 provider 拒绝（比如某些只读网盘），抛出可识别错误。
    // ---------------------------------------------------------------------

    /**
     * 在指定目录下新建一个子目录。
     *
     * 入参：parentUri（父目录 document URI）、name（新目录名）。
     * 出参：{ ok, uri, name }。
     */
    @PluginMethod
    public void createDirectory(PluginCall call) {
        String rawParent = call.getString("parentUri");
        String name = call.getString("name");
        if (rawParent == null || rawParent.isEmpty()) {
            call.reject("A parent directory URI is required", "INVALID_ARGUMENT");
            return;
        }
        if (name == null || name.trim().isEmpty()) {
            call.reject("A directory name is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri parentUri = Uri.parse(rawParent);
        final String dirName = sanitizeEntryName(name);
        runOffMainThread(call, () -> {
            if (!hasWritePermission(documentsRoot(parentUri))) {
                throw new WorkspacePermissionLostException();
            }
            Uri created = createDocumentEntry(
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                dirName
            );
            if (created == null) {
                throw new DocumentReadException(
                    "WORKSPACE_WRITE_REJECTED",
                    "The folder could not be created here"
                );
            }
            JSObject payload = new JSObject();
            payload.put("ok", true);
            payload.put("uri", created.toString());
            payload.put("name", queryDisplayName(getContext().getContentResolver(), created));
            return payload;
        });
    }

    /**
     * 在指定目录下新建一个 Markdown 文件（可选写入初始内容）。
     *
     * 入参：parentUri、name、markdown（可选，初始内容）、encoding（可选）。
     * 出参：{ ok, uri, name, size }。
     *
     * 注意：先 createDocument 建空文件拿到 URI，再用 SafeDocumentWriter 写内容。
     * 之所以不一次写完：有些 provider 的 createDocument 返回的流写入后不
     * 立刻回读得到——分两步更稳，且内容写入复用「带备份回滚」的既有写路径。
     */
    @PluginMethod
    public void createFile(PluginCall call) {
        String rawParent = call.getString("parentUri");
        String name = call.getString("name");
        if (rawParent == null || rawParent.isEmpty()) {
            call.reject("A parent directory URI is required", "INVALID_ARGUMENT");
            return;
        }
        if (name == null || name.trim().isEmpty()) {
            call.reject("A file name is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri parentUri = Uri.parse(rawParent);
        final String fileName = sanitizeEntryName(name);
        final String markdown = call.getString("markdown", "");
        final String encoding = normalizeRequestedEncoding(call.getString("encoding"));

        runOffMainThread(call, () -> {
            if (!hasWritePermission(documentsRoot(parentUri))) {
                throw new WorkspacePermissionLostException();
            }
            Uri created = createDocumentEntry(parentUri, "text/markdown", fileName);
            if (created == null) {
                throw new DocumentReadException(
                    "WORKSPACE_WRITE_REJECTED",
                    "The file could not be created here"
                );
            }
            byte[] bytes = MarkdownCodec.encode(
                markdown == null ? "" : markdown,
                new MarkdownWriteOptions(encoding, false)
            );
            if (bytes.length > 0) {
                SafeDocumentWriter.write(
                    new ContentResolverDocumentIo(
                        getContext().getContentResolver(),
                        created,
                        MarkdownCodec.MAX_MARKDOWN_BYTES
                    ),
                    bytes,
                    // 新建文件是空的，protectExisting=false：不需要备份步骤。
                    false
                );
            }
            JSObject payload = new JSObject();
            payload.put("ok", true);
            payload.put("uri", created.toString());
            payload.put("name", queryDisplayName(getContext().getContentResolver(), created));
            payload.put("size", bytes.length);
            return payload;
        });
    }

    /**
     * 删除一个文档或目录（目录递归删除，由 provider 负责）。
     *
     * 入参：uri（目标 document URI）。
     * 出参：{ ok }。
     */
    @PluginMethod
    public void deleteDocument(PluginCall call) {
        String raw = call.getString("uri");
        if (raw == null || raw.isEmpty()) {
            call.reject("A document URI is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri target = Uri.parse(raw);
        runOffMainThread(call, () -> {
            if (!hasWritePermission(documentsRoot(target))) {
                throw new WorkspacePermissionLostException();
            }
            boolean removed;
            try {
                removed = DocumentsContract.deleteDocument(
                    getContext().getContentResolver(),
                    target
                );
            } catch (java.io.FileNotFoundException ex) {
                // 文件已被用户手动删掉——对「删除」这个意图来说算成功。
                removed = true;
            }
            if (!removed) {
                throw new DocumentReadException(
                    "WORKSPACE_WRITE_REJECTED",
                    "The item could not be deleted"
                );
            }
            JSObject payload = new JSObject();
            payload.put("ok", true);
            return payload;
        });
    }

    /**
     * 重命名一个文档或目录。
     *
     * 入参：uri、newName。
     * 出参：{ ok, uri, name }（新 URI 可能变化，前端要更新登记）。
     */
    @PluginMethod
    public void renameDocument(PluginCall call) {
        String raw = call.getString("uri");
        String newName = call.getString("newName");
        if (raw == null || raw.isEmpty()) {
            call.reject("A document URI is required", "INVALID_ARGUMENT");
            return;
        }
        if (newName == null || newName.trim().isEmpty()) {
            call.reject("A new name is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri target = Uri.parse(raw);
        final String finalName = sanitizeEntryName(newName);
        runOffMainThread(call, () -> {
            if (!hasWritePermission(documentsRoot(target))) {
                throw new WorkspacePermissionLostException();
            }
            Uri renamed = DocumentsContract.renameDocument(
                getContext().getContentResolver(),
                target,
                finalName
            );
            // renameDocument 在某些 provider 上返回 null 表示「不改名」；
            // 对用户来说这时名字已是目标值，按成功处理但沿用原 URI。
            Uri resultUri = renamed == null ? target : renamed;
            JSObject payload = new JSObject();
            payload.put("ok", true);
            payload.put("uri", resultUri.toString());
            payload.put("name", queryDisplayName(getContext().getContentResolver(), resultUri));
            return payload;
        });
    }

    /**
     * 把文档移动到另一个目录。
     *
     * 入参：uri、newParentUri。
     * 出参：{ ok, uri }。
     *
     * 注意：DocumentsContract.moveDocument 从 API 24 起可用；对不支持移动的
     * provider（如部分网盘）会抛 FileNotFoundException，这里翻译成
     * WORKSPACE_WRITE_REJECTED，让前端提示「该位置不支持移动」。
     */
    @PluginMethod
    public void moveDocument(PluginCall call) {
        String raw = call.getString("uri");
        String rawParent = call.getString("newParentUri");
        String rawSourceParent = call.getString("sourceParentUri");
        if (raw == null || raw.isEmpty()) {
            call.reject("A document URI is required", "INVALID_ARGUMENT");
            return;
        }
        if (rawParent == null || rawParent.isEmpty()) {
            call.reject("A target directory URI is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri target = Uri.parse(raw);
        final Uri newParent = Uri.parse(rawParent);
        final Uri sourceParent = (rawSourceParent == null || rawSourceParent.isEmpty())
            ? null
            : Uri.parse(rawSourceParent);
        runOffMainThread(call, () -> {
            Uri root = documentsRoot(target);
            if (!hasWritePermission(root) || !hasWritePermission(documentsRoot(newParent))) {
                throw new WorkspacePermissionLostException();
            }
            // 源父目录：优先用前端显式传入的（精确），拿不到才按 URI 形状尽力反推。
            // SAF 的 documentId 由 provider 内部编码，子项 id 里不含父 id，
            // 从 `.../document/<id>` 反推父目录是不可靠的——这就是此前拖拽失败的真因。
            Uri resolvedSourceParent = (sourceParent != null)
                ? asDocumentUri(sourceParent)
                : documentsParent(target);
            // 移动到「自己当前所在的目录」= 原地不动，视为成功 no-op。
            // 若不拦，回退路径会把源复制进它自己所在目录——provider 遇到同名项
            // 会静默生成「名字 (1)」的副本，再把原名删掉，用户看到的就是
            // 「拖了一下，目录/文件变成『xxx (1)』」甚至以为内容丢了。
            Uri targetParentDoc = asDocumentUri(newParent);
            if (resolvedSourceParent != null
                && targetParentDoc != null
                && resolvedSourceParent.toString().equals(targetParentDoc.toString())) {
                JSObject same = new JSObject();
                same.put("ok", true);
                same.put("uri", target.toString());
                return same;
            }
            // 目标目录里若已存在同名项，且它不是源自身，直接报冲突——不要靠 provider
            // 的「自动加 (1)」来兜，那会让用户以为原项消失、凭空多出一个副本。
            if (targetParentDoc != null
                && hasSameNameChild(targetParentDoc, queryDisplayNameSafe(getContext().getContentResolver(), target), target)) {
                throw new DocumentReadException(
                    "WORKSPACE_MOVE_TARGET_NAME_CONFLICT",
                    "The target folder already has an item named: "
                        + queryDisplayNameSafe(getContext().getContentResolver(), target)
                );
            }
            // 首选：让 provider 自己移动（同一 provider 内最干净，保留元数据）。
            // 但 moveDocument 的支持度参差不齐：externalstorage 在某些 ROM 上、
            // 以及跨 provider 移动时会抛 IOException / 返回 null，甚至直接抛
            // FileNotFoundException。因此这里不再把它当唯一路径，而是**失败就回退
            // 到「复制 + 删除」**——见 relocateByCopy。这正是「有的文件能拖、有的
            // 拖不动」的真因：能否成功取决于 provider 对 moveDocument 的实现细节。
            // 记录 provider 原生 moveDocument 失败的原因，兜底也失败时回报给用户。
            String nativeMoveReason = null;
            if (resolvedSourceParent != null) {
                try {
                    Uri moved = DocumentsContract.moveDocument(
                        getContext().getContentResolver(),
                        target,
                        resolvedSourceParent,
                        newParent
                    );
                    if (moved != null) {
                        JSObject payload = new JSObject();
                        payload.put("ok", true);
                        payload.put("uri", moved.toString());
                        return payload;
                    }
                    nativeMoveReason = "provider returned no result";
                    Log.w(TAG, "moveDocument returned null, falling back to copy+delete: " + target);
                } catch (Exception ex) {
                    // 不区分具体异常类型：任何失败都回退到 copy+delete。
                    nativeMoveReason = ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : (": " + ex.getMessage()));
                    Log.w(TAG, "moveDocument failed, falling back to copy+delete: " + target, ex);
                }
            }
            // 回退路径：把源项「复制」到目标目录，再删掉源项。
            // relocateByCopy 失败时会抛带具体原因码的 DocumentReadException，前端可据此
            // 展示「为什么这个文件拖不动」；这里补一句 provider 原生移动失败的原因。
            Uri relocated;
            try {
                relocated = relocateByCopy(target, newParent);
            } catch (DocumentReadException ex) {
                Log.w(TAG, "relocateByCopy rejected: " + ex.code + " / " + ex.getMessage());
                throw new DocumentReadException(
                    ex.code,
                    ex.getMessage(),
                    nativeMoveReason == null
                        ? ex
                        : new DocumentReadException(
                            "WORKSPACE_MOVE_NATIVE_FAILED",
                            "Native move failed: " + nativeMoveReason,
                            ex
                        )
                );
            }
            JSObject payload = new JSObject();
            payload.put("ok", true);
            payload.put("uri", relocated.toString());
            return payload;
        });
    }

    /**
     * 「复制 + 删除」式搬家：把 source 复制进 targetParent，再删掉 source。
     *
     * 用于 moveDocument 不可靠时的兜底。对文件是「读字节 → 在目标建同名文档 →
     * 写入 → 删源」；对目录是递归处理其每个子项，最后删掉空目录。
     *
     * @return 移动后在目标目录里的新 URI；任何一步失败返回 null（此时不改动源）。
     */
    private Uri relocateByCopy(Uri source, Uri targetParent) throws java.io.IOException, DocumentReadException {
        ContentResolver resolver = getContext().getContentResolver();
        // 名字 / MIME 的读取也要包住：某些 provider 在权限被回收或 URI 失效时，
        // getType / query 会抛 SecurityException 或 IllegalArgumentException（运行时异常）。
        // 早先它们裸在 try 外，异常直接穿透到 runOffMainThread 的 RuntimeException 兜底，
        // 前端只看到笼统的「写入磁盘失败」——这正是「细分原因没生效」的真因之一。
        String name;
        String mime;
        try {
            name = queryDisplayName(resolver, source);
            mime = resolver.getType(source);
        } catch (Exception ex) {
            Log.w(TAG, "relocateByCopy: cannot inspect source " + source, ex);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_SOURCE_UNREADABLE",
                "Could not inspect the source item",
                ex
            );
        }
        if (name == null || name.isEmpty()) {
            name = "untitled";
        }
        boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        if (isDir) {
            return relocateDirectoryByCopy(source, targetParent, name);
        }
        // ---- 文件：读源字节 ----
        byte[] bytes;
        try (java.io.InputStream in = resolver.openInputStream(source)) {
            if (in == null) {
                // 源打开不出流：权限被回收、文件已被删、或 provider 不支持读。
                throw new DocumentReadException(
                    "WORKSPACE_MOVE_SOURCE_UNREADABLE",
                    "Could not read the source item: " + name
                );
            }
            bytes = readAllBytesBounded(in, MAX_DOCUMENT_BYTES);
        } catch (java.io.IOException ex) {
            Log.w(TAG, "relocateByCopy: read source failed for " + name, ex);
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("too large")) {
                throw new DocumentReadException(
                    "WORKSPACE_MOVE_SOURCE_TOO_LARGE",
                    "The source item is too large to move: " + name,
                    ex
                );
            }
            throw new DocumentReadException(
                "WORKSPACE_MOVE_SOURCE_UNREADABLE",
                "Could not read the source item: " + name,
                ex
            );
        }
        if (mime == null || mime.isEmpty()) {
            mime = "application/octet-stream";
        }
        // ---- 在目标目录创建同名文档 ----
        Uri created = asDocumentUri(targetParent);
        // 先查同名：provider 会自动给同名项加「 (1)」而不是报错，
        // 那样「移动」就变成了「复制一份并改名 + 删源」，用户会以为数据丢了。
        if (hasSameNameChild(created, name, source)) {
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_NAME_CONFLICT",
                "The target folder already has an item named: " + name
            );
        }
        Uri newDoc;
        try {
            newDoc = DocumentsContract.createDocument(resolver, created, mime, name);
        } catch (java.io.FileNotFoundException ex) {
            // 目标目录里已有同名项、或目标目录已不可达。
            Log.w(TAG, "relocateByCopy: createDocument FileNotFound for " + name, ex);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_NAME_CONFLICT",
                "The target folder already has an item named: " + name,
                ex
            );
        } catch (Exception ex) {
            Log.w(TAG, "relocateByCopy: createDocument failed for " + name, ex);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_CREATE_FAILED",
                "Could not create the item in the target folder: " + name,
                ex
            );
        }
        if (newDoc == null) {
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_CREATE_FAILED",
                "The target folder refused to create: " + name
            );
        }
        // ---- 写入内容 ----
        try (java.io.OutputStream out = resolver.openOutputStream(newDoc, "wt")) {
            if (out == null) {
                // 写不进去：把刚建的空壳删掉，避免留垃圾。
                tryDelete(resolver, newDoc);
                throw new DocumentReadException(
                    "WORKSPACE_MOVE_TARGET_WRITE_FAILED",
                    "The target folder is not writable: " + name
                );
            }
            out.write(bytes);
            out.flush();
        } catch (DocumentReadException ex) {
            throw ex;
        } catch (Exception ex) {
            Log.w(TAG, "relocateByCopy: write failed for " + name, ex);
            tryDelete(resolver, newDoc);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_WRITE_FAILED",
                "Could not write the copy into the target folder: " + name,
                ex
            );
        }
        // ---- 删除源 ----
        try {
            boolean removed = DocumentsContract.deleteDocument(resolver, source);
            if (!removed) {
                // 源删不掉：为避免「两份都存在」的困惑，回滚目标副本。
                tryDelete(resolver, newDoc);
                throw new DocumentReadException(
                    "WORKSPACE_MOVE_SOURCE_DELETE_FAILED",
                    "The source item could not be removed: " + name
                );
            }
        } catch (DocumentReadException ex) {
            throw ex;
        } catch (Exception ex) {
            Log.w(TAG, "relocateByCopy: delete source failed for " + name, ex);
            tryDelete(resolver, newDoc);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_SOURCE_DELETE_FAILED",
                "The source item could not be removed: " + name,
                ex
            );
        }
        return newDoc;
    }

    /**
     * 目录版「复制 + 删除」：递归复制 source 目录的全部子项到 targetParent 下
     * 一个新目录里，然后删掉源目录。任一步失败即中止（已复制的部分不回滚，
     * 但会尽力删除，避免半成品目录长期残留）。
     */
    private Uri relocateDirectoryByCopy(Uri source, Uri targetParent, String name)
        throws java.io.IOException, DocumentReadException {
        ContentResolver resolver = getContext().getContentResolver();
        Uri created = asDocumentUri(targetParent);
        // 目录也要先查同名：否则 provider 会生成「大纲 (1)」这样的目录，
        // 用户会以为原目录被拖没了（其实是被改名了）。命中的若是源自身则放行。
        if (hasSameNameChild(created, name, source)) {
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_NAME_CONFLICT",
                "The target folder already has an item named: " + name
            );
        }
        Uri newDir;
        try {
            newDir = DocumentsContract.createDocument(
                resolver,
                created,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            );
        } catch (java.io.FileNotFoundException ex) {
            Log.w(TAG, "relocateDirectoryByCopy: createDocument FileNotFound for " + name, ex);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_NAME_CONFLICT",
                "The target folder already has an item named: " + name,
                ex
            );
        } catch (Exception ex) {
            Log.w(TAG, "relocateDirectoryByCopy: createDocument failed for " + name, ex);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_CREATE_FAILED",
                "Could not create the folder in the target: " + name,
                ex
            );
        }
        if (newDir == null) {
            throw new DocumentReadException(
                "WORKSPACE_MOVE_TARGET_CREATE_FAILED",
                "The target folder refused to create: " + name
            );
        }
        // 递归复制子项。
        List<Uri> children = listChildDocumentUris(source);
        for (Uri child : children) {
            try {
                Uri copied = relocateByCopy(child, newDir);
                if (copied == null) {
                    Log.w(TAG, "relocateDirectoryByCopy: child copy returned null: " + child);
                    tryDelete(resolver, newDir);
                    throw new DocumentReadException(
                        "WORKSPACE_MOVE_CHILD_FAILED",
                        "A child item could not be moved: " + name
                    );
                }
            } catch (DocumentReadException ex) {
                Log.w(TAG, "relocateDirectoryByCopy: child copy failed: " + child + " / " + ex.code);
                // 已复制的部分不回滚（尽力删除新目录，避免半成品长期残留）。
                tryDelete(resolver, newDir);
                throw ex;
            }
        }
        // 删源目录（此时应为空）。
        try {
            boolean removed = DocumentsContract.deleteDocument(resolver, source);
            if (!removed) {
                throw new DocumentReadException(
                    "WORKSPACE_MOVE_SOURCE_DELETE_FAILED",
                    "The source folder could not be removed: " + name
                );
            }
        } catch (DocumentReadException ex) {
            throw ex;
        } catch (Exception ex) {
            Log.w(TAG, "relocateDirectoryByCopy: delete source failed for " + name, ex);
            throw new DocumentReadException(
                "WORKSPACE_MOVE_SOURCE_DELETE_FAILED",
                "The source folder could not be removed: " + name,
                ex
            );
        }
        return newDir;
    }

    /** 列出某目录 document URI 下的直接子项 URI（用于递归复制）。 */
    private List<Uri> listChildDocumentUris(Uri parent) {
        List<Uri> out = new ArrayList<>();
        ContentResolver resolver = getContext().getContentResolver();
        String parentDocId = DocumentsContract.getDocumentId(parent);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentDocId);
        String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID };
        try (android.database.Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return out;
            }
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                out.add(DocumentsContract.buildDocumentUriUsingTree(parent, childId));
            }
        } catch (Exception ex) {
            Log.w(TAG, "listChildDocumentUris failed for " + parent, ex);
        }
        return out;
    }

    /**
     * 目标目录里是否已存在同名项（且该项不是源自身）。
     *
     * 用于移动前防冲突：SAF 的 createDocument 在目标已有同名项时会**静默**
     * 生成「名字 (1)」的新项，而不是报错——这会把「移动」变成「复制一份并改名」，
     * 用户看到原项消失、凭空多出个带 (1) 的副本，像数据丢了。这里提前识别。
     *
     * @param parent     目标父目录 document URI
     * @param displayName 期望的名字
     * @param source     源 URI（若命中的就是源自身，不算冲突）
     */
    private boolean hasSameNameChild(Uri parent, String displayName, Uri source) {
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        String sourceDocId;
        try {
            sourceDocId = DocumentsContract.getDocumentId(source);
        } catch (Exception ex) {
            sourceDocId = null;
        }
        ContentResolver resolver = getContext().getContentResolver();
        String parentDocId;
        try {
            parentDocId = DocumentsContract.getDocumentId(parent);
        } catch (Exception ex) {
            return false;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentDocId);
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        };
        try (android.database.Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return false;
            }
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String childName = cursor.getString(1);
                if (!displayName.equals(childName)) {
                    continue;
                }
                if (sourceDocId != null && sourceDocId.equals(childId)) {
                    // 命中的就是源自身（例如移动到原目录），不算冲突。
                    continue;
                }
                return true;
            }
        } catch (Exception ex) {
            Log.w(TAG, "hasSameNameChild failed for " + parent, ex);
        }
        return false;
    }

    /** queryDisplayName 的安全版：任何异常都不抛，返回空串。 */
    private String queryDisplayNameSafe(ContentResolver resolver, Uri uri) {
        try {
            String name = queryDisplayName(resolver, uri);
            return name == null ? "" : name;
        } catch (Exception ex) {
            return "";
        }
    }

    /** 尽力删除一个 document，失败只记日志不抛。 */
    private void tryDelete(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ex) {
            Log.w(TAG, "tryDelete failed for " + uri, ex);
        }
    }

    /**
     * 新建文档：按 provider 能力在父目录里 createDocument；失败返回 null。
     *
     * 抽出来是因为 createDirectory / createFile 只差 MIME 类型。
     *
     * 关键修复（v0.4.18）：`DocumentsContract.createDocument` 要求 parent 是
     * **document URI**（形如 `.../tree/<treeId>/document/<docId>`）。但前端「在
     * 绑定根目录下新建」时传进来的往往是 **tree URI**（形如 `.../tree/<treeId>`，
     * 没有 `/document/` 段）。对 `com.android.externalstorage.documents` 这类
     * provider，直接拿 tree URI 调 createDocument 会失败——表现为新建文件夹 /
     * 新建文件「明明有写权限却建不了」。
     *
     * 因此在真正调用前，先把 parentUri 归一成该树根的 document URI：
     *   `.../tree/<treeId>` → `.../tree/<treeId>/document/<treeId>`。
     * 已经是 document URI 的（含 `/document/` 段）原样透传。
     */
    private Uri createDocumentEntry(Uri parentUri, String mimeType, String displayName)
        throws java.io.IOException {
        Uri target = asDocumentUri(parentUri);
        try {
            return DocumentsContract.createDocument(
                getContext().getContentResolver(),
                target,
                mimeType,
                displayName
            );
        } catch (java.io.FileNotFoundException ex) {
            throw new java.io.IOException("The parent folder is no longer reachable", ex);
        } catch (IllegalArgumentException ex) {
            // 个别 provider 对 MIME_TYPE_DIR 或 text/markdown 不认，抛 IllegalArgumentException。
            Log.w(TAG, "Provider rejected createDocument for " + mimeType, ex);
            return null;
        }
    }

    /**
     * 把一个「可能只是树 URI」的目录 URI 归一成合法的 document URI。
     *
     * - 已含 `/document/` 段：本就是一个 document URI，原样返回。
     * - 形如 `.../tree/<treeId>`（树根）：用 buildDocumentUriUsingTree 补出
     *   该树根的 document URI。
     * - 其它形状（拿不到 treeId）：原样返回，交给 provider 自己报错。
     */
    private Uri asDocumentUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        // 已带 /document/ 段：就是 document URI。
        List<String> segments = uri.getPathSegments();
        if (segments.contains("document")) {
            return uri;
        }
        // 树 URI：还原出树根 document URI。
        if (segments.size() >= 2 && "tree".equals(segments.get(0))) {
            String treeId = segments.get(1);
            try {
                return DocumentsContract.buildDocumentUriUsingTree(uri, treeId);
            } catch (IllegalArgumentException ex) {
                Log.w(TAG, "Could not derive document URI from tree " + uri, ex);
                return uri;
            }
        }
        return uri;
    }

    /**
     * 求一个 document URI 的父目录 URI。
     *
     * 只对 `.../document/<parentId>/children/<childId>` 形状有效——去掉最后两段。
     * 注意：这只是**尽力而为的兜底**。SAF 的 documentId 由 provider 内部编码，
     * 从子项 id 反推父 id 本就不可靠；而扫描得到的 URI 是
     * `.../document/<docId>`（无 children 段），此时**无法**推出父目录，
     * 返回 null，交给调用方处理（moveDocument 会据此报错）。
     *
     * 此前这里在拿不到父目录时退回 tree URI，会让 moveDocument 收到一个非法
     * 的 sourceParent 而失败——这正是拖拽无法移动的真因。修正为返回 null。
     */
    private Uri documentsParent(Uri documentUri) {
        List<String> segments = documentUri.getPathSegments();
        int documentIndex = segments.indexOf("document");
        // 需要 `document/<parentId>/children/<childId>` 共 4 段才推得出父目录。
        if (documentIndex < 0 || segments.size() < documentIndex + 4) {
            return null;
        }
        if (!"children".equals(segments.get(documentIndex + 2))) {
            return null;
        }
        // 重新拼一个不带最后两段（children/childId）的 document URI。
        StringBuilder builder = new StringBuilder();
        builder.append(documentUri.getScheme()).append("://")
            .append(documentUri.getAuthority());
        for (int i = 0; i <= documentIndex + 1; i++) {
            builder.append('/').append(segments.get(i));
        }
        return Uri.parse(builder.toString());
    }

    /** 文件名/目录名清洗：去掉路径分隔符与控制字符，保留中文与空格。 */
    private String sanitizeEntryName(String value) {
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim();
        return cleaned.isEmpty() ? "untitled" : cleaned;
    }

    // ---------------------------------------------------------------------
    // 项目归档导入 / 导出（zip）
    //
    // 前端把项目树序列化成条目列表（projectArchive.ts），这里只负责把条目
    // 写成 zip 落盘、或把 zip 读回条目列表。JS 不碰二进制，中文名靠
    // java.util.zip 的 UTF-8 保证。
    // ---------------------------------------------------------------------

    /**
     * 把一个项目导出成 zip，写到用户选中的目录里。
     *
     * 入参：treeUri（目标目录，需写权限）、fileName（归档文件名，含 .zip）、
     *      entries（[{path, content, isDirectory}]）。
     * 出参：{ ok, uri, name, size }。
     */
    @PluginMethod
    public void exportProjectArchive(PluginCall call) {
        String rawTree = call.getString("treeUri");
        String fileName = call.getString("fileName");
        JSArray rawEntries = call.getArray("entries");
        if (rawTree == null || rawTree.isEmpty()) {
            call.reject("A target directory URI is required", "INVALID_ARGUMENT");
            return;
        }
        if (fileName == null || fileName.isEmpty()) {
            call.reject("An archive file name is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri treeUri = Uri.parse(rawTree);
        final String name = sanitizeArchiveName(fileName);
        final List<ProjectArchiveCodec.ArchiveEntry> entries;
        try {
            entries = ProjectArchiveCodec.fromJsArray(rawEntries);
        } catch (org.json.JSONException ex) {
            call.reject("The archive entries are malformed", "INVALID_ARGUMENT", ex);
            return;
        }
        if (entries.isEmpty()) {
            call.reject("There is nothing to export", "INVALID_ARGUMENT");
            return;
        }
        runOffMainThread(call, () -> {
            if (!hasWritePermission(treeUri)) {
                throw new WorkspacePermissionLostException();
            }
            String uri = ProjectArchiveCodec.writeArchive(
                getContext().getContentResolver(),
                treeUri,
                name,
                entries
            );
            JSObject payload = new JSObject();
            payload.put("ok", true);
            payload.put("uri", uri);
            payload.put("name", name);
            payload.put("entryCount", entries.size());
            return payload;
        });
    }

    /**
     * 读一个 zip 归档，返回条目列表交给前端还原项目树。
     *
     * 入参：fileUri（zip 的 URI，需读权限）、grantFlags（可选，来自分享 Intent）。
     * 出参：{ ok, entries: [{path, content, isDirectory}], entryCount }。
     *
     * 注意这里**不**用 hasReadPermission 做前置校验：从 QQ / 微信分享进来的
     * URI 只带一次性读授权（FLAG_GRANT_READ_URI_PERMISSION），不会进
     * getPersistedUriPermissions()，用它判断会把所有分享导入都误杀。
     * 权限是否真的够，交给 openInputStream 自己抛 SecurityException 来回答。
     */
    @PluginMethod
    public void importProjectArchive(PluginCall call) {
        String raw = call.getString("fileUri");
        if (raw == null || raw.isEmpty()) {
            call.reject("An archive URI is required", "INVALID_ARGUMENT");
            return;
        }
        final Uri archiveUri = Uri.parse(raw);
        runOffMainThread(call, () -> {
            List<ProjectArchiveCodec.ArchiveEntry> entries;
            try {
                entries = ProjectArchiveCodec.readArchive(
                    getContext().getContentResolver(),
                    archiveUri
                );
            } catch (SecurityException ex) {
                throw new WorkspacePermissionLostException();
            } catch (java.util.zip.ZipException | java.io.EOFException ex) {
                // 不是合法 zip（或已损坏、被截断）——这条错误要能单独被前端认出来。
                //
                // 注意必须把 EOFException 一并归到这里：java.util.zip 在遇到被截断的
                // 归档时抛的是 EOFException（而非 ZipException），它是 IOException 的
                // 子类，若漏掉就会穿透到 runOffMainThread 的 IOException 分支，被误报成
                // 泛化的「文件夹操作失败」，前端只能显示最含糊的提示。
                throw new DocumentReadException(
                    "INVALID_ARCHIVE",
                    "This file is not a readable project archive",
                    ex
                );
            }
            JSObject payload = new JSObject();
            payload.put("ok", true);
            payload.put("entries", ProjectArchiveCodec.toJsArray(entries));
            payload.put("entryCount", entries.size());
            return payload;
        });
    }

    /** 归档文件名只保留安全字符，强制 .zip 后缀。 */
    private String sanitizeArchiveName(String value) {
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) {
            cleaned = "project";
        }
        if (!cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            cleaned = cleaned + ".zip";
        }
        return cleaned;
    }

    /** 用户没指定编码时，跟随工作区既有的默认编码策略，保证同文件同写法。 */
    private String normalizeRequestedEncoding(String requested) {
        if (requested == null || requested.isEmpty()) {
            return MarkdownCodec.normalizeEncoding(defaultMarkdownEncoding);
        }
        return MarkdownCodec.normalizeEncoding(requested);
    }

    /** 从 display name 里取扩展名（小写、不含点）；取不到给 `md`。 */
    private String extensionOf(Uri uri, ContentResolver resolver) {
        String name = queryDisplayName(resolver, uri);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "md";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 返回 [size, modified]，未知均为 -1。 */
    private long[] querySizeAndModified(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long size = cursor.isNull(0) ? -1L : cursor.getLong(0);
                long modified = cursor.isNull(1) ? -1L : cursor.getLong(1);
                return new long[] { size, modified };
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not read workspace document metadata", ex);
        }
        return new long[] { -1L, -1L };
    }

    /**
     * 从 `tree/<treeId>/document/<docId>` 形状的 URI 里还原出所属的树 URI。
     *
     * 取不到树段时退回原 URI——查询持久化授权本身是安全的，命中不了就返回
     * 只读，不会误判成可写。
     */
    private Uri documentsRoot(Uri documentUri) {
        List<String> segments = documentUri.getPathSegments();
        if (segments.size() >= 2 && "tree".equals(segments.get(0))) {
            String treeId = segments.get(1);
            return DocumentsContract.buildTreeDocumentUri(documentUri.getAuthority(), treeId);
        }
        return documentUri;
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private JSObject readDocument(Uri fileUri, Uri rootUri)
        throws java.io.IOException, DocumentReadException {
        ContentResolver resolver = getContext().getContentResolver();
        JSObject payload = new JSObject();
        payload.put("canceled", false);
        payload.put("sourceUri", fileUri.toString());
        payload.put("displayName", queryDisplayName(resolver, fileUri));
        // 让工作区文档在编辑器里与 SAF 文档同权：能判断是否可写回、能否重开。
        payload.put("providerName", fileUri.getAuthority() == null ? "" : fileUri.getAuthority());
        payload.put("canWrite", hasWritePermission(rootUri));
        payload.put("persisted", hasReadPermission(rootUri));
        // 文档所在目录的绝对路径（如 /storage/emulated/0/WenJian/.../），供前端把
        // Markdown 里的相对图片路径锚定到真实文件。拿不到时返回空串，前端会跳过。
        payload.put("dirPath", resolveDocumentDirectoryPath(fileUri));
        byte[] bytes;
        try (java.io.InputStream in = resolver.openInputStream(fileUri)) {
            if (in == null) {
                throw new java.io.IOException("Could not open the selected document");
            }
            bytes = readAllBytesBounded(in, MAX_DOCUMENT_BYTES);
        }
        DecodedMarkdown decoded = MarkdownCodec.decode(
            bytes,
            defaultMarkdownEncoding,
            autoDetectMarkdownEncoding,
            workspaceCharsetSniffer
        );
        payload.put("markdown", decoded.markdown);
        payload.put("encoding", MarkdownCodec.normalizeEncoding(decoded.encoding));
        payload.put("hasEncodingBom", decoded.hasBom);
        return payload;
    }

    /**
     * 反解一个文档 content URI 的「父目录绝对路径」，供前端把 Markdown 里的
     * 相对图片路径（`./a.png`、`docs/a.png`）锚定到真实文件。
     *
     * <p>只处理外部存储 provider（`com.android.externalstorage.documents`）：它的
     * documentId 形如 `primary:WenJian/Perspicuity/docs/a.png`，其中 `primary`
     * 固定映射到主外部存储根（{@link Environment#getExternalStorageDirectory()}），
     * 其余是相对该根的正斜杠路径。其他 provider（下载、云盘、第三方）拿不到
     * 可用的文件系统路径，返回空串由前端兜底跳过。
     *
     * <p>返回的路径以 `/` 结尾，且不会包含文件名本身。
     */
    private String resolveDocumentDirectoryPath(Uri fileUri) {
        try {
            String authority = fileUri.getAuthority();
            if (!"com.android.externalstorage.documents".equals(authority)) {
                return "";
            }
            String documentId = DocumentsContract.getDocumentId(fileUri);
            if (documentId == null || documentId.isEmpty()) {
                return "";
            }
            int colon = documentId.indexOf(':');
            if (colon <= 0) {
                return "";
            }
            String volume = documentId.substring(0, colon);
            String relative = documentId.substring(colon + 1);
            String base;
            if ("primary".equalsIgnoreCase(volume)) {
                java.io.File primary = Environment.getExternalStorageDirectory();
                if (primary == null) {
                    return "";
                }
                base = primary.getAbsolutePath();
            } else {
                // 次要卷（可移动 SD 卡）：/storage/<volume>。
                base = "/storage/" + volume;
            }
            // 去掉文件名段，只保留目录；relative 为空表示就是根目录本身。
            String directoryRelative = "";
            int slash = relative.lastIndexOf('/');
            if (slash >= 0) {
                directoryRelative = relative.substring(0, slash);
            }
            String path = base;
            if (!directoryRelative.isEmpty()) {
                path = base.endsWith("/") ? base + directoryRelative : base + "/" + directoryRelative;
            }
            return path.endsWith("/") ? path : path + "/";
        }
        catch (RuntimeException ex) {
            // getDocumentId 对非标准 URI 会抛异常；这不是错误路径，静默降级即可。
            return "";
        }
    }

    private static byte[] readAllBytesBounded(java.io.InputStream in, long maxBytes)
        throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new java.io.IOException("The selected document is too large to open");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not read workspace document name", ex);
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private boolean hasReadPermission(Uri uri) {
        return hasPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private boolean hasWritePermission(Uri uri) {
        return hasPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    /**
     * 判断某个 URI 是否持有指定的持久化授权。
     *
     * 历史坑：此前用 `target.equals(permission.getUri().toString())` 做字符串
     * 精确比对，结果大面积误判为「无权限」——同一个目录，Intent 回调里拿到的
     * 字符串与 getPersistedUriPermissions() 里的字符串在多个细节上并不一致：
     *   - 尾斜杠：`.../tree/primary%3ADocs` 与 `.../tree/primary%3ADocs/`；
     *   - 百分号编码大小写：`%3A` 与 `%3a`；
     *   - 某些 provider 会附加 `.document` / `document/` 段；
     *   - MediaStore / 第三方 provider 会做 URI 规范化。
     * 只要有一个字符对不上，就判定成只读，UI 便把所有写按钮灰掉——
     * 用户明明在系统弹框里点了「允许修改」，App 却坚称只读，这正是那个 bug。
     *
     * 现在改为三层判定：
     *   1) Uri 语义比对（normalizeToString 归一去尾斜杠、统一编码大小写）；
     *   2) 前缀匹配——SAF 树 URI 授权天然覆盖其所有后代（配合
     *      FLAG_GRANT_PREFIX_URI_PERMISSION），所以document URI 只要以某个已授权
     *      树 URI 为前缀也应视为有权；
     *   3) 都失败才回落到 false。
     */
    private boolean hasPermission(Uri uri, int flag) {
        if (uri == null) {
            return false;
        }
        List<UriPermission> permissions = getContext()
            .getContentResolver()
            .getPersistedUriPermissions();
        String target = normalizeUriForAccess(uri);
        String targetPrefix = target.endsWith("/") ? target : target + "/";
        for (UriPermission permission : permissions) {
            Uri granted = permission.getUri();
            if (granted == null) {
                continue;
            }
            String grant = normalizeUriForAccess(granted);
            boolean matches = target.equals(grant) || targetPrefix.startsWith(grant + "/");
            if (!matches) {
                continue;
            }
            if (flag == Intent.FLAG_GRANT_WRITE_URI_PERMISSION) {
                if (permission.isWritePermission()) {
                    return true;
                }
            } else if (permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 URI 归一成便于比较的形式：统一百分号编码大小写、去掉多余尾斜杠。
     *
     * 只做「比较用」的归一，不改变真实 URI；大小写统一是安全的，因为百分号
     * 编码的十六进制大小写等价（%3A == %3a，解码结果相同）。
     */
    private static String normalizeUriForAccess(Uri uri) {
        String raw = uri.toString();
        StringBuilder out = new StringBuilder(raw.length());
        boolean inEscape = false;
        for (int i = 0; i < raw.length(); i += 1) {
            char c = raw.charAt(i);
            if (inEscape) {
                out.append(Character.toLowerCase(c));
                inEscape = false;
                continue;
            }
            if (c == '%') {
                out.append(c);
                inEscape = true;
                continue;
            }
            out.append(c);
        }
        String normalized = out.toString();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return MAX_WORKSPACE_FILES;
        }
        return Math.min(limit, MAX_WORKSPACE_FILES);
    }

    private void resolveCanceled(PluginCall call) {
        JSObject payload = new JSObject();
        payload.put("canceled", true);
        call.resolve(payload);
    }

    /** 把重 IO（递归扫描 / 读文件）放到后台线程，避免大目录 ANR。 */
    private void runOffMainThread(PluginCall call, WorkspaceTask task) {
        new Thread(() -> {
            try {
                JSObject payload = task.run();
                getActivity().runOnUiThread(() -> call.resolve(payload));
            } catch (WorkspacePermissionLostException ex) {
                getActivity().runOnUiThread(() ->
                    call.reject(
                        "Permission for the bound folder is no longer available",
                        "WORKSPACE_PERMISSION_LOST"
                    )
                );
            } catch (DocumentReadException ex) {
                Log.w(TAG, "Workspace document decode rejected: " + ex.getMessage());
                getActivity().runOnUiThread(() -> call.reject(ex.getMessage(), ex.code, ex));
            } catch (java.io.IOException ex) {
                Log.e(TAG, "Workspace file operation failed", ex);
                getActivity().runOnUiThread(() ->
                    call.reject("The folder operation failed", "WORKSPACE_IO_FAILED", ex)
                );
            } catch (RuntimeException ex) {
                Log.e(TAG, "Unexpected workspace failure", ex);
                getActivity().runOnUiThread(() ->
                    call.reject("The folder operation failed", "WORKSPACE_IO_FAILED", ex)
                );
            }
        }, "perspicuity-workspace").start();
    }

    private interface WorkspaceTask {
        JSObject run() throws java.io.IOException, DocumentReadException;
    }

    private static final class WorkspacePermissionLostException extends RuntimeException {}
}

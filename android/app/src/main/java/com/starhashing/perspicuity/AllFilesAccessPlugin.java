package com.starhashing.perspicuity;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;

/**
 * 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）开关。
 *
 * <p>这是 SAF {@code ACTION_OPEN_DOCUMENT_TREE} 之外的另一条路径：授予后应用可以
 * 直接以 {@link java.io.File} 读写共享存储的任意路径，不必逐个目录弹框授权。
 * 代价是——这是一项「敏感」权限：
 *
 * <ul>
 *   <li>Google Play 只允许 文件管理器 / 杀毒 / 备份 三类应用声明，编辑器声明会被
 *       拒审。因此 release 包在 Manifest 层就把它剥离（见
 *       {@code src/release/AndroidManifest.xml}），只有自用 debug 包可用；</li>
 *   <li>授予后无法由应用代码「申请」，必须跳到系统设置页面由用户手动打开
 *       （{@code Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION}）；</li>
 *   <li>部分国产 ROM（MIUI / ColorOS / EMUI）对该页面有额外限制，用户开了也
 *       可能不生效——所以它只是「高级开关」，正常编辑依旧首选 SAF。</li>
 * </ul>
 */
@CapacitorPlugin(name = "AllFilesAccess")
public class AllFilesAccessPlugin extends Plugin {

    /** 判断当前是否已持有「所有文件访问」权限。 */
    @PluginMethod
    public void isGranted(PluginCall call) {
        JSObject payload = new JSObject();
        payload.put("granted", checkGranted());
        payload.put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        call.resolve(payload);
    }

    /**
     * 跳转系统「所有文件访问」设置页，由用户手动开启。
     *
     * <p>优先使用带包名的 {@code ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION}
     * 直达本应用条目；个别 ROM 不支持时退回全局的
     * {@code ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION}。
     */
    @PluginMethod
    public void requestAccess(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            JSObject payload = new JSObject();
            payload.put("opened", false);
            payload.put("granted", true);
            payload.put("reason", "NOT_REQUIRED");
            call.resolve(payload);
            return;
        }
        Context context = getContext();
        boolean opened = false;
        if (!checkGranted()) {
            try {
                Intent appIntent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
                );
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(appIntent);
                opened = true;
            } catch (RuntimeException appEx) {
                try {
                    Intent globalIntent = new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    );
                    globalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(globalIntent);
                    opened = true;
                } catch (RuntimeException globalEx) {
                    opened = false;
                }
            }
        }
        JSObject payload = new JSObject();
        payload.put("opened", opened);
        // 此刻多半还没开；返回当前状态供前端做乐观/轮询判断。
        payload.put("granted", checkGranted());
        call.resolve(payload);
    }

    /**
     * 列出共享存储里可访问的「物理根目录」，供前端在拿到全盘权限后直接呈现。
     *
     * <p>权限未授予时返回空数组——调用方应先查 {@link #isGranted}。
     */
    @PluginMethod
    public void listRoots(PluginCall call) {
        JSArray roots = new JSArray();
        if (checkGranted()) {
            // 主外部存储是唯一保证存在的根；SD 卡等次要卷仅在权限下可见。
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null && primary.exists()) {
                roots.put(describeRoot(primary, "primary"));
            }
            try {
                File[] volumes = getContext().getExternalFilesDirs(null);
                if (volumes != null) {
                    for (File volume : volumes) {
                        if (volume == null) {
                            continue;
                        }
                        // getExternalFilesDirs 返回的是 .../Android/data/<pkg>/files，
                        // 往上退四级拿到卷根（…/files → <pkg> → data → Android → 卷根）。
                        File cursor = volume;
                        for (int i = 0; i < 4 && cursor != null; i += 1) {
                            cursor = cursor.getParentFile();
                        }
                        if (cursor == null || cursor.equals(primary)) {
                            continue;
                        }
                        JSObject entry = describeRoot(cursor, "secondary");
                        if (entry != null) {
                            roots.put(entry);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // 次要卷枚举失败不影响主存储。
            }
        }
        JSObject payload = new JSObject();
        payload.put("roots", roots);
        payload.put("granted", checkGranted());
        call.resolve(payload);
    }

    private boolean checkGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Android 10 及以下：MANAGE_EXTERNAL_STORAGE 不存在，退化为「是否持有
        // 传统存储权限」。这里只做保守判断，避免误报未授予。
        return getContext().checkSelfPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private JSObject describeRoot(File root, String kind) {
        if (root == null || !root.exists()) {
            return null;
        }
        JSObject entry = new JSObject();
        entry.put("path", root.getAbsolutePath());
        entry.put("name", root.getName().isEmpty() ? root.getAbsolutePath() : root.getName());
        entry.put("kind", kind);
        entry.put("readable", root.canRead());
        entry.put("writable", root.canWrite());
        return entry;
    }
}

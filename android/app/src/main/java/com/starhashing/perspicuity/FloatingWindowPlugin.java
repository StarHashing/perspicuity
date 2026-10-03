package com.starhashing.perspicuity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 全局便签浮窗的前端桥。
 *
 * <p>前端通过 {@code Capacitor.Plugins.FloatingWindow} 调用本插件，实现：
 *
 * <ul>
 *   <li>{@code isGranted}：查询是否已持有悬浮窗权限；</li>
 *   <li>{@code requestPermission}：跳系统「显示在其他应用上层」页手动授予；</li>
 *   <li>{@code showNote}：把一段 markdown 挂成浮窗（native 里由
 *       {@link FloatingNoteService} 负责渲染与交互）；</li>
 *   <li>{@code updateActive}：把最后一个浮窗的内容换成新的；</li>
 *   <li>{@code hideAll}：收起全部浮窗（保留前台服务与常驻通知）；</li>
 *   <li>{@code stop}：收全部并停服务；</li>
 *   <li>{@code count}：当前浮窗数量。</li>
 * </ul>
 *
 * <p><b>硬约束</b>：悬浮窗权限必须由用户手动在系统设置页打开，应用侧无法用
 * 运行时弹框申请（{@code SYSTEM_ALERT_WINDOW} 特殊性）；ColorOS 等国产 ROM
 * 可能还需额外开启「后台弹出界面」。
 */
@CapacitorPlugin(name = "FloatingWindow")
public class FloatingWindowPlugin extends Plugin {

    /** 是否已持有悬浮窗权限。低于 Android 6 的系统默认放行。 */
    @PluginMethod
    public void isGranted(PluginCall call) {
        JSObject payload = new JSObject();
        payload.put("granted", checkGranted());
        payload.put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        call.resolve(payload);
    }

    /**
     * 跳转系统「显示在其他应用上层」设置页，由用户手动开启。
     *
     * <p>优先使用带包名的 {@code ACTION_MANAGE_OVERLAY_PERMISSION} 直达本应用
     * 条目；个别 ROM 不支持时退回不带包名的全局页。
     */
    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
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
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
                );
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(appIntent);
                opened = true;
            } catch (RuntimeException appEx) {
                try {
                    Intent globalIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
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
     * 新开一个浮窗。
     *
     * <p>参数：{@code title}(可选，浮窗标题)、{@code markdown}(正文)、
     * {@code opacity}(可选，0.1~1.0，默认 0.9)。
     *
     * <p>未授予悬浮窗权限时不会抛异常，返回 {@code granted:false}，
     * 由前端提示用户先授权。
     */
    @PluginMethod
    public void showNote(PluginCall call) {
        Context context = getContext();
        if (!checkGranted()) {
            JSObject payload = new JSObject();
            payload.put("granted", false);
            payload.put("shown", false);
            call.resolve(payload);
            return;
        }
        String title = call.getString("title", "");
        String markdown = call.getString("markdown", "");
        Double opacityRaw = call.getDouble("opacity");
        float opacity = opacityRaw == null ? 0.9f : opacityRaw.floatValue();

        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, FloatingNoteService.class);
        intent.setAction(FloatingNoteService.ACTION_SHOW);
        intent.putExtra(FloatingNoteService.EXTRA_TITLE, title);
        intent.putExtra(FloatingNoteService.EXTRA_MARKDOWN, markdown);
        intent.putExtra(FloatingNoteService.EXTRA_OPACITY, opacity);
        try {
            startServiceForContext(app, intent);
        } catch (RuntimeException ex) {
            JSObject payload = new JSObject();
            payload.put("granted", true);
            payload.put("shown", false);
            payload.put("reason", "START_FAILED");
            payload.put("message", ex.getMessage());
            call.resolve(payload);
            return;
        }

        JSObject payload = new JSObject();
        payload.put("granted", true);
        payload.put("shown", true);
        call.resolve(payload);
    }

    /** 把最后一个浮窗的内容换成新的（用于「更新为当前文档」）。 */
    @PluginMethod
    public void updateActive(PluginCall call) {
        String title = call.getString("title", "");
        String markdown = call.getString("markdown", "");
        FloatingNoteService service = FloatingNoteService.instance();
        if (service != null) {
            service.updateMostRecent(title, markdown);
        } else {
            // 服务没起来时，等价于新开一个。
            Context app = getContext().getApplicationContext();
            Intent intent = new Intent(app, FloatingNoteService.class);
            intent.setAction(FloatingNoteService.ACTION_SHOW);
            intent.putExtra(FloatingNoteService.EXTRA_TITLE, title);
            intent.putExtra(FloatingNoteService.EXTRA_MARKDOWN, markdown);
            try {
                startServiceForContext(app, intent);
            } catch (RuntimeException ignored) {
                // 未授权时静默失败，由 count 反馈真实状态。
            }
        }
        JSObject payload = new JSObject();
        payload.put("updated", true);
        payload.put("count", service == null ? 0 : service.noteCount());
        call.resolve(payload);
    }

    /** 收起全部浮窗（保留服务与常驻通知）。 */
    @PluginMethod
    public void hideAll(PluginCall call) {
        Context app = getContext().getApplicationContext();
        Intent intent = new Intent(app, FloatingNoteService.class);
        intent.setAction(FloatingNoteService.ACTION_HIDE_ALL);
        FloatingNoteService service = FloatingNoteService.instance();
        if (service != null) {
            service.closeAll();
        } else {
            try {
                startServiceForContext(app, intent);
            } catch (RuntimeException ignored) {
                // 服务未起、权限未授予时无从收起，直接返回 0。
            }
        }
        JSObject payload = new JSObject();
        payload.put("count", FloatingNoteService.instance() == null
            ? 0
            : FloatingNoteService.instance().noteCount());
        call.resolve(payload);
    }

    /** 收全部浮窗并停止前台服务。 */
    @PluginMethod
    public void stop(PluginCall call) {
        FloatingNoteService service = FloatingNoteService.instance();
        if (service != null) {
            service.shutdown();
        } else {
            Context app = getContext().getApplicationContext();
            app.stopService(new Intent(app, FloatingNoteService.class));
        }
        JSObject payload = new JSObject();
        payload.put("stopped", true);
        call.resolve(payload);
    }

    /** 当前浮窗数量。 */
    @PluginMethod
    public void count(PluginCall call) {
        FloatingNoteService service = FloatingNoteService.instance();
        JSObject payload = new JSObject();
        payload.put("count", service == null ? 0 : service.noteCount());
        payload.put("running", service != null);
        payload.put("granted", checkGranted());
        call.resolve(payload);
    }

    private boolean checkGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(getContext());
    }

    /**
     * Android 8+ 起后台直接 {@code startService} 会抛
     * {@code IllegalStateException}，改为 {@code startForegroundService}，
     * 由 {@link FloatingNoteService#onStartCommand} 尽快
     * {@code startForeground} 满足约束。
     */
    private void startServiceForContext(Context app, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }
}

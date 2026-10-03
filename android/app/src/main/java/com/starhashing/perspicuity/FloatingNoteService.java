package com.starhashing.perspicuity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 全局便签浮窗服务。
 *
 * <p>把 Markdown 文档「钉」在其他 app 之上（边玩游戏 / 看教程边瞄一眼），
 * 由前台服务持有 {@link WindowManager} 浮窗，退到后台也不消失。
 *
 * <p>每个浮窗具备：拖动移动、双指缩放、透明度调节、点击穿透、贴边收纳、
 * 标题显隐锁定。内容由一个独立轻量 WebView 渲染（{@code floating.html}），
 * 通过 {@link FloatingBridge} 让页面内的「收起/关闭」按钮回传原生。
 *
 * <p>进程内单例：插件层通过 {@link #instance()} 拿它引用，避免重复起服务。
 */
public class FloatingNoteService extends Service {

    public static final String ACTION_SHOW = "com.starhashing.perspicuity.action.FLOATING_SHOW";
    public static final String ACTION_HIDE_ALL =
        "com.starhashing.perspicuity.action.FLOATING_HIDE_ALL";
    public static final String ACTION_UPDATE_ACTIVE =
        "com.starhashing.perspicuity.action.FLOATING_UPDATE_ACTIVE";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MARKDOWN = "markdown";
    public static final String EXTRA_OPACITY = "opacity";

    private static final String CHANNEL_ID = "perspicuity_floating_note";
    private static final int NOTIFICATION_ID = 0x5F10A7;
    private static final String APP_ASSET_ENTRY = "public/floating.html";

    /** 穿透模式提供的「一键还原」广播，防止用户把浮窗点没了。 */
    public static final String ACTION_EXIT_PENETRATE =
        "com.starhashing.perspicuity.action.FLOATING_EXIT_PENETRATE";
    private static final String PENETRATE_ACTION_ID = "exit_penetrate";

    private static FloatingNoteService sInstance;

    private WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<FloatingNote> notes = new ArrayList<>();

    /** 处于穿透态的浮窗数量，用于决定常驻通知里是否显示「还原」入口。 */
    private int penetratingCount = 0;

    /** 让插件层能拿到当前活着的服务，直接调用控制方法。 */
    public static FloatingNoteService instance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_HIDE_ALL.equals(action)) {
                closeAll();
            } else if (ACTION_EXIT_PENETRATE.equals(action)) {
                exitAllPenetration();
            } else if (ACTION_UPDATE_ACTIVE.equals(action)) {
                // 由「更新当前活动文档」入口触发，只更新最后一个浮窗的内容。
                String title = intent.getStringExtra(EXTRA_TITLE);
                String markdown = intent.getStringExtra(EXTRA_MARKDOWN);
                updateMostRecent(title, markdown);
            } else if (intent.hasExtra(EXTRA_MARKDOWN)) {
                String title = intent.getStringExtra(EXTRA_TITLE);
                String markdown = intent.getStringExtra(EXTRA_MARKDOWN);
                float opacity = intent.getFloatExtra(EXTRA_OPACITY, 0.9f);
                showNote(title, markdown, opacity);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        closeAll();
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    // --- 插件层公开 API -----------------------------------------------------

    /** 新开一个浮窗。 */
    public void showNote(String title, String markdown, float opacity) {
        mainHandler.post(() -> addNoteView(title, markdown, opacity));
    }

    /** 关闭全部浮窗（保留服务，通知常驻）。 */
    public void closeAll() {
        mainHandler.post(() -> {
            for (FloatingNote note : new ArrayList<>(notes)) {
                note.remove();
            }
            notes.clear();
        });
    }

    /** 关闭全部并停止服务。 */
    public void shutdown() {
        mainHandler.post(() -> {
            closeAll();
            stopSelf();
        });
    }

    /** 某个浮窗穿透状态变化：刷新计数与常驻通知。 */
    private void onPenetrationChanged() {
        int count = 0;
        for (FloatingNote note : notes) {
            if (note.isPenetrating()) {
                count++;
            }
        }
        penetratingCount = count;
        refreshNotification();
    }

    /** 一键退出所有浮窗的穿透态（通知「还原浮窗」触发）。 */
    private void exitAllPenetration() {
        for (FloatingNote note : notes) {
            note.exitPenetration();
        }
        penetratingCount = 0;
        refreshNotification();
    }

    /** 把最后一个浮窗的内容换成新的（用于「更新为当前文档」）。 */
    public void updateMostRecent(String title, String markdown) {
        mainHandler.post(() -> {
            if (notes.isEmpty()) {
                addNoteView(title, markdown, 0.9f);
                return;
            }
            notes.get(notes.size() - 1).updateContent(title, markdown);
        });
    }

    public int noteCount() {
        return notes.size();
    }

    // --- 前台通知 -----------------------------------------------------------

    private void startForegroundInternal() {
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "便签浮窗",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持悬浮便签在其他应用之上");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
    }

    /** 构建常驻通知；穿透态下附带「还原浮窗」按钮（逃生口）。 */
    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, launch, pendingFlags
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        builder
            .setContentTitle("便签浮窗已开启")
            .setContentText(
                penetratingCount > 0
                    ? "点击穿透中：如需恢复交互请点「还原浮窗」"
                    : "文档正悬浮在其他应用之上"
            )
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(contentIntent)
            .setOngoing(true);

        if (penetratingCount > 0) {
            Intent exit = new Intent(this, FloatingNoteService.class);
            exit.setAction(ACTION_EXIT_PENETRATE);
            PendingIntent exitIntent = PendingIntent.getService(
                this, 1, exit, pendingFlags
            );
            builder.addAction(
                android.R.drawable.ic_menu_revert, "还原浮窗", exitIntent
            );
        }
        return builder.build();
    }

    /** 刷新常驻通知（穿透状态变化时调用）。 */
    private void refreshNotification() {
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    // --- 单个浮窗视图 -------------------------------------------------------

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        );
    }

    private void addNoteView(String title, String markdown, float opacity) {
        FloatingNote note = new FloatingNote(title, markdown, opacity);
        if (note.attach()) {
            notes.add(note);
        }
    }

    /** 一个浮窗 = 标题栏 + WebView 内容 + 工具条。 */
    private final class FloatingNote {

        private final String initialTitle;
        private final String initialMarkdown;
        private float opacity;

        private FrameLayout root;
        private LinearLayout column;
        private LinearLayout headerBar;
        private TextView titleView;
        private WebView contentView;
        private SeekBar opacityBar;
        private TextView opacityLabel;
        private LinearLayout toolbar;
        private final List<ImageView> headerIcons = new ArrayList<>();
        private ImageView penetrationToggle;
        private ImageView lockToggle;
        private ImageView closeButton;

        private WindowManager.LayoutParams params;
        private boolean penThrough = false;
        private boolean locked = false;
        private boolean collapsed = false;

        /** 穿透模式下独立于主窗口的「退出穿透」小把手（始终可点，防死锁）。 */
        private View penetrateExitView;
        private WindowManager.LayoutParams penetrateExitParams;

        /** 当前已弹出的「复制/全选」菜单，再次触发前先关掉，避免叠成一堆。 */
        private PopupMenu selectPopup;
        /** 上一次已上报过的选中文字，用于去重，避免同一段文字反复弹菜单。 */
        private String lastReportedSelection = "";

        /** 内容主题：0=深色 1=浅色 2=纸黄。 */
        private int themeIndex = 0;
        /** 正文字号（px），11~24。 */
        private int fontSize = 14;

        // 主题配色：[卡片底色, 标题栏色, 工具条色, 强调色(dark 主题取白)]。
        private final int[][] THEMES = {
            { 0xF01E1E2A, 0xFF1E1E2A, 0xEE26263A, 0xFFEDEDF5 }, // 深色
            { 0xF2FFFFFF, 0xFFFFFFFF, 0xF2F3F3F8, 0xFF33333A }, // 浅色
            { 0xF2F5ECD7, 0xFFF0E4C8, 0xF2EADFC2, 0xFF4A3A20 }, // 纸黄
        };
        private final String[] THEME_NAMES = { "dark", "light", "sepia" };

        // 拖动/缩放用的一次性状态。
        private float downRawX, downRawY;
        private int startX, startY;
        private float lastSpan = 0f;
        private int startWidth, startHeight;
        private boolean dragging = false;
        private final int touchSlop;

        /** 右下角「拖拽调整大小」手柄。 */
        private View resizeHandle;
        /** 缩放拖拽状态：按下点与起始尺寸。 */
        private float resizeDownRawX, resizeDownRawY;
        private int resizeStartWidth, resizeStartHeight;
        private boolean resizing = false;
        /** 用户调好的「展开态」高度，供收起/展开时还原。 */
        private int expandedHeight = 0;

        FloatingNote(String title, String markdown, float opacity) {
            this.initialTitle = title == null || title.isEmpty() ? "便签" : title;
            this.initialMarkdown = markdown == null ? "" : markdown;
            this.opacity = clampOpacity(opacity);
            this.touchSlop = ViewConfiguration.get(FloatingNoteService.this).getScaledTouchSlop();
        }

        boolean attach() {
            if (!canDrawOverlays()) {
                return false;
            }
            buildViews();
            params = new WindowManager.LayoutParams(
                dp(260),
                dp(220),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE,
                // 关键 flag 组合：
                //  - FLAG_NOT_FOCUSABLE   不抢焦点 → 下层 App 的输入法能正常弹
                //  - FLAG_NOT_TOUCH_MODAL 窗口外的触摸直接交给下层 App
                //                          （这是「只点自己才有反应，点别处不影响」的关键）
                // 注意：绝不能用 FLAG_ALT_FOCUSABLE_IM，它会长期占住输入法焦点，
                //       导致开着浮窗时任何 App 都弹不出键盘。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = dp(24);
            params.y = dp(120);
            try {
                windowManager.addView(root, params);
            } catch (RuntimeException error) {
                Toast.makeText(
                    FloatingNoteService.this, "添加浮窗失败：" + error.getMessage(),
                    Toast.LENGTH_LONG
                ).show();
                return false;
            }
            applyOpacity();
            loadContent();
            return true;
        }

        void remove() {
            hidePenetrateExitHandle();
            if (root != null) {
                try {
                    windowManager.removeView(root);
                } catch (RuntimeException ignored) {
                    // 已经移除。
}
                if (contentView != null) {
                    contentView.destroy();
                }
                root = null;
            }
        }

        void updateContent(String title, String markdown) {
            if (titleView != null && title != null && !title.isEmpty()) {
                titleView.setText(title);
            }
            if (contentView != null) {
                renderMarkdown(markdown == null ? "" : markdown);
            }
        }

        // --- 视图构建 -------------------------------------------------------

        private void buildViews() {
            root = new FrameLayout(FloatingNoteService.this);

            column = new LinearLayout(FloatingNoteService.this);
            column.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(14));
            background.setColor(0xF2FFFFFF);
            background.setStroke(dp(1), 0x33000000);
            column.setBackground(background);
            column.setClipToOutline(true);
            column.setElevation(dp(10));
            root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            buildHeader(column);
            buildContent(column);
            buildToolbar(column);
            buildResizeHandle();
            applyTheme();
        }

        /**
         * 右下角「拖拽调整大小」手柄。
         *
         * <p>在 {@link #root}（FrameLayout）右下角叠一个小小的斜纹三角，按住并拖动
         * 即可像桌面窗口一样改浮窗宽高。手柄本身很薄、贴角，不影响正文阅读。
         */
        private void buildResizeHandle() {
            // 用三条斜线拼出经典的「resize 角标」外观。
            LinearLayout grip = new LinearLayout(FloatingNoteService.this);
            grip.setOrientation(LinearLayout.VERTICAL);
            grip.setGravity(Gravity.BOTTOM | Gravity.END);
            grip.setPadding(dp(6), dp(6), dp(3), dp(3));

            for (int i = 0; i < 3; i++) {
                View line = new View(FloatingNoteService.this);
                GradientDrawable lineBg = new GradientDrawable();
                lineBg.setColor(0x998A8A99);
                lineBg.setCornerRadius(dp(1));
                line.setBackground(lineBg);
                int len = dp(6 + i * 5);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    len, dp(2)
                );
                lp.gravity = Gravity.END;
                lp.topMargin = dp(3);
                grip.addView(line, lp);
            }
            grip.setBackgroundColor(0x00000000);
            // 让手柄区域足够大、便于按住（透明的 26dp 方块）。
            grip.setMinimumWidth(dp(26));
            grip.setMinimumHeight(dp(26));

            FrameLayout.LayoutParams gripParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            gripParams.gravity = Gravity.BOTTOM | Gravity.END;

            grip.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        resizeDownRawX = event.getRawX();
                        resizeDownRawY = event.getRawY();
                        resizeStartWidth = params.width;
                        resizeStartHeight = params.height;
                        resizing = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (locked) {
                            return true;
                        }
                        float dx = event.getRawX() - resizeDownRawX;
                        float dy = event.getRawY() - resizeDownRawY;
                        if (!resizing
                            && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            resizing = true;
                        }
                        if (resizing) {
                            int maxW = screenWidth() - params.x - dp(8);
                            int maxH = screenHeight() - params.y - dp(8);
                            params.width = clamp(
                                Math.round(resizeStartWidth + dx),
                                dp(160), Math.max(dp(160), maxW)
                            );
                            params.height = clamp(
                                Math.round(resizeStartHeight + dy),
                                dp(140), Math.max(dp(140), maxH)
                            );
                            // 变宽后若右边超出屏幕，把窗口往左挪回来，别让内容跑到屏幕外。
                            int rightOverflow = params.x + params.width
                                - screenWidth() + dp(8);
                            if (rightOverflow > 0) {
                                params.x = Math.max(dp(8), params.x - rightOverflow);
                            }
                            safeUpdateLayout();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (resizing && !collapsed) {
                            // 记住用户调好的展开态高度，收起再展开时还原。
                            expandedHeight = params.height;
                        }
                        resizing = false;
                        return true;
                    default:
                        return false;
                }
            });

            resizeHandle = grip;
            root.addView(grip, gripParams);
        }

        private static int clamp(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }

        private int screenWidth() {
            return getResources().getDisplayMetrics().widthPixels;
        }

        private int screenHeight() {
            return getResources().getDisplayMetrics().heightPixels;
        }

        private void buildHeader(LinearLayout parent) {
            headerBar = new LinearLayout(FloatingNoteService.this);
            headerBar.setOrientation(LinearLayout.HORIZONTAL);
            headerBar.setGravity(Gravity.CENTER_VERTICAL);
            headerBar.setBackgroundColor(0xFF1E1E2A);
            headerBar.setPadding(dp(10), dp(6), dp(6), dp(6));

            titleView = new TextView(FloatingNoteService.this);
            titleView.setText(initialTitle);
            titleView.setTextColor(0xFFFFFFFF);
            titleView.setTextSize(13f);
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            headerBar.addView(titleView, titleParams);

            // 锁定（防误拖）
            lockToggle = makeHeaderIcon(android.R.drawable.ic_lock_lock);
            lockToggle.setOnClickListener(v -> {
                locked = !locked;
                lockToggle.setAlpha(locked ? 1f : 0.45f);
            });
            lockToggle.setAlpha(0.45f);
            headerBar.addView(lockToggle);

            // 主题循环：深色 → 浅色 → 纸黄
            ImageView themeButton = makeHeaderIcon(android.R.drawable.ic_menu_slideshow);
            themeButton.setOnClickListener(v -> cycleTheme());
            headerBar.addView(themeButton);
            // 穿透开关（放标题栏，避免在工具栏底部被误触）。
            penetrationToggle = makeHeaderIcon(android.R.drawable.ic_menu_manage);
            penetrationToggle.setOnClickListener(v -> togglePenetration());
            headerBar.addView(penetrationToggle);

            // 贴边收纳
            ImageView collapseButton = makeHeaderIcon(android.R.drawable.ic_menu_revert);
            collapseButton.setOnClickListener(v -> toggleCollapse());
            headerBar.addView(collapseButton);

            // 关闭本浮窗
            closeButton = makeHeaderIcon(android.R.drawable.ic_menu_close_clear_cancel);
            closeButton.setOnClickListener(v -> {
                remove();
                notes.remove(this);
            });
            headerBar.addView(closeButton);

            parent.addView(headerBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            installDragHandler();
        }

        private ImageView makeHeaderIcon(int resId) {
            ImageView view = new ImageView(FloatingNoteService.this);
            view.setImageResource(resId);
            view.setColorFilter(0xFFEDEDF5);
            view.setPadding(dp(6), dp(6), dp(6), dp(6));
            int size = dp(30);
            view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            headerIcons.add(view);
            return view;
        }

        private TextView makeToolbarText(String label) {
            TextView view = new TextView(FloatingNoteService.this);
            view.setText(label);
            view.setTextSize(12f);
            view.setTextColor(0xFF5A5A6E);
            view.setPadding(dp(7), dp(4), dp(7), dp(4));
            return view;
        }

        /** 循环切换主题：深色 → 浅色 → 纸黄。 */
        private void cycleTheme() {
            themeIndex = (themeIndex + 1) % THEMES.length;
            applyTheme();
        }

        /** 调整正文字号（px），区间 11~24。 */
        private void changeFontSize(int delta) {
            int next = fontSize + delta;
            if (next < 11) {
                next = 11;
            }
            if (next > 24) {
                next = 24;
            }
            if (next == fontSize) {
                return;
            }
            fontSize = next;
            applyFontSize();
        }

        /** 把当前主题套到原生视图（卡片底/标题栏/工具条/图标）+ 通知页面。 */
        private void applyTheme() {
            int[] theme = THEMES[themeIndex];
            int cardBg = theme[0];
            int headerBg = theme[1];
            int toolbarBg = theme[2];
            int iconColor = theme[3];

            if (column != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(14));
                bg.setColor(cardBg);
                bg.setStroke(dp(1), 0x33000000);
                column.setBackground(bg);
            }
            if (headerBar != null) {
                headerBar.setBackgroundColor(headerBg);
            }
            if (toolbar != null) {
                toolbar.setBackgroundColor(toolbarBg);
            }
            for (ImageView icon : headerIcons) {
                icon.setColorFilter(iconColor);
            }
            if (penetrationToggle != null) {
                penetrationToggle.setColorFilter(penThrough ? 0xFF2E7D32 : 0x805A5A6E);
            }
            if (opacityLabel != null) {
                opacityLabel.setTextColor(0xB05A5A6E);
            }
            // 通知页面切主题 + 字号。
            if (contentView != null) {
                String themeName = THEME_NAMES[themeIndex];
                contentView.evaluateJavascript(
                    "window.__perspicuityTheme && window.__perspicuityTheme('"
                        + themeName + "');",
                    null
                );
                applyFontSize();
            }
        }

        /** 通知页面切字号。 */
        private void applyFontSize() {
            if (contentView == null) {
                return;
            }
            contentView.evaluateJavascript(
                "window.__perspicuityFontSize && window.__perspicuityFontSize("
                    + fontSize + ");",
                null
            );
        }

        private void buildContent(LinearLayout parent) {
            contentView = new WebView(FloatingNoteService.this);
            contentView.setBackgroundColor(Color.TRANSPARENT);
            WebSettings settings = contentView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);
            settings.setTextZoom(100);
            // 允许长按选中并复制正文文字。
            contentView.setLongClickable(true);
            contentView.setHapticFeedbackEnabled(true);
            contentView.setOnLongClickListener(v -> false);
            contentView.setWebViewClient(new WebViewClient());
            contentView.addJavascriptInterface(new FloatingBridge(this), "PerspicuityFloating");

            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            );
            contentParams.weight = 1f;
            parent.addView(contentView, contentParams);
        }

        private void buildToolbar(LinearLayout parent) {
            toolbar = new LinearLayout(FloatingNoteService.this);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setBackgroundColor(0xEEF3F3F8);
            toolbar.setPadding(dp(10), dp(4), dp(10), dp(4));

            // 字号缩小 / 放大。
            TextView fontMinus = makeToolbarText("A-");
            fontMinus.setOnClickListener(v -> changeFontSize(-1));
            toolbar.addView(fontMinus);

            TextView fontPlus = makeToolbarText("A+");
            fontPlus.setOnClickListener(v -> changeFontSize(1));
            toolbar.addView(fontPlus);

            // 复制全文（长按选中走原生菜单；这里是「一键复制整篇」的快捷方式）。
            TextView copyAll = makeToolbarText("复制");
            copyAll.setOnClickListener(v -> {
                if (contentView != null) {
                    contentView.evaluateJavascript(
                        "(function(){var t=document.body?document.body.innerText:'';"
                            + "if(window.PerspicuityFloating&&PerspicuityFloating.onSelectText)"
                            + "{PerspicuityFloating.onSelectText(t);}})();",
                        null
                    );
                }
            });
            toolbar.addView(copyAll);

            opacityLabel = new TextView(FloatingNoteService.this);
            opacityLabel.setText("透明");
            opacityLabel.setTextSize(11f);
            opacityLabel.setTextColor(0xFF5A5A6E);
            opacityLabel.setPadding(dp(8), 0, dp(4), 0);
            toolbar.addView(opacityLabel);

            opacityBar = new SeekBar(FloatingNoteService.this);
            opacityBar.setMax(90);
            opacityBar.setProgress(Math.round((opacity - 0.1f) * 100f / 0.9f));
            opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    opacity = clampOpacity(0.1f + 0.9f * (progress / 100f));
                    applyOpacity();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            toolbar.addView(opacityBar, barParams);

            parent.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        // --- 交互 -----------------------------------------------------------

        private void installDragHandler() {
            headerBar.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (locked) {
                            return true;
                        }
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            params.x = startX + (int) dx;
                            params.y = startY + (int) dy;
                            safeUpdateLayout();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            snapToEdge();
                        }
                        return true;
                    default:
                        return false;
                }
            });

            // 双指缩放：在根容器上捏合调整浮窗大小。
            root.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    if (event.getPointerCount() == 2) {
                        float span = spacing(event);
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_POINTER_DOWN:
                                lastSpan = span;
                                startWidth = params.width;
                                startHeight = params.height;
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                if (lastSpan > 0f) {
                                    float scale = span / lastSpan;
                                    params.width = Math.max(dp(160),
                                        Math.round(startWidth * scale));
                                    params.height = Math.max(dp(140),
                                        Math.round(startHeight * scale));
                                    safeUpdateLayout();
                                }
                                return true;
                            case MotionEvent.ACTION_POINTER_UP:
                                lastSpan = 0f;
                                return true;
                            default:
                                break;
                        }
                    }
                    return false;
                }
            });
        }

        private float spacing(MotionEvent event) {
            if (event.getPointerCount() < 2) {
                return 0f;
            }
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        private void safeUpdateLayout() {
            if (root == null || root.getParent() == null) {
                return;
            }
            try {
                windowManager.updateViewLayout(root, params);
            } catch (RuntimeException ignored) {
                // 视图已移除。
            }
        }

        private void snapToEdge() {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int centerX = params.x + params.width / 2;
            params.x = centerX < screenWidth / 2
                ? dp(8)
                : screenWidth - params.width - dp(8);
            safeUpdateLayout();
        }

        private void toggleCollapse() {
            collapsed = !collapsed;
            headerBar.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            if (contentView != null) {
                ViewGroup.LayoutParams contentParams = contentView.getLayoutParams();
                contentParams.height = collapsed ? dp(40) : 0;
                if (contentParams instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) contentParams).weight = collapsed ? 0f : 1f;
                }
                contentView.setLayoutParams(contentParams);
                contentView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            }
            if (collapsed) {
                // 记住收起前的自定义高度，展开时还原（不然用户调好的大小会被抹掉）。
                if (params.height > dp(60)) {
                    expandedHeight = params.height;
                }
                params.height = dp(48);
            } else {
                params.height = expandedHeight > 0 ? expandedHeight : dp(220);
            }
            // 收起态是一根细条，右下角缩放手柄没意义且会挡到，直接藏掉。
            if (resizeHandle != null) {
                resizeHandle.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            }
            safeUpdateLayout();
        }

        private void togglePenetration() {
            setPenetration(!penThrough, true);
        }

        /**
         * 切换「触摸穿透」模式（方案 B）。
         *
         * <p>语义：浮窗<b>外观完全不变</b>（还是原来大小、还显示文章），
         * 但整个浮窗变成「只读装饰」——所有触摸自动落到下层 App。
         * 实现方式：给主窗口加 {@code FLAG_NOT_TOUCHABLE}。
         *
         * <p>因为 {@code FLAG_NOT_TOUCHABLE} 是<b>整窗级</b>的，开了之后浮窗
         * 自己所有按钮都点不动（会死锁），所以穿透期间额外创建一个
         * <b>独立的小把手窗口</b>（常驻屏幕边缘、始终可点），点它即可退出穿透。
         */
        private void setPenetration(boolean on, boolean notifyService) {
            if (penThrough == on) {
                return;
            }
            penThrough = on;
            if (root == null) {
                return;
            }
            if (on) {
                // 外观不变，只是让触摸穿透到下层。
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                safeUpdateLayout();
                // 把穿透齿轮图标点亮成绿色，作为「当前已穿透」的提示。
                if (penetrationToggle != null) {
                    penetrationToggle.setColorFilter(0xFF2E7D32);
                }
                showPenetrateExitHandle();
                Toast.makeText(
                    FloatingNoteService.this,
                    "已开启触摸穿透：点绿色「退出」把手可恢复",
                    Toast.LENGTH_LONG
                ).show();
            } else {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                safeUpdateLayout();
                if (penetrationToggle != null) {
                    penetrationToggle.setColorFilter(0x805A5A6E);
                }
                hidePenetrateExitHandle();
            }
            if (notifyService) {
                onPenetrationChanged();
            }
        }

        /** 创建/显示「退出穿透」小把手（独立浮窗，始终可点，可拖动）。 */
        private void showPenetrateExitHandle() {
            if (penetrateExitView != null) {
                return;
            }
            LinearLayout handle = new LinearLayout(FloatingNoteService.this);
            handle.setOrientation(LinearLayout.HORIZONTAL);
            handle.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0xF02E7D32);
            bg.setStroke(dp(1), 0x66FFFFFF);
            handle.setBackground(bg);
            handle.setElevation(dp(12));

            TextView icon = new TextView(FloatingNoteService.this);
            icon.setText("✕");
            icon.setTextSize(11f);
            icon.setTextColor(0xFFFFFFFF);
            handle.addView(icon);

            TextView label = new TextView(FloatingNoteService.this);
            label.setText("退出");
            label.setTextSize(10f);
            label.setTextColor(0xFFFFFFFF);
            label.setPadding(dp(1), dp(4), dp(8), dp(4));
            handle.addView(label);

            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(16);
            lp.y = dp(120);

            // 拖动 vs 点击：移动超过阈值算拖拽，不触发退出；否则算点击，退出穿透。
            final int touchSlop = dp(6);
            final float[] downRaw = new float[2];
            final int[] downPos = new int[2];
            final boolean[] moved = new boolean[1];
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRaw[0] = event.getRawX();
                        downRaw[1] = event.getRawY();
                        downPos[0] = lp.x;
                        downPos[1] = lp.y;
                        moved[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRaw[0];
                        float dy = event.getRawY() - downRaw[1];
                        if (!moved[0]
                            && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            moved[0] = true;
                        }
                        if (moved[0]) {
                            lp.x = downPos[0] + (int) dx;
                            lp.y = downPos[1] + (int) dy;
                            try {
                                windowManager.updateViewLayout(handle, lp);
                            } catch (RuntimeException ignored) {
                                // 视图已移除。
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved[0]) {
                            // 视为点击 → 退出穿透。
                            setPenetration(false, true);
                        }
                        return true;
                    default:
                        return false;
                }
            });

            try {
                windowManager.addView(handle, lp);
                penetrateExitView = handle;
                penetrateExitParams = lp;
            } catch (RuntimeException error) {
                Toast.makeText(
                    FloatingNoteService.this,
                    "添加退出把柄失败：" + error.getMessage(),
                    Toast.LENGTH_LONG
                ).show();
            }
        }

        /** 移除「退出穿透」小把手。 */
        private void hidePenetrateExitHandle() {
            if (penetrateExitView == null) {
                return;
            }
            try {
                windowManager.removeView(penetrateExitView);
            } catch (RuntimeException ignored) {
                // 已移除。
            }
            penetrateExitView = null;
            penetrateExitParams = null;
        }

        /** 供服务层强制退出穿透（通知按钮触发）。 */
        void exitPenetration() {
            if (penThrough) {
                setPenetration(false, false);
                Toast.makeText(
                    FloatingNoteService.this, "已还原浮窗交互", Toast.LENGTH_SHORT
                ).show();
            }
        }

        boolean isPenetrating() {
            return penThrough;
        }

        private void applyOpacity() {
            float alpha = clampOpacity(opacity);
            if (root != null) {
                root.setAlpha(alpha);
            }
        }

        // --- 内容渲染 -------------------------------------------------------

        private void loadContent() {
            contentView.loadUrl("file:///android_asset/" + APP_ASSET_ENTRY);
            contentView.setWebViewClient(new WebViewClient() {
                private boolean loaded = false;

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (loaded) {
                        return;
                    }
                    loaded = true;
                    renderMarkdown(initialMarkdown);
                }
            });
        }

        private void renderMarkdown(String markdown) {
            if (contentView == null) {
                return;
            }
            try {
                JSONObject payload = new JSONObject();
                payload.put("title", initialTitle);
                payload.put("markdown", markdown);
                String encoded = JSONObject.quote(payload.toString());
                contentView.evaluateJavascript(
                    "window.__perspicuityRender && window.__perspicuityRender(" + encoded + ");",
                    null
                );
            } catch (Exception ignored) {
                // 渲染失败不崩浮窗。
            }
        }

        /** 页面内按钮（收起/关闭）回传原生。 */
        void onPageRequest(String action) {
            mainHandler.post(() -> {
                if ("close".equals(action)) {
                    remove();
                    notes.remove(this);
                } else if ("collapse".equals(action)) {
                    toggleCollapse();
                } else if ("penetrate".equals(action)) {
                    togglePenetration();
                }
            });
        }

        /**
         * 页面长按选中文字后回调：弹出<b>原生</b>菜单（复制 / 全选）。
         *
         * <p>悬浮窗带 {@code FLAG_NOT_FOCUSABLE}，系统不会给它弹文本选择
         * ActionMode（就是那个「复制/剪切」浮动条），所以这里用原生
         * {@link PopupMenu} 自己造一个，锚定在选中文字附近。
         */
        void onPageSelectText(String text) {
            mainHandler.post(() -> {
                if (contentView == null || root == null) {
                    return;
                }
                final String selected = text == null ? "" : text.trim();
                // 去重：同一段文字反复上报（拖动过程中 selectionchange 高频触发）时
                // 只弹一次，避免叠出一堆复制菜单。
                if (selected.equals(lastReportedSelection) && selectPopup != null) {
                    return;
                }
                lastReportedSelection = selected;
                // 先关掉上一个菜单，保证同一时刻只有一个。
                if (selectPopup != null) {
                    try {
                        selectPopup.dismiss();
                    } catch (RuntimeException ignored) {
                        // 已经关掉了。
                    }
                    selectPopup = null;
                }
                PopupMenu menu = new PopupMenu(FloatingNoteService.this, contentView);
                if (selected.isEmpty()) {
                    menu.getMenu().add(0, 1, 0, "全选");
                } else {
                    String preview = selected.length() > 8
                        ? selected.substring(0, 8) + "…"
                        : selected;
                    menu.getMenu().add(0, 0, 0, "复制：" + preview);
                    menu.getMenu().add(0, 1, 1, "全选");
                }
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 0) {
                        copyToClipboard(selected);
                    } else if (item.getItemId() == 1) {
                        // 全选：让页面把正文全部选中并回传。
                        contentView.evaluateJavascript(
                            "window.__perspicuitySelectAll && window.__perspicuitySelectAll();",
                            null
                        );
                    }
                    return true;
                });
                menu.setOnDismissListener(d -> {
                    if (selectPopup == menu) {
                        selectPopup = null;
                    }
                });
                selectPopup = menu;
                menu.show();
            });
        }

        /** 把文字写入系统剪贴板并提示。 */
        private void copyToClipboard(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            ClipboardManager cm = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Perspicuity", text));
            }
            Toast.makeText(
                FloatingNoteService.this, "已复制到剪贴板", Toast.LENGTH_SHORT
            ).show();
        }
    }

    /** WebView 的 JS 桥：页面能调用原生收起/关闭/复制。 */
    public static class FloatingBridge {
        private final FloatingNote host;
        FloatingBridge(FloatingNote host) {
            this.host = host;
        }
        @JavascriptInterface
        public void request(String action) {
            if (host != null) {
                host.onPageRequest(action);
            }
        }
        /**
         * 页面长按选中文字后回调：弹出原生「复制 / 全选」菜单。
         *
         * @param text 当前选中的文字（可能为空表示仅想全选）
         */
        @JavascriptInterface
        public void onSelectText(String text) {
            if (host != null) {
                host.onPageSelectText(text);
            }
        }
    }

    // --- 工具 ---------------------------------------------------------------

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        return true;
    }

    private static float clampOpacity(float value) {
        if (value < 0.1f) {
            return 0.1f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    /** 供插件层复用：字符串化用于日志。 */
    public static String describe(String title) {
        return String.format(Locale.US, "note title=%s", title == null ? "" : title);
    }
}

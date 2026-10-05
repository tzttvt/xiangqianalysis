package com.dsh.xiangqi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * 悬浮窗实时识谱：屏幕上浮着一个小棋盘，每隔几秒自动抓屏识谱，
 * 识别出局面后用皮卡鱼算出推荐着法并显示在棋盘上（Pro象棋 同款玩法）。
 * 面板可拖动，点「暂停/继续」控制是否自动识别。
 */
public class ShotService extends Service {

    public static final String ACTION_START = "com.dsh.xiangqi.START_SCAN";
    public static final String ACTION_STOP = "com.dsh.xiangqi.STOP_SCAN";
    /**
     * 抓屏方式开关。false（默认）= 投屏（MediaProjection）；true = 无障碍截屏（API 30+）。
     * ★ 默认必须保持 false：无障碍截屏会被系统限流，投屏那条路是验证过的。
     * 想切过去要显式传 `--ez accshot true`（或走 UI 的长按入口），保证"没验证过的新路"不影响默认行为。
     */
    public static final String EXTRA_ACC_SHOT = "accshot";
    private boolean useAccCapture = false;
    private static final int NOTI_ID = 0x5A17;
    private static final long SCAN_INTERVAL_MS = 500;
    /** 连续这么多帧都检不到棋盘，才把小棋盘清空（中途闪一两帧是常事，不清不闪）。 */
    private static final int MISS_MAX = 3;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private WindowManager wm;
    private LinearLayout panel;
    private BoardView miniBoard;
    // ★ 2026-09-21 大改：悬浮窗从"走子助手"砍成"局势分析器"。
    //   面板上只剩：小棋盘（顺带当拖动把手）+ 分析条 + 一行状态 + 「轮到:红/黑」+「✕」。
    //   删掉的功能：自动走子、立即识别、暂停、提示开/关（识别本来就是自动的）。
    private EvalBar evalBar;          // 分析条：红蓝分色 + 分数
    private TextView statusText;      // 状态一行（识别了几子 / 引擎到几层）
    /** 上一帧"含遮挡回填"的格面 + 它对应的网格，专供被面板挡住的格子沿用。 */
    private int[] occCells;
    private float[] occGrid;
    private Button sideBtn;
    private Button relocateBtn;
    private WindowManager.LayoutParams panelParams;
    private int screenW, screenH, density, panelSize;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * `Handler` 不是 `Executor`（它只有 post()），而 `takeScreenshot()` 要的是 Executor，
     * 所以包一层。回调里干的只是"另起线程跑识别 + 放 analyzing 标志"，不吃线程，包一层没有额外代价。
     */
    private final java.util.concurrent.Executor accExec = new java.util.concurrent.Executor() {
        public void execute(Runnable r) { handler.post(r); }
    };
    private Engine engine;
    private Board lastBoard;
    private volatile boolean analyzing = false;      // 抓屏那一趟在跑（**不是**引擎状态，别混）

    // ==================== 状态机（2026-09-27 重写）====================
    //  识别帧可以很多，但"正式局面"必须谨慎；引擎只跟随正式局面。
    //   [1] 定位  首次 / 点「重新识别」→ 整屏找框；平时沿用 DetOcr 的黏性框
    //   [2] 候选  每帧 YOLO+ORT 的产出，只进候选状态，**不碰引擎、不碰画面**
    //   [3] 提交  多帧投票；与正式局面不同时优先验"一步合法着法"，确实新局才提交
    //   [4] 分析  每个已提交局面只跑一轮 go depth N；**相同局面不重跑**
    //   [5] 显示  分数/箭头跟着局面走，同一局面刷新不清
    //   [6] 故障  只有进程/管道真异常才退避重启；**正常限深结束不算故障**
    // ================================================================

    /** [2] 候选局面（屏幕原样）：连续相同的帧数 + 能否由上局面一步合法着法得到。 */
    private int[] candCells = null;
    private int candStable = 0;
    private boolean candViaLegalMove = false;

    /** [3] 正式局面（屏幕原样）——它一变，上一局面的分析与箭头就得作废。 */
    private int[] posCells = null;
    private int posPieces = 0;
    private String posReason = "";

    /** [3] 提交基准（引擎口径，红在下）：严格匹配"下一步合法着法"用。 */
    private int[] committedEngineCells = null;
    private int committedEngineSide = Board.RED;
    /** 仅首次识别、用户重定位或换盘时允许建立新基准。 */
    private boolean allowPositionReset = true;

    /** [4] 分析状态：IDLE 可开新一轮，RUNNING 有分析在跑，DONE 本局面已完成。 */
    private enum Ana { IDLE, RUNNING, DONE }
    private volatile Ana ana = Ana.IDLE;
    /** [4] 当前正式局面的 FEN（引擎口径）+ 它是否已跑完一轮分析（跑完就不重跑）。 */
    private String posFen = null;
    private boolean posAnalyzed = false;
    /** 分析代数：局面一变就 `++`，作废上一局面还在回吐的 info 回调。 */
    private volatile int analyzeGen = 0;
    /** 已向 UCI 发过 stop，等当前线程收尾期间不重复发。 */
    private volatile boolean stopRequested = false;
    private long anaStartAt = 0;
    /** [5] 本局面已达层数 / 分数 / 箭头 —— 绑局面，刷新不清。 */
    private volatile int anaDepth = 0;
    private int anaScoreRed = 0;
    private ArrayList<BoardView.Arrow> anaArrows = null;

    /** [6] 只有**真故障**才用：退避到此刻 + 连续故障次数（正常限深结束永远不碰它）。 */
    private long retryAfterAt = 0;
    private int failCount = 0;

    /**
     * ★★★ 2026-09-27 加：上一帧**因为不合法被拒掉**的那个局面（人话描述）。
     *   非法局面在棋盘被修好之前会**每帧**重复出现，而 `commitIfNewPosition` 只跟「正式局面」
     *   比（`posCells` 没变 ⇒ 每帧都够稳、每帧都来调 `commitPosition`）——
     *   不记住就会 500ms 刷一条日志、把面板刷成乱码。只在"换了一种不合法"时才提示。
     */
    private String lastIllegalWhy = null;

    private boolean engineReady = false;
    /** 每次开启悬浮窗读取当前配置；搜索期间保持本轮参数不变。 */
    private volatile int floatAnalysisDepth = 16;
    private volatile long floatAnalysisMs = 0;
    private int sideToMove = Board.RED;
    private final Board board = new Board();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Diag.init(getApplicationContext());
        Diag.log("Shot", "onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }

        // 抓屏方式：显式传了 accshot=true 才走无障碍截屏，否则一律走投屏（默认行为不变）
        if (intent != null && intent.hasExtra(EXTRA_ACC_SHOT)) {
            useAccCapture = intent.getBooleanExtra(EXTRA_ACC_SHOT, false);
        }
        android.content.SharedPreferences cfg = getSharedPreferences("xiangqi_cfg", MODE_PRIVATE);
        floatAnalysisDepth = Math.max(0, Math.min(60, cfg.getInt("floatDepth", 16)));
        floatAnalysisMs = Math.max(0, Math.min(60000L, cfg.getLong("floatMs", 0)));
        Diag.log("Shot", "抓屏方式=" + (useAccCapture ? "无障碍截屏(API30+)" : "投屏MediaProjection")
                + " accReady=" + AutoService.accShotReady()
                + " floatDepth=" + floatAnalysisDepth + " floatMs=" + floatAnalysisMs);
        // ★ 这里**不要**因为"暂时没连上"就永久退回投屏：
        //   AutoService 的实例是在 onServiceConnected 里拿到的，App 刚起来时可能还没绑定好
        //   （而且 force-stop 会让系统把该 App 的无障碍服务从启用列表里摘掉、要用户重新开）。
        //   所以只提示 + 记日志，真正的等待交给 doScan：连上之前那一帧直接跳过，连上就自动开始工作。
        if (useAccCapture && !AutoService.accShotReady()) {
            toast("无障碍服务还没连上：请确认「设置 → 无障碍 → 中国象棋AI」是开着的");
            Diag.log("Shot", "acc capture 请求了但服务未就绪 -> 先空转等它绑定，不退回投屏");
        }

        if (!useAccCapture && projection == null && intent != null && intent.getExtras() != null) {
            Intent data = (Intent) intent.getExtras().get("data");
            int code = intent.getIntExtra("code", 0);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            // 阶段一：直接取投影（Android 13 及以下、部分 ROM 走这条）
            try {
                projection = mpm.getMediaProjection(code, data);
                Diag.log("Shot", "phase1 getMediaProjection=" + (projection != null));
            } catch (Throwable t) {
                Diag.log("Shot", "phase1 failed: " + t.getMessage());
            }

            // 阶段二：先起前台服务再取投影（Android 14+ 常见要求）
            if (projection == null) {
                try {
                    startAsForeground();
                    Diag.log("Shot", "phase2 startAsForeground ok");
                } catch (Throwable t) {
                    Diag.log("Shot", "phase2 startAsForeground failed: " + t.getMessage());
                }
                try {
                    projection = mpm.getMediaProjection(code, data);
                    Diag.log("Shot", "phase2 getMediaProjection=" + (projection != null));
                } catch (Throwable t) {
                    Diag.log("Shot", "phase2 failed: " + t.getMessage());
                }
            }

            if (projection == null) {
                Diag.log("Shot", "projection null -> abort");
                toast("屏幕捕获初始化失败：系统拒绝了录屏授权，请重启 App 再试一次");
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { Diag.log("Shot", "projection stopped"); }
                }, handler);
            } catch (Throwable t) {
                Diag.log("Shot", "registerCallback failed: " + t);
            }
        }

        // 确保前台服务已启动（阶段一成功时还没起）
        try { startAsForeground(); Diag.log("Shot", "foreground ok"); }
        catch (Throwable t) { Diag.log("Shot", "foreground failed: " + t.getMessage()); }

        // 建虚拟显示：若报"需要前台服务"，说明服务还没被系统认可，等一下重试一次
        if (projection != null && display == null) {
            try {
                setupCapture();
                Diag.log("Shot", "virtualDisplay ok");
            } catch (Throwable t) {
                Diag.log("Shot", "setupCapture failed: " + t.getMessage() + " -> retry");
                handler.postDelayed(new Runnable() {
                    public void run() {
                        try { startAsForeground(); } catch (Throwable ignored) { }
                        try { setupCapture(); Diag.log("Shot", "virtualDisplay ok on retry"); }
                        catch (Throwable t2) { Diag.log("Shot", "setupCapture retry failed: " + t2.getMessage()); }
                    }
                }, 800);
            }
        }

        if (panel == null) buildPanel();
        if (engine == null) warmUp();
        loopAlive = true;                    // ★ 服务实例可能被复用（onStartCommand 会多次进来）→ 重新开门
        handler.removeCallbacks(scanLoop);
        handler.postDelayed(scanLoop, 1500);
        return START_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("scan", "实时识谱", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "scan") : new Notification.Builder(this);
        b.setContentTitle("实时识谱已开启")
         .setContentText("小棋盘悬浮窗 · 自动识谱出招")
         .setSmallIcon(android.R.drawable.ic_menu_search)
         .setOngoing(true);
        Notification n = b.build();
        // 前台服务类型必须和"实际拿到的抓屏能力"一致，否则 Android 14 会丢 SecurityException：
        //   投屏路：声明了 mediaProjection 且确实有 MediaProjection → 用 MEDIA_PROJECTION
        //   无障碍路：拿不到投影 token，若还用 mediaProjection 类型会被判"声明与事实不符"
        //              → 用 SPECIAL_USE（清单里已同时声明这两种类型 + 对应权限）
        if (Build.VERSION.SDK_INT >= 34 && useAccCapture) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private void setupCapture() {
        WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        w.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels; screenH = dm.heightPixels; density = dm.densityDpi;
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("xiangqi-live", screenW, screenH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
    }

    // ---------- 悬浮面板 ----------

    private long hiddenAt = 0;
    /** 最近一次成功识别到棋盘的时间（用于判断提示箭头是否还有效）。 */

    private volatile boolean pendingRescan = false;
    private int missCount = 0;
    /** 本会话是否已经自动避让过（只做一次，避免面板跳来跳去）。 */
    private boolean autoPlaced = false;
    /** 用户手动拖过面板之后，就再也不自动挪它 —— 手动摆放永远优先。 */
    private boolean userMoved = false;
    private final java.util.List<int[]> frameHistory = new java.util.ArrayList<int[]>();

    /** 矩形交叠面积。 */
    private static double overlapArea(int x0, int y0, int w, int h,
                                      float bx0, float by0, float bx1, float by1) {
        double ix = Math.min(x0 + w, bx1) - Math.max(x0, bx0);
        double iy = Math.min(y0 + h, by1) - Math.max(y0, by0);
        return (ix > 0 && iy > 0) ? ix * iy : 0;
    }

    /**
     * 面板压着棋盘就把它挪开。**这是"悬浮窗很差"的主因，不是检测器的问题。**
     *
     * 面板默认在左上角 dp(8),dp(200)，本机 520dpi 下就是 (26,650)、尺寸 481x500。
     * 实测（真机 1200x2670 整屏、棋盘区 y 189~1215）：默认位置**正压在棋盘正中间**，
     * 盖掉棋盘 24.5% 的面积，单帧就吃掉 15 个子里的 3 个。
     *
     * 被面板压住的格子只能靠上一帧的结果回填（`ScanFuse.restoreOccluded`），
     * 所以"本来就在盖住区里的子"还能撑住，**但棋子一动进盖住区就再也追不到** ——
     * 表现就是"悬浮窗里的局面总是缺子/停在几手之前"。
     *
     * 老版本是靠"抓屏瞬间把面板透明掉"，准但会闪；后来为了不闪改成"保留面板 + 识别时
     * 排除该矩形"，准确度就让给了观感。这里的做法是两头都要：**把面板挪到棋盘外的空白处**
     * （棋盘下方一般是聊天区，很空），既不闪，也不再压住任何格子。
     *
     * 只在第一次成功认出棋盘时做一次；用户手动拖过就永不自动挪。
     * @return true 表示"这一帧因为刚挪开面板而作废"，调用方应丢掉本帧等下一帧。
     */
    private boolean autoAvoidBoard() {
        if (autoPlaced || userMoved || panel == null || panelParams == null) return false;
        if (screenW <= 0 || screenH <= 0) return false;
        float[] c = Ocr.lastCorners;
        if (c == null || c.length != 8) return false;
        int pw = panel.getWidth(), ph = panel.getHeight();
        if (pw <= 0 || ph <= 0) return false;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float x = c[i * 2], y = c[i * 2 + 1];
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
        }
        // 棋盘太小/太扁 → 多半是假棋盘，别拿它挪面板
        if (maxX - minX < screenW * 0.35f || maxY - minY < screenH * 0.20f) { autoPlaced = true; return false; }
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        double boardArea = (double) (maxX - minX) * (maxY - minY);
        double ov = overlapArea(loc[0], loc[1], pw, ph, minX, minY, maxX, maxY) / boardArea;
        if (ov < 0.06) { autoPlaced = true; return false; }      // 基本没压住，不用动
        int margin = Ui.dp(this, 8);
        int newY = (int) maxY + margin;
        if (newY + ph > screenH - margin) {                      // 下面放不下 → 试棋盘上方
            int above = (int) minY - margin - ph;
            if (above < margin) return false;                    // 上下都没地方：保持原样，下次再试
            newY = above;
        }
        // MATCH_PARENT 面板不能再给 x 偏移，否则右边按钮被裁出屏幕。
        int newX = 0;
        int oldX = loc[0], oldY = loc[1];
        panelParams.x = newX; panelParams.y = newY;
        try { wm.updateViewLayout(panel, panelParams); } catch (Throwable ignored) { }
        autoPlaced = true;
        Diag.log("Shot", "面板自动避让棋盘 " + oldX + "," + oldY + " -> " + newX + "," + newY
                + "（原重叠 " + Math.round(ov * 100) + "% 棋盘区）");
        return true;
    }

    /** 棋盘四角围出的中心点是否落在某个矩形里（用来判断"认到的其实是自己的小棋盘"）。 */
    private boolean insideRect(float[] corners, int[] r) {
        if (corners == null || corners.length != 8 || r == null) return false;
        double cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4.0;
        double cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4.0;
        return cx >= r[0] && cx <= r[2] && cy >= r[1] && cy <= r[3];
    }

    /** 面板是否压住了棋盘区域（压住才需要抓屏时隐藏）。 */
    private boolean panelOverlapsBoard() {
        try {
            float[] c = Ocr.lastCorners;
            if (c == null || c.length != 8 || panel == null) return true;   // 不确定就保守处理
            int[] loc = new int[2];
            panel.getLocationOnScreen(loc);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float x = c[i * 2], y = c[i * 2 + 1];
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
            }
            boolean overlap = !(loc[0] > maxX || loc[0] + panel.getWidth() < minX
                    || loc[1] > maxY || loc[1] + panel.getHeight() < minY);
            return overlap;
        } catch (Throwable t) { return true; }
    }

    /**
     * 悬浮窗面板。★ 2026-09-21 按用户要求**大改**：只用来"分析局势给分"。
     *   保留：小棋盘（识别结果，也是拖动把手）、分析条（给分）、一行状态、
     *        「轮到:红/黑」（告诉引擎该谁走）、「✕」关闭。
     *   删掉：自动走子、立即识别、暂停、提示开/关 —— 识别是**自动**的（scanLoop 每 500ms 一趟）。
     *   布局：[小棋盘 132dp] [ 分析条 22dp / 状态一行 / 轮到 + ✕ ]
     */
    private void buildPanel() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int pad = Ui.dp(this, 6);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackground(Ui.round(0xE6141A21, 6f, this, Ui.STROKE));
        panel.setPadding(pad, pad, pad, pad);

        panelSize = Ui.dp(this, 132);
        miniBoard = new BoardView(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(panelSize, Math.round(panelSize * 10f / 9f));
        panel.addView(miniBoard, bp);

        // 右列：分析条 + 状态 + 两个键
        // ★ 2026-09-23（用户要求）：原来是固定 108dp，所以分析条和按钮都只有那么窄一条。
        //   改成 weight=1 —— 占满"小棋盘右边"的全部剩余宽度，条和按钮跟着一起变宽。
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = Ui.dp(this, 6);
        panel.addView(col, cp);

        evalBar = new EvalBar(this);
        col.addView(evalBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22)));
        evalBar.setScore(0, 0);          // 先摆个"均势 0 分"，别开机一条空白

        statusText = new TextView(this);
        statusText.setTextColor(0xFFB9B2A6);
        statusText.setTextSize(11f);
        statusText.setSingleLine(true);
        statusText.setText("识别中…");
        statusText.setPadding(Ui.dp(this, 2), Ui.dp(this, 3), 0, 0);
        col.addView(statusText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sideBtn = smallBtn("轮到:红", new View.OnClickListener() {
            public void onClick(View v) {
                sideToMove = Board.opponent(sideToMove);
                ((Button) v).setText(sideToMove == Board.RED ? "轮到:红" : "轮到:黑");
                frameHistory.clear(); occCells = null;    // 换边重来，别让旧格面回填
                resetPositions("用户按「轮到」换边");        // 换走子方 = 换局面口径，旧分析作废
                Diag.log("Shot", "side->" + (sideToMove == Board.RED ? "red" : "black"));
                lastBoard = null;                          // 触发一次重算（分数立刻按新走子方给）
            }
        });
        relocateBtn = smallBtn("重新识别", new View.OnClickListener() {
            public void onClick(View v) {
                DetOcr.requestRelocate();
                frameHistory.clear();
                occCells = null; occGrid = null;
                resetPositions("用户点「重新识别」");
                autoPlaced = false;
                postInfo("正在重新定位棋盘…");
            }
        });
        Button closeBtn = smallBtn("✕", new View.OnClickListener() {
            public void onClick(View v) {
                Diag.log("Shot", "close tapped");
                // ★★ 2026-09-23 修：这里**必须**用异步摘面板。
                //   之前用了同步 removeView，等于在"正在处理这次点击"的视图树上动手，
                //   实测「点一下 ✕ 就卡死然后闪退」。post 会等本轮事件分发结束再执行。
                hidePanelAsync();
                stopSelf();
            }
        });

        // 底部按钮横排：轮到、重新识别、关闭放在同一行，避免悬浮窗继续变高。
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 4);
        col.addView(btnRow, blp);
        btnRow.addView(sideBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = Ui.dp(this, 4);
        btnRow.addView(relocateBtn, rp);
        LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f);
        xp.leftMargin = Ui.dp(this, 4);
        btnRow.addView(closeBtn, xp);

        // ★ 2026-09-23（用户要求）：面板**铺满一整行**（原来 WRAP_CONTENT 只那么窄一条）。
        //   ⚠️ 宽度改 MATCH_PARENT 后，左边的 x 偏移必须归零 —— 否则右边会被挤出屏幕裁掉。
        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = 0;
        panelParams.y = Ui.dp(this, 200);
        panelParams.alpha = 1f;

        // 小棋盘按住就能拖动整个面板（没有标题栏了）
        View.OnTouchListener drag = new View.OnTouchListener() {
            float dx, dy; int sx, sy; boolean moving;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY();
                        sx = panelParams.x; sy = panelParams.y; moving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moving && Math.abs(e.getRawX() - dx) + Math.abs(e.getRawY() - dy) < 12) return true;
                        moving = true;
                        // ★ 2026-09-23：窗口宽度是 MATCH_PARENT（铺满整行），**横向不能再拖** ——
                        //   一旦 x 非 0，右半截就超出屏幕被裁掉，最右边的 ✕ 和分数条首当其冲。
                        //   所以 x 恒为 0，只跟随纵向。
                        panelParams.x = 0;
                        panelParams.y = sy + (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(panel, panelParams); } catch (Throwable ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moving) {
                            userMoved = true;      // 手动摆过就永不自动挪
                            Diag.log("Shot", "panel moved to " + panelParams.x + "," + panelParams.y);
                        }
                        return true;
                }
                return false;
            }
        };
        miniBoard.setOnTouchListener(drag);
        try { wm.addView(panel, panelParams); Diag.log("Shot", "panel added"); }
        catch (Throwable t) { Diag.log("Shot", "panel add failed: " + t); toast("添加悬浮窗失败：" + t); }
    }

    private LinearLayout.LayoutParams fullLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 3);
        return lp;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(this, 3);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    private Button smallBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setBackground(Ui.round(Ui.CARD2, 5f, this, Ui.STROKE));
        b.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setOnClickListener(l);
        return b;
    }

    // ---------- 引擎 ----------

    private void warmUp() {
        new Thread(new Runnable() {
            public void run() {
                DetOcr.init(getApplicationContext());      // YOLO26s 棋子检测器（主力，一次推理出整盘）
                OnnxOcr.init(getApplicationContext());
                OnnxPose.init(getApplicationContext());   // 角点模型（小窗一直漏用，导致定位差）
                OnnxRec.init(getApplicationContext());    // PP-OCR 读字模型（跨画风）
                engine = new Engine(ShotService.this);
                // 悬浮窗持续分析要在主 Activity 退后台后继续；不要继承前台分析页
                // 设置的 16 线程/大 Hash，降低系统因内存/CPU压力杀子进程的概率。
                engine.setThreads(2);
                engine.setHash(32);
                final boolean ok = engine.start();
                handler.post(new Runnable() { public void run() {
                    engineReady = ok;
                    if (!ok) postInfo("引擎启动失败，仅显示识别结果");
                } });
            }
        }).start();
    }

    // ---------- 抓屏识别循环 ----------

    /**
     * 服务是否还活着 —— onDestroy 一置 false，**任何**自续请求都不再受理。
     *
     * ★★ 2026-09-27 修 scanLoop 泄漏（实测：关窗后心跳还继续打了约 30 秒）：
     *   scanLoop 的自续点原本散在 **6 处** —— 本循环体内、catch 里、doScan 拉起的识图
     *   线程 finally 两处、无障碍回调 finally、无障碍请求发送失败分支。
     *   而 onDestroy 只做 `handler.removeCallbacks(scanLoop)`，那只掐得掉"**还没跑**"的
     *   排队任务；**正在执行中的那一轮**、以及它拉起的识图线程稍后 post 回来的，
     *   照样把循环续下去 —— 于是面板都摘了、投影都停了，心跳还在空转。
     *   现在收敛成**一个出口** {@link #scheduleScan}：服务不在了就丢弃，没有漏网的可能。
     */
    private volatile boolean loopAlive = true;

    /** scanLoop 唯一的自续出口。delay<=0 立即跑，>0 延迟跑；服务已销毁则直接丢弃。 */
    private void scheduleScan(long delay) {
        if (!loopAlive) return;
        if (delay <= 0) handler.post(scanLoop);
        else handler.postDelayed(scanLoop, delay);
    }

    private final Runnable scanLoop = new Runnable() {
        public void run() {
            if (!loopAlive) return;      // 双保险：万一还有更早排进来的任务，跑到这里也停
            try {
                // 看门狗：抓屏时会临时把面板透明化，万一恢复失败这里强制恢复，避免"小窗消失又点不到"
                if (panelParams != null && panelParams.alpha < 1f && hiddenAt > 0 && System.currentTimeMillis() - hiddenAt > 3000) {
                    Diag.log("Shot", "watchdog restore panel alpha");
                    setPanelAlpha(1f);
                }
                // ★ 2026-09-23 诊断：每 ~5 秒一条心跳（含内存占用）。
                //   用户报"偶尔面板卡死、什么点不了、然后自己没了" —— 这是典型的 UI 线程被堵死
                //   → ANR/内存吃紧 → 系统回收 Service 的表现。UI 一卡，这行日志立刻断，
                //   日志断点的时间戳就是事发时刻，同时能看出当时内存涨到什么程度。
                loopTick++;
                if (loopTick % 10 == 0) {
                    Runtime rt = Runtime.getRuntime();
                    Diag.log("Shot", "心跳 #" + loopTick
                            + " 已用=" + ((rt.totalMemory() - rt.freeMemory()) / 1048576) + "MB"
                            + " 上限=" + (rt.maxMemory() / 1048576) + "MB"
                            + " analyzing=" + analyzing + " gen=" + analyzeGen);
                }
                // 串行、完成驱动：开始一轮后不再按固定500ms轮询；识图线程结束时
                // 立即唤醒下一轮。这样间隔≈单帧实际耗时，不会在识图中空等或堆积检查。
                if (!doScan()) scheduleScan(100); // 截图源暂不可用时轻量退避，避免空转
            } catch (Throwable t) {
                Diag.log("Shot", "scanLoop error: " + t);
                scheduleScan(100);
            }
        }
    };

    /** 心跳计数（见 scanLoop 里的诊断日志）。 */
    private int loopTick = 0;

    /** 抓屏识别放到后台线程（无障碍路是回调式，见 scanOnceAcc）。 */
    private boolean doScan() {
        if (analyzing) return true;
        if (useAccCapture) {
            if (!AutoService.accShotReady()) return false;
            analyzing = true;
            scanOnceAcc();                 // analyzing 由回调那边放开，别在这里放
            return true;
        }
        if (reader == null) return false;
        analyzing = true;
        new Thread(new Runnable() {
            public void run() {
                boolean frameReady = false;
                try { frameReady = scanOnce(); } catch (Throwable t) { postInfo("识别异常：" + t.getMessage()); }
                finally {
                    analyzing = false;
                    scheduleScan(frameReady ? 0 : 100);   // ImageReader 暂无新帧时避免空转
                }
            }
        }).start();
        return true;
    }

    /** 【抓屏路 A】投屏：从 ImageReader 取一帧，转成位图后交给 processFrame。 */
    private boolean scanOnce() {
        final long tCapture = System.currentTimeMillis();
        boolean gotFrame = false;
        Image img = null;
        Bitmap bmp = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return false;   // 画面没变化时暂退避，再试
            gotFrame = true;
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane[] planes = img.getPlanes();
            ByteBuffer buf = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * w;
            bmp = Bitmap.createBitmap(w + (pixelStride > 0 ? rowPadding / pixelStride : 0), h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            img.close();
            img = null;
            if (rowPadding != 0) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
                bmp.recycle();
                bmp = cropped;
            }
            Diag.log("Shot", "截图转Bitmap耗时=" + (System.currentTimeMillis() - tCapture)
                    + "ms size=" + w + "x" + h);
            processFrame(bmp);          // 同步消费（内部只用它识别，不会留到异步线程去）
        } catch (Throwable t) {
            Diag.log("Shot", "scanOnce(投屏) error: " + t);
        } finally {
            // ★★★ 2026-09-25 修 Bug4（用户报"悬浮窗跑久了识图就不工作"）：
            //   原来 img.close() 是**裸写在中间**的 —— 上面任何一步抛异常（Bitmap 分配失败最典型：
            //   全屏 ARGB 一帧 ~13MB，还要每 500ms 来一张），close() 就被跳过。
            //   而 ImageReader 队列只有 2 格，漏掉两帧之后 acquireLatestImage() 会**永远返回 null**，
            //   抓屏就此彻底哑火 —— 这正是"跑一段时间就不识图"的根因。
            //   现在把关闭挪进 finally，保证任何路径都会把帧还回去；位图也顺手回收，减轻 GC 压力。
            if (img != null) { try { img.close(); } catch (Throwable ignored) { } }
            if (bmp != null) { try { bmp.recycle(); } catch (Throwable ignored) { } }
        }
        return gotFrame;
    }

    /**
     * 【抓屏路 B】无障碍截屏：不用投屏授权弹窗、没有"屏幕共享中"常驻提示。
     * 它是**回调式**的（结果异步回来），所以这里不能像投屏那样同步 return 一帧，
     * 只能把"放下 analyzing 标志"也挪到回调里 —— 否则主循环以为这一帧已经跑完，
     * 下一帧（500ms 后）就会和这一帧叠着跑。
     * 失败（最常见的 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT=3 限流）当一次普通 miss 处理。
     */
    private void scanOnceAcc() {
        final long t0 = System.currentTimeMillis();
        boolean sent = AutoService.takeShot(accExec, new AccShotCb() {
            public void onShot(final Bitmap bmp, final String err) {
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            if (bmp == null) {
                                Diag.log("Shot", "无障碍截屏失败: " + err + " (" + (System.currentTimeMillis() - t0) + "ms)");
                            } else {
                                Diag.log("Shot", "无障碍截屏 ok " + bmp.getWidth() + "x" + bmp.getHeight()
                                        + " 耗时" + (System.currentTimeMillis() - t0) + "ms");
                                processFrame(bmp);
                            }
                        } catch (Throwable t) {
                            Diag.log("Shot", "scanOnceAcc error: " + t);
                        } finally {
                            analyzing = false;
                            scheduleScan(0);
                        }
                    }
                }).start();
            }
        });
        if (!sent) { analyzing = false; scheduleScan(100); }       // 请求压根没发出去则短暂退避重试
    }

    /**
     * 一帧位图进来之后的**全部**处理：排除面板矩形 → 检测 → 建盘 → 遮挡回填 → 多帧投票 → 上屏。
     * ★ 两条抓屏路都汇到这里，**换抓屏方式不许改变识别行为** —— 改这块等于同时改两条路。
     */
    private void processFrame(final Bitmap bmp) {
        long tFrame = System.currentTimeMillis();
        try {
            // 面板不再隐藏，所以要把它的区域排除掉，否则会把小棋盘自己认成棋盘
            int[] exclude = null;
            if (panel != null) {
                int[] loc = new int[2];
                panel.getLocationOnScreen(loc);
                exclude = new int[] { loc[0] - 4, loc[1] - 4, loc[0] + panel.getWidth() + 4, loc[1] + panel.getHeight() + 4 };
            }

            // ========== ① 定位 + 识别 ==========
            // 棋盘框平时**沿用** DetOcr 的黏性框；只有首次和用户点「重新识别」时才整屏重找
            // （DetOcr.forceRelocate + stickyFrame 已在底层实现，这里无需干预）。
            // 悬浮窗每 0.5 秒扫一帧，这里**绝不能**退回"猜亮区 → 读字 → 爬山"的老链路：
            // 那条一次要跑几十遍推理（2~3 秒），放进来整个悬浮窗就卡死了（上一帧还没算完下一帧又到）。
            long tDetect = System.currentTimeMillis();
            int[] cells = Ocr.recognizeDetOnly(bmp, exclude);
            Diag.log("Shot", "识图检测阶段耗时=" + (System.currentTimeMillis() - tDetect) + "ms");
            if (cells == null) { onNoBoard(); return; }
            missCount = 0;

            // ========== ② 清洗 ==========
            // 双保险：万一排除没干净（面板贴边、位置读歪），把"整块都在面板里"的识别结果丢掉
            // —— 那只可能是我们自己的小棋盘。
            if (exclude != null && insideRect(Ocr.lastCorners, exclude)) {
                Diag.log("Shot", "detected board sits inside own panel -> ignore");
                return;
            }
            // 面板压着棋盘就挪开（只做一次），本帧作废 —— 下一帧抓到的是没有遮挡的画面
            if (autoAvoidBoard()) return;
            // 换了棋盘（切到另一副棋/另一次对局）就把历史清掉：
            // 不然新棋盘的头几帧会被旧棋盘的投票拖着走，还会被旧格面回填成幽灵子。
            if (occGrid != null && !Ocr.nearGrid(occGrid, Ocr.lastCorners)) {
                frameHistory.clear();
                occCells = null;
                recBoard = null;    // ★ 换了棋盘 → 旧盘不能当记谱基准（否则这一步永远换不了边）
                resetPositions("换盘（棋盘几何变了）");
                autoPlaced = false;      // 换了棋盘 → 允许再避让一次（棋盘位置可能也变了）
            }
            // 面板压住的那几格沿用上一帧（只回填真正被压住的格子，绝不能全盘回填）
            cells = ScanFuse.restoreOccluded(cells, occCells, Ocr.lastCorners, exclude);
            if (cells == null) return;
            occCells = cells.clone();
            occGrid = Ocr.lastCorners == null ? null : Ocr.lastCorners.clone();

            // ========== ③ 候选（只更新候选状态，不碰引擎、不碰画面） ==========
            int[] voted = ScanFuse.vote(frameHistory, cells);
            updateCandidate(voted);

            // ========== ④ 提交（够稳才升格为"正式局面"） ==========
            if (!commitIfNewPosition(voted)) return;

            // ========== ⑤ 分析（正式局面没分析过才开一轮） ==========
            ensureAnalysis();
            Diag.log("Shot", "识图整帧耗时=" + (System.currentTimeMillis() - tFrame) + "ms");
        } catch (Throwable t) {
            handler.post(new Runnable() { public void run() { postInfo("识别异常：" + t.getMessage()); } });
        }
    }

    /**
     * 连续多帧找不到棋盘就清空一切（用户切走了 / 被挡住了）。
     * ★ 是"清空"不是"拿旧结果复活"——否则切回来看到的是上一局的残影。
     */
    private void onNoBoard() {
        missCount++;
        Diag.log("Shot", "no board in frame (#" + missCount + ")");
        if (missCount < MISS_MAX) return;
        frameHistory.clear();
        occCells = null;
        occGrid = null;
        Ocr.hintCorners = null;
        recBoard = null;        // ★ 必须清：否则切回来时拿上一局当基准，记谱永远对不上 → 不换边
        resetPositions("连续 " + missCount + " 帧找不到棋盘");
        board.clear();
        for (int i = 0; i < 90; i++) board.cells[i] = 0;
        board.kingSq[0] = -1;
        board.kingSq[1] = -1;
        postBoard();
    }

    /**
     * 清空「候选 + 正式局面 + 分析」三件套（换盘 / 丢盘时用）。
     * ★ 先把 analyzeGen 作废，再改状态 —— 否则正在收尾的那趟分析的 finally 会回来
     *   覆盖刚清好的状态（它就是 09-27 那个"引擎重试中"误报的同一个坑）。
     */
    private void resetPositions(String why) {
        ++analyzeGen;                       // 先作废旧回调，再动状态
        candCells = null;
        candStable = 0;
        candViaLegalMove = false;
        posCells = null;
        posPieces = 0;
        posReason = "";
        posFen = null;
        posAnalyzed = false;
        committedEngineCells = null;
        anaDepth = 0;
        anaScoreRed = 0;
        anaArrows = null;
        retryAfterAt = 0;
        failCount = 0;
        lastIllegalWhy = null;          // 局面已整体清空（换盘/重定位/"没棋盘了"）→ 提示记忆也清掉
        if (ana == Ana.RUNNING && engine != null) engine.stopAnalysis();
        ana = Ana.IDLE;
        Diag.log("Commit", "局面状态清空：" + why);
    }

    /**
     * ③ 候选：多帧投票的结果**先只当候选**。
     *   它绝不直接改画面、也不碰引擎 —— 这是"识别帧可以很多、正式局面要谨慎"的落点。
     *   candStable = 连续多少帧投出同一个格面；candViaLegalMove = 能否由正式局面一步走到。
     *   日志只在候选**变化**时打（否则每 500ms 一条会把日志刷爆）。
     */
    private void updateCandidate(int[] voted) {
        boolean same = (candCells != null && sameCells(candCells, voted));
        if (same) candStable++;
        else { candCells = voted.clone(); candStable = 1; }
        boolean differs = (posCells == null) || !sameCells(posCells, voted);
        candViaLegalMove = differs && matchesNextLegalMove(voted);
        if (!same) {
            Diag.log("Cand", "候选 pieces=" + countPieces(voted)
                    + " · " + (posCells == null ? "还没有正式局面" : (differs ? "与正式局面不同" : "与正式局面相同"))
                    + (candViaLegalMove ? " · 一步合法着法可达" : ""));
        }
    }

    /**
     * ④ 提交：够稳才把候选升格为「正式局面」——引擎唯一认的东西。
     *   判据（用户定的流程）：
     *     · 与正式局面相同    → 不提交，什么都不做（画面与箭头都不动，刷新不清）
     *     · 不同，但能由正式局面**一步合法着法**走到 → 连续 2 帧就提交（正常走子，要快）
     *     · 不同，且对不上任何合法着法 → 连续 4 帧才提交（疑似识别抖动，门槛加高）
     *   达不到门槛就**保留旧局面**：宁可慢一拍，也不让误识别去驱动引擎。
     */
    private boolean commitIfNewPosition(int[] voted) {
        if (posCells != null && sameCells(posCells, voted)) return false;
        // ★★ 门槛必须与改动前的原版对齐（09-27 曾误抬到"非合法就 4 帧"，把自动换边弄坏了）：
        //   · 还没有正式局面（首次建盘）    → 2 帧
        //   · 有正式局面 + 能一步合法着法走到（正常走子）→ 2 帧
        //   · 有正式局面 + 对不上任何合法着法（疑似误识别）→ 4 帧
        //   原版是 `changedFromCommitted && !legalNextMove ? 4 : 2`，
        //   而首次时 changedFromCommitted=false ⇒ 也是 2 帧。**这里差一帧都不行**：
        //   识别本来就抖，门槛一高就永远凑不齐连续帧 → 局面提交不了 → 不记谱 → 不换边。
        int need = (posCells == null || candViaLegalMove) ? 2 : 4;
        if (candStable < need) return false;
        commitPosition(voted, candViaLegalMove ? "一步合法着法" : "多帧稳定确认");
        return true;
    }

    private int countPieces(int[] cells) {
        int n = 0;
        if (cells != null) for (int v : cells) if (v != 0) n++;
        return n;
    }

    private static boolean sameCells(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    /** 对候选当前棋盘与上次已确认盘做合法单步匹配，不用 OCR 猜测直接切换。 */
    private boolean matchesNextLegalMove(int[] candidate) {
        if (committedEngineCells == null || candidate == null || candidate.length != 90) return false;
        Board base = new Board();
        for (int i = 0; i < 90; i++) base.cells[i] = committedEngineCells[i];
        base.sideToMove = committedEngineSide;
        base.kingSq[0] = base.kingSq[1] = -1;
        for (int i = 0; i < 90; i++) {
            if (Board.type(base.cells[i]) == Board.KING)
                base.kingSq[Board.isRed(base.cells[i]) ? 0 : 1] = i;
        }
        if (base.kingSq[0] < 0 || base.kingSq[1] < 0) return false;
        // 目标和基准都用红在下（引擎口径）；不能依赖本帧方向标志改变坐标口径。
        int[] target = !DetOcr.lastRedBottom ? DetOcr.rotCells(candidate) : candidate;
        ArrayList<Integer> moves = base.generateLegalMoves(base.sideToMove);
        for (int i = 0; i < moves.size(); i++) {
            int mv = moves.get(i);
            int cap = base.makeMove(mv);
            boolean match = true;
            for (int sq = 0; sq < 90; sq++) {
                if (base.cells[sq] != target[sq]) { match = false; break; }
            }
            base.unmakeMove(mv, cap);
            if (match) return true;
        }
        return false;
    }

    /**
     * 比较相邻两帧的引擎口径棋盘：一方棋子从原格消失并在新格出现，另一方只可能在吃子时消失。
     * 返回发生移动的颜色；局面跨度太大、两边都像移动或没有新增棋子时返回 -1。
     */
    private int inferMovedSide(int[] before, int[] after) {
        if (before == null || after == null || before.length != 90 || after.length != 90) return -1;
        int changed = 0;
        int redAppear = 0, blackAppear = 0;
        for (int i = 0; i < 90; i++) {
            int a = before[i], b = after[i];
            if (a == b) continue;
            changed++;
            if (b > 0 && Board.isRed(b) && !Board.isRed(a)) redAppear++;
            if (b > 0 && Board.isBlack(b) && !Board.isBlack(a)) blackAppear++;
        }
        // 一着通常改 2 格，吃子仍是 2 格；容许一处识别抖动，但不跨多着猜颜色。
        if (changed < 2 || changed > 3) return -1;
        if (redAppear > blackAppear && redAppear == 1) return Board.RED;
        if (blackAppear > redAppear && blackAppear == 1) return Board.BLACK;
        return -1;
    }

    /**
     * ④ 提交落地：把这个候选升格成「正式局面」——**引擎唯一认的东西**。
     *   顺序：建盘（屏幕原样）→ 校验完整性 → 归一化朝向（引擎口径）→ 记谱/换边
     *         → 正式局面落库 + 作废上一局面的分析 → 上屏 + 打 [Commit] 日志。
     *   ★ 校验不过的帧只提示、**不提交** —— 残缺局面喂给皮卡鱼会让它直接退出。
     *   ★★★ 2026-09-27：两道闸门 —— ①完整性（子数/将数）②**合法性**（`Board.illegalReason`）。
     *     第②道是这次"老是引擎中断"的根治：子站在不可能的位置时，皮卡鱼会
     *     `CRITICAL ERROR` + `exit(1)`，而应用侧只看得到"管道断了"，
     *     于是报「引擎中断」→ 拿同一条非法 FEN 重试 → 死循环。
     */
    private void commitPosition(int[] voted, String reason) {
        // 0) ★★★ 2026-09-27 新增：喂引擎前的两道闸门。
        //    **必须在改动任何字段之前做** —— 这样被拒掉的帧不会在 board 上留下半个局面。
        //    先归一成"红在下"（引擎口径）再查，跟真正喂引擎的那份完全一致。
        final int[] engineCells = voted.clone();
        if (!DetOcr.lastRedBottom) {
            System.arraycopy(DetOcr.rotCells(engineCells), 0, engineCells, 0, 90);
        }
        int votedCount = 0, votedRedKing = 0, votedBlackKing = 0;
        for (int i = 0; i < 90; i++) {
            if (engineCells[i] <= 0) continue;
            votedCount++;
            if (Board.type(engineCells[i]) == Board.KING) {
                if (Board.isRed(engineCells[i])) votedRedKing++; else votedBlackKing++;
            }
        }
        // 0a) 完整性：子太少 / 将帅不全 → 等它稳定，先不提交
        if (votedCount < 10 || votedRedKing != 1 || votedBlackKing != 1) {
            Diag.log("Cand", "局面不完整（" + votedCount + " 子 · 帅" + votedRedKing + " 将" + votedBlackKing
                    + "）→ 不提交，保留旧局面");
            postInfo("识别到 " + votedCount + " 子（局面不完整，等待稳定）");
            return;
        }
        // 0b) ★★★ 合法性：有子站在它一辈子到不了的位置。
        //     皮卡鱼遇到这种局面**不吐 bestmove、不重试，直接 CRITICAL ERROR 然后 exit(1)**；
        //     应用侧只看得到"管道断了"→ 报「引擎中断」→ 拿同一条非法 FEN 重试 → 又崩 → 死循环。
        //     所以这里必须当识别噪声整帧丢掉：既不提交，也不喂引擎，保留上一个正式局面。
        String illegalWhy = Board.illegalReason(engineCells);
        if (illegalWhy != null) {
            // 去重：棋盘被修好之前这个非法局面会每帧重复出现，别 500ms 刷一条。
            //   只在"换了一种不合法"时才写日志、刷面板。
            if (!illegalWhy.equals(lastIllegalWhy)) {
                lastIllegalWhy = illegalWhy;
                String brief = illegalWhy;
                int cut = brief.indexOf('（');          // 面板一行放不下带坐标的长句，只留前半句
                if (cut > 0) brief = brief.substring(0, cut);
                Diag.log("Cand", "★ 局面不合法 → 不提交，保留旧局面：" + illegalWhy
                        + "（这类局面会让皮卡鱼 CRITICAL ERROR + exit(1)，用重试是救不回来的）");
                postInfo("识别 " + votedCount + " 子 · " + brief + "，已忽略");
            }
            return;
        }
        lastIllegalWhy = null;      // 这一帧合法了 → 清掉记忆，下次再出错还能正常提示

        // 1) 屏幕原样那份（画小棋盘用，对齐用户看到的画面）
        board.clear();
        int placed = 0;
        for (int i = 0; i < 90; i++) if (voted[i] > 0) { board.cells[i] = voted[i]; placed++; }
        board.kingSq[0] = -1;
        board.kingSq[1] = -1;
        int[] kingCount = new int[] { 0, 0 };
        for (int i = 0; i < 90; i++) {
            if (Board.type(board.cells[i]) == Board.KING) {
                int side = Board.isRed(board.cells[i]) ? 0 : 1;
                kingCount[side]++;
                board.kingSq[side] = i;
            }
        }
        board.sideToMove = sideToMove;
        // 2) 完整性校验
        if (placed < 10 || kingCount[0] != 1 || kingCount[1] != 1) {
            Diag.log("Cand", "局面不完整（" + placed + " 子 · 帅" + kingCount[0] + " 将" + kingCount[1]
                    + "）→ 不提交，保留旧局面");
            postInfo("识别到 " + placed + " 子（局面不完整，等待稳定）");
            return;
        }
        // 3) 归一化成"红在下"再喂引擎：
        //    用户执黑时天天象棋把棋盘转 180° 显示，直接送引擎等于给了个反过来的非法局面，
        //    皮卡鱼一句 info 都不吐（2026-09-21 实测）。小棋盘仍按屏幕原样显示。
        final Board snapshot = board.copy();
        if (!DetOcr.lastRedBottom) {
            System.arraycopy(DetOcr.rotCells(snapshot.cells), 0, snapshot.cells, 0, 90);
            for (int k = 0; k < 2; k++) if (snapshot.kingSq[k] >= 0) snapshot.kingSq[k] = 89 - snapshot.kingSq[k];
            Diag.log("Shot", "识别结果是红在上 -> 转 180° 再喂引擎");
        }
        snapshot.sideToMove = sideToMove;

        // 4) 记谱 + 自动换边。换边改为根据相邻两盘实际变化判断哪方走了子，
        // 不再把「recordGame 是否严格匹配出一步棋」当作换边开关。
        final int[] previousEngineCells = committedEngineCells;
        final int recBefore = gameMoves.size();
        try { recordGame(snapshot.cells); } catch (Throwable t) { Diag.log("Shot", "记谱异常: " + t); }
        int recCount = gameMoves.size();
        int movedSide = inferMovedSide(previousEngineCells, snapshot.cells);
        if (movedSide >= 0) {
            sideToMove = Board.opponent(movedSide);
            // 记谱器如未能续上（例如识别有一两格抖动），仍按棋子颜色更新轮到方；
            // 并以当前稳定局面重新设记谱基准，避免下一帧继续拿旧盘比较。
            if (recCount == recBefore) {
                recBoard = snapshot.copy();
                recBoard.sideToMove = sideToMove;
                gameStartFen = recBoard.toFen();
                gameMoves.clear();
                pushRecord();
                recCount = 0;
                Diag.log("Shot", "自动换边识别到" + (movedSide == Board.RED ? "红" : "黑")
                        + "方移动；记谱匹配未通过，已从当前局面重设基准");
            } else if (recBoard != null) {
                recBoard.sideToMove = sideToMove;
            }
            recSide = sideToMove;
            board.sideToMove = sideToMove;
            snapshot.sideToMove = sideToMove;
            final String sideLabel = sideToMove == Board.RED ? "轮到:红" : "轮到:黑";
            handler.post(new Runnable() { public void run() { if (sideBtn != null) sideBtn.setText(sideLabel); } });
            Diag.log("Commit", "检测到" + (movedSide == Board.RED ? "红" : "黑")
                    + "方走子 → 自动换边：" + sideLabel + "（记" + recCount + "手）");
        } else if (recCount > recBefore) {
            // 无法仅凭格面唯一判定移动方时，沿用记谱器确认的结果。
            sideToMove = Board.opponent(sideToMove);
            recSide = sideToMove;
            board.sideToMove = sideToMove;
            snapshot.sideToMove = sideToMove;
            final String sideLabel = sideToMove == Board.RED ? "轮到:红" : "轮到:黑";
            handler.post(new Runnable() { public void run() { if (sideBtn != null) sideBtn.setText(sideLabel); } });
            Diag.log("Commit", "记谱确认走子 → 自动换边：" + sideLabel + "（第 " + recCount + " 手）");
        }

        // 5) 正式局面落库；旧局面的分析整批作废（旧回调靠 analyzeGen 拦住）
        ++analyzeGen;
        posCells = voted.clone();
        posPieces = placed;
        posReason = reason;
        posFen = Engine.fenOf(snapshot);
        posAnalyzed = false;                       // 新局面还没跑过分析
        committedEngineCells = snapshot.cells.clone();
        committedEngineSide = snapshot.sideToMove;
        anaDepth = 0;
        anaScoreRed = 0;
        anaArrows = null;
        if (ana == Ana.RUNNING && engine != null) engine.stopAnalysis();
        ana = Ana.IDLE;
        retryAfterAt = 0;                          // 新局面重新开始，**不继承**上一局面的退避
        failCount = 0;

        lastBoard = board.copy();
        postBoard();                               // 新局面：不带箭头（旧箭头已作废）
        Diag.log("Commit", "正式局面 reason=" + reason + " pieces=" + placed
                + " 轮到=" + (sideToMove == Board.RED ? "红" : "黑")
                + " 记" + recCount + "手 fen=" + posFen);
    }

    /**
     * ⑤ 分析调度：正式局面**没分析过**才开一轮。
     *   ★ 跑完就不再跑 —— 限深搜索天生会结束，同一个局面重跑没有任何意义。
     *   ★ 正常限深结束**不进重试**；只有引擎进程退出 / 管道异常才算故障、才退避。
     */
    private void ensureAnalysis() {
        if (posCells == null) return;                 // 还没有正式局面
        if (!engineReady) { postInfo("识别 " + posPieces + " 子 · 引擎预热中…"); return; }
        if (ana == Ana.RUNNING) return;               // 已经有一趟在跑
        if (posAnalyzed) {                            // 本局面跑完了 → 只刷新状态行，不重跑
            postInfo("识别 " + posPieces + " 子 · d" + anaDepth + " · 记" + gameMoves.size() + "手");
            return;
        }
        long now = System.currentTimeMillis();
        if (now < retryAfterAt) {                     // 上一轮**真故障**留下的退避
            postInfo("识别 " + posPieces + " 子 · 引擎重试中(" + ((retryAfterAt - now + 999) / 1000) + "秒)");
            return;
        }
        startAnalysis();
    }

    /** 真正启动一轮分析（每个正式局面只调一次）。 */
    private void startAnalysis() {
        final int myGen = ++analyzeGen;
        final String fen = posFen;
        final int pieceCount = posPieces;
        final int recCount = gameMoves.size();
        final int analyzeDepth = floatAnalysisDepth;
        final long analyzeMs = floatAnalysisMs;       // 0 = 不限时（跑到限深为止）
        // 喂引擎那份从"已提交的引擎口径格面"重建（不依赖 board，board 是屏幕原样）
        final Board snapshot = new Board();
        for (int i = 0; i < 90; i++) snapshot.cells[i] = committedEngineCells[i];
        snapshot.sideToMove = committedEngineSide;
        snapshot.kingSq[0] = snapshot.kingSq[1] = -1;
        for (int i = 0; i < 90; i++) {
            if (Board.type(snapshot.cells[i]) == Board.KING)
                snapshot.kingSq[Board.isRed(snapshot.cells[i]) ? 0 : 1] = i;
        }
        final boolean rot = !DetOcr.lastRedBottom;     // 箭头方向（屏幕原样 vs 引擎口径）
        final boolean redTurn = (snapshot.sideToMove == Board.RED);

        ana = Ana.RUNNING;
        anaDepth = 0;
        anaScoreRed = 0;
        stopRequested = false;
        anaStartAt = System.currentTimeMillis();
        Diag.log("Ana", "启动 fen=" + fen + " depth=" + analyzeDepth
                + (analyzeMs > 0 ? (" movetime=" + analyzeMs) : " 不限时") + " pieces=" + pieceCount);
        if (engine != null) engine.stopAnalysis();     // 先打断（它会自己吐 bestmove 退出）
        new Thread(new Runnable() {
            public void run() {
                try {
                    engine.analyzeStream(snapshot, analyzeMs, 2, analyzeDepth, new Engine.StreamListener() {
                        public void onUpdate(final java.util.List<Engine.Line> lines, final int depth) {
                            if (myGen != analyzeGen || lines.isEmpty()) return;
                            anaDepth = depth;
                            final Engine.Line l = lines.get(0);
                            final ArrayList<BoardView.Arrow> arrows = arrowsForLines(lines, rot);
                            if (!arrows.isEmpty()) anaArrows = new ArrayList<BoardView.Arrow>(arrows);
                            final int cpRed = redTurn ? l.score : -l.score;
                            anaScoreRed = cpRed;
                            handler.post(new Runnable() { public void run() {
                                if (myGen != analyzeGen) return;
                                if (evalBar != null) evalBar.setScore(cpRed, depth);
                                // 箭头（同主界面「分析」页）：引擎按"红在下"算，小棋盘按屏幕原样显示
                                if (anaArrows != null && miniBoard != null)
                                    miniBoard.setArrows(new ArrayList<BoardView.Arrow>(anaArrows));
                                postInfo("识别 " + pieceCount + " 子 · d" + depth + " · 记" + recCount + "手");
                            } });
                        }
                    });
                } catch (Throwable t) {
                    Diag.log("Ana", "分析异常: " + t);
                } finally {
                    handler.post(new Runnable() { public void run() {
                        // ★★ 局面已变 / 服务已停 → 整段跳过。
                        //   这正是 09-27"引擎重试中"误报的根因所在：原来不判代次，
                        //   关窗时 engine 被置 null，这里就把"用户关窗"记成"引擎故障"。
                        if (myGen != analyzeGen) return;
                        ana = Ana.DONE;
                        stopRequested = false;
                        long used = System.currentTimeMillis() - anaStartAt;
                        String engineError = (engine == null) ? null : engine.getError();
                        boolean engineDead = (engine == null) || !engine.isStarted() || engineError != null;
                        if (engineDead) {
                            // 只有这种才叫故障：进程退出 / 管道关闭 / UCI 超时
                            failCount++;
                            long delay = Math.min(30000L, 2000L << Math.min(4, failCount - 1));
                            retryAfterAt = System.currentTimeMillis() + delay;
                            posAnalyzed = false;               // 没分析成功 → 还欠着，退避结束重来
                            Diag.log("Ana", "故障：引擎不可用 retryMs=" + delay + " depth=" + anaDepth
                                    + " err=" + engineError
                                    + " started=" + (engine != null && engine.isStarted()));
                            postInfo("识别 " + pieceCount + " 子 · 引擎中断，" + (delay / 1000) + "秒后重试");
                            return;
                        }
                        failCount = 0;
                        retryAfterAt = 0;
                        posAnalyzed = true;                    // ★ 正常结束（含无可分析着法）：到此为止，不重跑
                        if (anaDepth == 0) {
                            Diag.log("Ana", "正常结束但无 PV（可能将死/无合法着法）用时 " + used + "ms");
                            postInfo("识别 " + pieceCount + " 子 · 无可分析着法");
                        } else {
                            Diag.log("Ana", "正常结束 depth=" + anaDepth + " 用时 " + used + "ms");
                            postInfo("识别 " + pieceCount + " 子 · d" + anaDepth + " · 记" + recCount + "手");
                        }
                    } });
                }
            }
        }).start();
    }

    /**
     * 箭头配色 —— **刻意跟主界面分析页（MainActivity 里那三个 ARROW_C）用同一套**：
     *   蓝 = 最优招「1」 · 紫 = 第二优招「2」 · 橙 = 对方最可能应手「敌」。
     * 同一个 App 里同样的东西两处显示，颜色语义必须一致，否则用户得学两遍。
     */
    private static final int ARROW_C1 = 0xFF1565C0, ARROW_C2 = 0xFF7B1FA2, ARROW_C3 = 0xFFE64A19;

    /**
     * 把引擎这一轮的 MultiPV 结果画成箭头：**1 = 最优招 · 2 = 第二优招 · 敌 = 对方最优应手**。
     *
     * ★ 2026-09-27（用户要求"把第二优招也加进去"）：引擎本来就开着 **MultiPV=2**，
     *   每轮都回传两条变化，但这里原来只取了 `lines.get(0)` —— 第二优招一直在手里没显示。
     *   （主界面分析页早就是三箭头了，悬浮窗一直少画一支。）
     *
     * ★ 三条箭头的编号牌必须**挂在不同位置**：最优招和第二优招多半是同一个子走两个方向，
     *   起点完全重合；悬浮窗的小棋盘上箭头本来就短，三张白圆牌都画在正中会叠成一坨，
     *   数字互相盖住谁也看不清。所以 labelT 分别取 0.42 / 0.60 / 0.50。
     *
     * ★ 注意别把两件事搞混：「2」是**同一局面下的第二好着法**（第二条 PV 的头一步），
     *   「敌」是**最优招走完之后对手的最佳应手**（第一条 PV 的第二步）—— 两者完全不是一回事。
     */
    private static ArrayList<BoardView.Arrow> arrowsForLines(java.util.List<Engine.Line> lines, boolean rot) {
        ArrayList<BoardView.Arrow> arrows = new ArrayList<BoardView.Arrow>();
        if (lines == null || lines.isEmpty()) return arrows;
        Engine.Line l1 = lines.get(0);
        int mv1 = Engine.firstMoveOf(l1.pv);
        if (mv1 > 0) arrows.add(new BoardView.Arrow(dispMove(mv1, rot), ARROW_C1, "1", 0.42f));
        if (lines.size() >= 2) {                       // 第二优招：第二条 PV 的头一步
            int mv2 = Engine.firstMoveOf(lines.get(1).pv);
            if (mv2 > 0 && mv2 != mv1)
                arrows.add(new BoardView.Arrow(dispMove(mv2, rot), ARROW_C2, "2", 0.60f));
        }
        int mvOpp = secondMoveOf(l1.pv);               // 对方最优应手（最优招之后的那一步）
        if (mvOpp > 0) arrows.add(new BoardView.Arrow(dispMove(mvOpp, rot), ARROW_C3, "敌", 0.50f));
        return arrows;
    }

    // ---------- 后台自动记谱（2026-09-21 用户要求）----------
    // 悬浮窗只做 0.9 秒的实时搜索，棋力有限；所以它边识别边把**整局**记下来，
    // 回到主界面可以接着做深度分析（棋谱 tab / 局势折线图 / 全盘分析）。
    private final ArrayList<Integer> gameMoves = new ArrayList<Integer>();
    private String gameStartFen = null;
    /** 已经记录到哪一步（引擎口径：红在下）。每帧拿它和新识别到的局面比。 */
    private Board recBoard = null;
    /** 记谱时的走子方；用户按「轮到」换边就重开一局记录。 */
    private int recSide = -1;

    /**
     * 把"识别到的局面"翻译成"一步棋"追加进棋谱。
     * 判据（离线实测调出来的，见 `D:\dsh\tmp\rectest`）：在**上一局面**的合法着法里挑一步，
     * 走完之后跟新识别到的局面比 90 格，要求
     *   ① **至少 88 格一致**（留 2 格容错 —— 识别偶发错一两颗子不至于把棋谱写歪）
     *   ② **严格赢过第二名**（`best > 2nd`）—— 平手说明这一步有歧义，宁可不记。
     * 实测（200 局×30 步，随机注入错格）：只加 ① 时错 1 格有 0.6%、错 2 格有 1.6% 会**记错**；
     * 加上 ② 之后降到 **0% / 0.2%**，代价只是多 0.8~2% 的"这帧放弃"（下一帧再试，安全）。
     * 对不上就整帧丢掉、等下一帧（多帧投票在 `ScanFuse.vote` 那层已经压过抖动）。
     */
    private void recordGame(int[] nowCells) {
        int placed = 0;
        for (int i = 0; i < 90; i++) if (nowCells[i] != 0) placed++;
        if (placed < 2) return;
        Board cur = new Board();
        for (int i = 0; i < 90; i++) cur.cells[i] = nowCells[i];
        cur.sideToMove = sideToMove;
        cur.kingSq[0] = cur.kingSq[1] = -1;
        for (int i = 0; i < 90; i++)
            if (Board.type(cur.cells[i]) == Board.KING) cur.kingSq[Board.isRed(cur.cells[i]) ? 0 : 1] = i;

        // 第一次识别到棋盘 / 用户按了「轮到」换边 → 从现在这盘开始记
        if (recBoard == null || recSide != sideToMove) {
            recBoard = cur;
            gameStartFen = cur.toFen();
            gameMoves.clear();
            recSide = sideToMove;
            pushRecord();
            Diag.log("Shot", "记谱开始，起始局面 " + placed + " 子 · 轮到" + (sideToMove == Board.RED ? "红" : "黑"));
            return;
        }
        ArrayList<Integer> ms = recBoard.generateLegalMoves(recBoard.sideToMove);
        int bestMv = -1, bestAgree = -1, secondAgree = -1;
        for (int i = 0; i < ms.size(); i++) {
            int mv = ms.get(i);
            int cap = recBoard.makeMove(mv);
            int agree = 0;
            for (int k = 0; k < 90; k++) if (recBoard.cells[k] == cur.cells[k]) agree++;
            recBoard.unmakeMove(mv, cap);
            if (agree > bestAgree) { secondAgree = bestAgree; bestAgree = agree; bestMv = mv; }
            else if (agree > secondAgree) secondAgree = agree;
        }
        if (bestMv < 0 || bestAgree < 88 || bestAgree <= secondAgree) return;   // 没跟上 / 有歧义 → 这帧不记
        String nt = Notation.move(recBoard, bestMv);   // 记谱文字要在 makeMove 之前算
        recBoard.makeMove(bestMv);                     // recBoard 顺势推进到新局面
        gameMoves.add(bestMv);
        pushRecord();
        Diag.log("Shot", "记谱 +1 -> 第 " + gameMoves.size() + " 手 " + nt
                + "（吻合 " + bestAgree + "/90，次优 " + secondAgree + "）");
    }

    /** 把当前记录推给主界面（拷一份，别让它拿到正在被改的 list）。 */
    private void pushRecord() {
        int[] copy = new int[gameMoves.size()];
        for (int i = 0; i < copy.length; i++) copy[i] = gameMoves.get(i);
        Bridge.gameMoves = copy;
        Bridge.gameFen = gameStartFen;
        Bridge.gameSeq++;
    }

    /** 引擎按"红在下"给着法；小棋盘可能按屏幕原样显示（红在上），这时要把着法转 180° 回来。 */
    private static int dispMove(int mv, boolean rot) {
        if (!rot) return mv;
        return Board.makeMove(89 - Board.moveFrom(mv), 89 - Board.moveTo(mv));
    }

    /** 主变第 2 手 = 引擎眼里的对方应手。 */
    private static int secondMoveOf(String pv) {
        if (pv == null) return -1;
        String[] ms = pv.trim().split("\\s+");
        return ms.length >= 2 ? Engine.moveOf(ms[1]) : -1;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // ★★★ 2026-09-27 修「引擎重试中」误报（用户报"经常出现模型重试中"）：
        //   正在跑的 analyzeStream 线程回来时，finally 里会这么判：
        //     engineError  = engine == null ? "引擎未创建" : engine.getError();
        //     engineFailed = engine == null || engineError != null || !engine.isStarted();
        //   而紧接着下面几行恰好把 engine 置 null —— 于是"**用户主动关悬浮窗**"被
        //   误判成"引擎故障"：累加 failCount 并设置 retryAfterAt，
        //   退避 2s → 4s → 8s → 16s → 30s 翻倍。关得越多，下次开窗等得越久，
        //   表现就是"一开悬浮窗就显示引擎重试中(N秒)"。
        //   日志实证（2026-09-27 07:48:44，手机 ec09de34）——那一刻引擎是健康的：
        //     [Engine] analyzeStream: 收到 bestmove 退出，共 32 条 info，总用时 1469ms
        //     [Shot]   引擎进程退出 failures=2 retryMs=4000 depth=16 engineError=引擎未创建
        //   对策：先作废分析代次 —— 那个线程的 finally 会因 `myGen != analyzeGen`
        //   整段跳过，不再把"关窗"记成失败。顺便把失败计数与退避清零，防止残留。
        //   ⚠️ 必须在把 engine 置 null **之前**执行，顺序不能换。
        ++analyzeGen;
        failCount = 0;
        retryAfterAt = 0;
        // ★★ 2026-09-27 修 scanLoop 泄漏：**先关自续开关，再收回排队任务**。
        //   顺序不能反 —— removeCallbacks 只掐得掉"还没跑"的排队项，
        //   只有先把 loopAlive 置 false，才能让"正在执行中的那一轮"以及它拉起的
        //   识图线程稍后的 post 一并作废（否则面板都摘了心跳还在空转，实测约 30 秒）。
        loopAlive = false;
        handler.removeCallbacks(scanLoop);
        // ★★ 2026-09-23 修 ANR：主线程**只做摘面板**这一件快事。
        //   原来 display.release() / reader.close() / projection.stop() / engine.stop()
        //   全在主线程跑，而 onDestroy 本来就在主线程 —— `MediaProjection.stop()` 要等
        //   虚拟显示销毁，实测能把主线程占住好几秒，于是用户点 ✕ 后
        //   下一次触摸超时 → ANR（系统日志：Input dispatching timed out，
        //   随后 "crashed too many times, killing"）。
        hidePanelSync();
        // 其余清理丢后台线程，并把引用先摘下来（避免后台线程和我们抢字段）
        final VirtualDisplay d = display; final ImageReader r = reader;
        final MediaProjection p = projection; final Engine e = engine;
        display = null; reader = null; projection = null; engine = null;
        new Thread(new Runnable() {
            public void run() {
                try { if (d != null) d.release(); } catch (Throwable ignored) { }
                try { if (r != null) r.close(); } catch (Throwable ignored) { }
                try { if (p != null) p.stop(); } catch (Throwable ignored) { }
                try { if (e != null) e.stop(); } catch (Throwable ignored) { }
                Diag.log("Shot", "onDestroy 后台清理完成（面板已在主线程摘除）");
            }
        }).start();
    }

    /**
     * 立刻把悬浮面板从 WindowManager 摘掉（幂等，可在任意线程调）。
     * ★ 2026-09-23：点 ✕ 时先调它 —— 保证"点了就消失"，不等 onDestroy 里那串清理动作。
     *   移除失败会重试一次 removeViewImmediate，并把异常写进日志（原来这里是静默吞掉的，
     *   所以"偶尔关不掉"根本查不到痕迹）。
     */
    /**
     * 摘面板 —— **只能在非事件回调处同步调**（例如 onDestroy）。
     * ⚠️ 2026-09-23 踩坑：绝不能在 `onClick` 里直接调它！那会在 `dispatchTouchEvent`
     *   处理过程中同步移除**正在处理这个事件的视图树**，实测直接卡死 + 闪退。
     *   View 回调里务必用 {@link #hidePanelAsync()}。
     */
    private void hidePanelSync() {
        if (Looper.myLooper() == Looper.getMainLooper()) doHidePanel();
        else handler.post(new Runnable() { public void run() { doHidePanel(); } });
    }

    /**
     * 异步摘面板（**View 事件回调里只准用这个**）。
     * 走消息队列 → 等这一轮触摸事件分发结束、回到事件循环后才真正移除，避开崩溃。
     */
    private void hidePanelAsync() {
        handler.post(new Runnable() { public void run() { doHidePanel(); } });
    }

    private void doHidePanel() {
        if (panel == null) return;
        try {
            wm.removeView(panel);
        } catch (Throwable t) {
            Diag.log("Shot", "removeView 异常，改试 removeViewImmediate: " + t);
            try { wm.removeViewImmediate(panel); }
            catch (Throwable t2) { Diag.log("Shot", "removeViewImmediate 也失败: " + t2); }
        }
        panel = null;
        Diag.log("Shot", "panel removed（面板已摘除）");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void setPanelAlpha(final float a) {
        if (a < 1f) hiddenAt = System.currentTimeMillis(); else hiddenAt = 0;
        handler.post(new Runnable() {
            public void run() {
                try {
                    if (panel != null && panelParams != null) {
                        panelParams.alpha = a;
                        wm.updateViewLayout(panel, panelParams);
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    /**
     * 把当前正式局面画到小棋盘。
     * ★ 箭头只可能来自"**当前局面自己的**分析结果"：commitPosition 先清空 anaArrows 再调它，
     *   所以新局面天然不带旧箭头；而同一局面**根本走不到这里**（commitIfNewPosition 直接返回 false）。
     *   —— "相同局面刷新不清箭头"是结构上保证的，不再靠 FEN 字符串比对。
     */
    private void postBoard() {
        final Board copy = board.copy();
        final ArrayList<BoardView.Arrow> arrows = (anaArrows == null) ? null
                : new ArrayList<BoardView.Arrow>(anaArrows);
        handler.post(new Runnable() { public void run() {
            if (miniBoard != null) {
                miniBoard.setBoard(copy);
                miniBoard.setLastMove(-1, -1);
                miniBoard.setArrows(arrows);
            }
        } });
    }

    /** 面板上那一行状态 + 日志（logcat tag 是 xiangqi-ocr，取证时能一眼看全流程）。 */
    private void postInfo(final String s) {
        Diag.log("Shot", s);
        handler.post(new Runnable() { public void run() { if (statusText != null) statusText.setText(s); } });
    }

    private void toast(final String s) {
        // 保留失败诊断日志，不再以 Toast 打断使用；前台服务与面板有状态反馈。
        Diag.log("Shot", s);
    }
}

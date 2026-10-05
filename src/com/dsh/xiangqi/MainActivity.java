package com.dsh.xiangqi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 中国象棋 AI —— 白底暖色调界面（参考 Pro象棋），底部【分析 / 棋谱】两个 Tab。
 * 分析为逐层加深的实时分析，可与 AI 对弈同时进行。
 */
public class MainActivity extends Activity implements BoardView.Listener {

    private boolean editMode = false;                 // 摆谱模式
    private boolean aiRed = false, aiBlack = false;   // AI 接管哪一方（默认都不接管）
    // ★ 2026-09-23 现状：**走子档用「限时」而不是「限深」**。历史：更早是限时 300ms，
    //   后来发现 300ms 就能搜到 d20+（"太强"其实不是问题，问题是残局太快看不清）；
    //   中途改成限深 12 层，但残局 8ms 就搜完 12 层、棋子闪得看不清，于是又改回限时
    //   （改成了「限层/限时都可配」，见侧边栏「AI 执棋设定」的 aiMoveDepth / aiMoveMs）。见字段区注释。
    private static final long ENGINE_TIME_CAP_MS = 3000;  // 但最多想 3 秒（否则开局会像卡死）

    private static final int TAB_ANALYSIS = 0, TAB_SITUATION = 1, TAB_NAV = 2;
    /** ★ 用户明确要求：「局势分析」和「分析」是**两个并列的模块** —— 各自一个页签。 */
    private static final String[] TAB_NAMES = { "分析", "局势", "棋谱" };
    private int tab = TAB_ANALYSIS;

    private Board board;
    private BoardView boardView;
    private TextView status;
    private TextView modeChip;
    private EvalBar evalBar;
    private LinearLayout paletteBar, tabBar;
    private TextView paletteLabel;
    private final TextView[] tabViews = new TextView[3];
    private LinearLayout analysisContent, situationContent, navContent;
    /** 分析列表（可滚动）：第一优 + 备选招法。上方的「局势」折线图不在这条里（它是固定的）。 */
    private LinearLayout analysisRows, navRows;
    private ScrollView analysisScroll;
    /** 上一次渲染的是哪个局面；换局面才自动滚回顶部（免得覆盖用户手动滚动的位置）。 */
    private String analysisScrollFen = "";
    private TextView navInfo;

    private int humanSide = Board.RED;
    private boolean thinking = false;
    /**
     * ★ 2026-09-25（修 Bug1）：用户点分析时若 AI 正在思考，`startAnalysis()` 会因
     * `if (thinking) return;` 直接放弃。这里记下"用户其实想要分析"，
     * 等 AI 那趟结束（成功/失败都算）再补触发一次，避免"点了没反应"。
     */
    private boolean pendingAnalysis = false;
    private boolean gameOver = false;
    /**
     * 分析开关。★ 默认 false：分析区一开始是**空白**的（用户要求"不点分析就是空白"）。
     * 点工具栏/底部的「分析」才会变成 true 并开始分析；点「停止」变回 false 并清空。
     */
    private boolean analysisOn = false;
    /** 界面是否在前台。onStop 置 false —— 省电，但不改变用户的分析开关意图。 */
    private boolean pageActive = true;

    private int editPiece = Board.ROOK | Board.RED;
    private boolean editErase = false;
    private int editSideToMove = Board.RED;

    private String startFen = null;
    private final ArrayList<int[]> history = new ArrayList<int[]>();
    private final ArrayList<String> notation = new ArrayList<String>();
    private int ply = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * ★★ 2026-09-22 合并：原来 `engine`（走子）+ `analysisEngine`（分析）是**两个皮卡鱼进程**，
     *   各加载一份 50MB 权重，加起来常驻 300MB+。用户问得对："AI 走子直接按分析模式走不行吗？"
     *   —— 行。它俩本来就是**同一台引擎的两种参数档**，只是从不同时用：
     *     · 走子档：MultiPV=1、限深 12 层（皮卡鱼精简版没有"调棋力"的选项，限深是唯一的限强手段）
     *     · 分析档：MultiPV=5、不限深跑满 12 秒
     *   `warmUpEngine()` 里为对弈预热的那台也早就被删掉了（它的进程还活着占着内存，
     *   走的却是另一台"从没拉起来"的引擎，纯属白占）—— 现在全部收进这一台。
     */
    private Engine engine;
    /** 走子限时（ms）。**0 = 不启用限时**。默认 200ms。侧边栏「AI 执棋设定」可改。 */
    private long aiMoveMs = 200;
    /** 走子限层。**0 = 不启用限层**。默认 0（不启用）。侧边栏「AI 执棋设定」可改。 */
    private int aiMoveDepth = 0;
    /** 悬浮窗搜索参数：默认限深16、不限时；独立于 AI 执棋设置。 */
    private int floatAnalysisDepth = 16;
    private long floatAnalysisMs = 0;
    private EditText cfgFloatDepth, cfgFloatMs;
    /** 走子档的兜底时限：万一引擎卡住不回话，到点强制收手出招。 */
    private static final long AI_MOVE_TIMEOUT_MS = 3000;
    /** 红黑条显示层数用的兜底值（引擎还没来得及报 depth 时）。 */
    private static final int AI_DISPLAY_DEPTH = 12;
    /**
     * ★ 2026-09-23：Hash（MB），**一个值，三个档位统一用**。
     *   早先按档位分成"走子 32 / 分析 64"，但基准实测 **32→64→128→256 没有可测差异**
     *   （200ms 的搜索连 32MB 的零头都用不到），分档纯属多余，
     *   还让人看不懂"0=跟随默认"到底跟的是什么。现在就是一个数，默认 32。
     */
    private int userHashMb = 32;
    /** ★ 2026-09-23：搜索线程数。默认 4。 */
    private int userThreads = 4;

    /** 侧边栏设置的存储名。 */
    private static final String PREF_CFG = "xiangqi_cfg";

    /** 读回上次保存的设置（onCreate 里最先调，保证引擎启动前参数就是对的）。 */
    private void loadSettings() {
        android.content.SharedPreferences sp = getSharedPreferences(PREF_CFG, MODE_PRIVATE);
        userHashMb = sp.getInt("hashMb", 32);
        userThreads = sp.getInt("threads", 4);
        aiMoveDepth = sp.getInt("aiDepth", 0);
        aiMoveMs = sp.getLong("aiMs", 200);
        floatAnalysisDepth = sp.getInt("floatDepth", 16);
        floatAnalysisMs = sp.getLong("floatMs", 0);
    }

    private void saveSettings() {
        getSharedPreferences(PREF_CFG, MODE_PRIVATE).edit()
                .putInt("hashMb", userHashMb)
                .putInt("threads", userThreads)
                .putInt("aiDepth", aiMoveDepth)
                .putLong("aiMs", aiMoveMs)
                .putInt("floatDepth", floatAnalysisDepth)
                .putLong("floatMs", floatAnalysisMs)
                .apply();
    }
    /** 分析档：不限深、跑满 12 秒，要准。 */
    private static final long ANALYSIS_MAIN_MS = 12000;

    private int analysisGen = 0;
    private List<Engine.Line> analysis = new ArrayList<Engine.Line>();
    private int analysisDepth = 0;
    // 「敌方应手」不再单独存状态：直接取主变第 2 手（见 replyMoveFromPv）。
    /** 最近一趟分析/回算报出来的深度，红黑条显示用。 */
    private int lastEngineDepth = AI_DISPLAY_DEPTH;

    // ---------------- 局势折线图（类似天天象棋的"局势"） ----------------
    /** 每一手一个分数（红方视角 centipawn），索引 = 手数（0 = 开局）。未知记 EvalChart.UNKNOWN。 */
    private final ArrayList<Integer> chartScores = new ArrayList<Integer>();

    /**
     * ★ 2026-09-23（用户要求）：**每一步的局面快照（FEN）**，index = 手数（0 = 起始局面）。
     *
     * 为什么单独存一份、而不是靠 history 重推？
     *   · 「逐手回算」要能"把每一手的局面原样摆出来" —— 快照最直接；
     *   · history 只记着法，一旦某步记录不可靠（识图/导入来的局面尤其如此），
     *     从起始局面重推就会一路偏下去；
     *   · 快照是"当时就是这样的"，不受后续任何操作影响。
     *
     * 重置时机（用户指定）：开新局 / 初始局面 / 导入棋谱 / 识图 / 重开 App
     *   —— 这些路径全都经过 resetHistoryFromBoard()，所以清空逻辑放在那里。
     */
    private final ArrayList<String> fenAtPly = new ArrayList<String>();
    private EvalChart evalChart;

    /**
     * 逐手回算的运行状态。
     * ★ 2026-09-22：原来这里是 `chartEngine`（第三台引擎）+ `chartScanning` + `chartScanGen`。
     *   引擎合并后不再有独立进程，回算复用主引擎，靠 `deepScanGen` 代数作废上一趟。
     */
    private boolean deepScanning = false;
    private int deepScanGen = 0;
    /** 上一次回算跑完时的棋谱手数（-1 = 还没算过）。用户要求：算过的没变就不重复算。 */
    private int deepScanPly = -1;
    /**
     * 回算每手想多深 / 最多想多久。
     * ★ 2026-10-01：按用户要求，深度 12 → **16 层**；时间**保持 600ms 不变**（用户指定）。
     *   两者是"谁先到谁停"的关系 —— 实际每个局面能到几层，取决于它自不复杂：
     *   简单局面能在 600ms 内爬到 16 层，复杂的（残局分支少反而更快，中局更慢）
     *   会被 600ms 先截断，停在该时刻到达的层数。
     */
    private static final int DEEP_SCAN_DEPTH = 16;
    private static final long DEEP_SCAN_MS = 600;


    private static final int[] PALETTE = {
        Board.KING | Board.RED, Board.ADVISOR | Board.RED, Board.ELEPHANT | Board.RED, Board.HORSE | Board.RED,
        Board.ROOK | Board.RED, Board.CANNON | Board.RED, Board.PAWN | Board.RED,
        Board.KING | Board.BLACK, Board.ADVISOR | Board.BLACK, Board.ELEPHANT | Board.BLACK, Board.HORSE | Board.BLACK,
        Board.ROOK | Board.BLACK, Board.CANNON | Board.BLACK, Board.PAWN | Board.BLACK,
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DetOcr.NO_SHRINK = false;      // 冷启动一律复位，免得自检的开关残留到正常使用
        Diag.init(getApplicationContext());
        Diag.log("App", "MainActivity onCreate");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // ★ 系统栏（顶上状态栏 / 底下手势条那两个区域）原来留白：
        //   状态栏被显式设成了纯白（0xFFFFFFFF），导航栏则吃主题默认的白，
        //   于是页面米色、上下各一条白边，看着像"没铺满"。现在一律刷成页面底色 →
        //   视觉上就等于"白边没了"。图标走浅色栏的深色图标，米色底上才看得清。
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        int sysUi = getWindow().getDecorView().getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= 23) sysUi |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) sysUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(sysUi);
        loadSettings();          // ★ 先读回用户设置（Hash/线程/限层/限时），再建界面
        setContentView(buildUi());
        newGame();
        // ★ 2026-09-22：这里原来是无条件 `warmUpEngine()`（一进 App 就拉引擎，常驻 250MB）。
        //   改成懒启动 —— 谁要用谁负责调 `ensureEngineReady()`（走子/分析/深度分析三处）。
        handleExternalIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleExternalIntent(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (Bridge.pendingCells != null) {
            final int[] cells = Bridge.pendingCells;
            Bridge.pendingCells = null;
            try {
                loadRecognized(cells, "扫谱");
            } catch (Throwable t) {
                android.util.Log.e("xiangqi-ocr", "loadRecognized failed: " + t);
                toast("载入识别结果失败：" + t);
            }
        }
        adoptFloatingRecord();
    }

    /** 已经接过哪一版悬浮窗棋谱（`Bridge.gameSeq`），防止每次切回前台都重置局面。 */
    private int adoptedGameSeq = 0;

    /**
     * ★ 2026-09-21：把悬浮窗**在后台自动记下来的棋谱**接过来。
     * 悬浮窗只跑 0.9→1.8 秒的实时搜索，棋力有限；但它边识别边把整局记着，
     * 回到这里就能用「棋谱」页翻、用「局势」页看整盘走势、点「全盘分析」做固定 12 层的深度分析。
     * 只在记录真的变了（`gameSeq` 变了）时才动，免得每次切前台都把当前局面重来一遍。
     */
    private void adoptFloatingRecord() {
        final String fen = Bridge.gameFen;
        final int[] ms = Bridge.gameMoves;
        if (fen == null || ms == null || ms.length == 0) return;
        if (Bridge.gameSeq == adoptedGameSeq) return;
        if (board == null || !board.loadFen(fen)) return;
        adoptedGameSeq = Bridge.gameSeq;
        history.clear();
        notation.clear();
        for (int i = 0; i < ms.length; i++) {
            String text = Notation.move(board, ms[i]);
            int cap = board.makeMove(ms[i]);
            history.add(new int[] { ms[i], cap });
            notation.add(text);
        }
        startFen = fen;
        ply = history.size();
        boardView.setBoard(board);
        boardView.setLastMove(Board.moveFrom(ms[ms.length - 1]), Board.moveTo(ms[ms.length - 1]));
        boardView.invalidate();
        resetChart();
        syncChart();
        refreshNav();
        if (analysisOn) { clearAnalysisRows(); startAnalysis(); }
        toast("已载入悬浮窗记录的棋谱（" + ms.length + " 手）");
        Diag.log("App", "adopt floating record: " + ms.length + " moves");
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 切到后台/锁屏：立刻停止一切计算，避免"耗电比充电快"。
        // 注意只关 pageActive，不动 analysisOn —— 用户开的分析开关要保留，回来接着分析。
        pageActive = false;
        if (engine != null && !thinking) { try { engine.stopAnalysis(); } catch (Throwable ignored) { } }
        Diag.log("App", "onStop -> 停止分析，省电");
    }

    @Override
    protected void onStart() {
        super.onStart();
        pageActive = true;
        if (analysisOn && !editMode && !gameOver) startAnalysis();
        Diag.log("App", "onStart -> 恢复");
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.stop();
        super.onDestroy();
    }

    private void handleExternalIntent() {
        Intent it = getIntent();
        android.util.Log.i("xiangqi-ocr", "handleExternalIntent it=" + it + " ocrImage=" + (it == null ? null : it.getStringExtra("ocrImage")));
        if (it == null) return;
        String ocrImage = it.getStringExtra("ocrImage");
        if (ocrImage != null) {
            it.removeExtra("ocrImage");
            final String path = ocrImage;
            toast("识别中…（首次会加载中文 OCR 模型，稍等几秒）");
            new Thread(new Runnable() {
                public void run() {
                    DetOcr.init(getApplicationContext());    // YOLO26s 棋子检测器（主力）
                    OnnxOcr.init(getApplicationContext());   // 开源深度学习识别模型
                    OnnxPose.init(getApplicationContext());  // 开源棋盘角点检测模型
                    OnnxRec.init(getApplicationContext());   // PP-OCR 读字模型
                    android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
                    bo.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(path, bo);
                    int bs = 1;
                    while (bo.outWidth / bs > 2000 || bo.outHeight / bs > 2000) bs *= 2;
                    android.graphics.BitmapFactory.Options bo2 = new android.graphics.BitmapFactory.Options();
                    bo2.inSampleSize = bs;
                    final android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(path, bo2);
                    android.util.Log.i("xiangqi-ocr", "decodeFile " + path + " -> " + (bm != null));
                    if (bm == null) {
                        ui.post(new Runnable() { public void run() { toast("打不开图片：" + path); } });
                        return;
                    }
                    long t0 = System.currentTimeMillis();
                    // ★★★ 2026-09-29：这是**静态单张图**，没有"连续帧"这回事 ——
                    //   先清掉跨帧粘性（粘性框 + 上一帧的旋转结论），保证同一张图跑多少次结果一致。
                    DetOcr.resetSticky();
                    final int[] cells = Ocr.recognize(bm);
                    android.util.Log.i("xiangqi-ocr", "recognize done in " + (System.currentTimeMillis() - t0) + "ms");
                    ui.post(new Runnable() {
                        public void run() {
                            if (cells != null) loadRecognized(cells, "扫图识谱");
                            else toast("没认到棋盘：请用「扫图识谱」里的「重标」贴着棋盘外框点四个角，识别会准很多");
                        }
                    });
                }
            }).start();
        }
        String ocrPath = it.getStringExtra("ocrPath");
        if (ocrPath != null) {
            it.removeExtra("ocrPath");
            Intent oi = new Intent(this, OcrActivity.class);
            oi.putExtra("imagePath", ocrPath);
            startActivity(oi);
        }
        // 多帧融合自检（开发用）：--ez fuseTest true
        // 不依赖图片和推理，直接造一个格面来验证"被面板挡住的格子才回填"这条铁律 ——
        // 幽灵棋子（被吃掉的子不消失）就是这个规则写错造成的，所以要有一条能确定的证据。
        if (it.getBooleanExtra("fuseTest", false)) {
            it.removeExtra("fuseTest");
            // 造个假棋盘：格距 100，左上格中心 (100,100)
            final float[] g = { 100, 100, 900, 100, 900, 1000, 100, 1000 };
            final int[] ex = { 0, 0, 500, 600 };          // "面板"盖住左上角一片
            int[] occ = new int[90];
            occ[0] = Board.ROOK | Board.RED;              // (row0,col0) 中心(100,100) → 被盖住
            occ[5] = Board.HORSE | Board.RED;             // (row0,col5) 中心(600,100) → 没被盖住
            int[] fresh = occ.clone();
            fresh[0] = 0;                                 // 模拟"这两格这一帧没认到子"
            fresh[5] = 0;
            int[] fused = ScanFuse.restoreOccluded(fresh, occ, g, ex);
            boolean occludedKept = fused[0] == occ[0];    // 被挡住的要沿用上一帧
            boolean ghostGone = fused[5] == 0;            // 没被挡住的不能复活
            android.util.Log.i("xiangqi-ocr", "fuseTest 被挡格应沿用=" + (occludedKept ? "是(PASS)" : "否(FAIL)")
                    + " 未挡格应清空=" + (ghostGone ? "是(PASS)" : "否(FAIL)")
                    + " 融合后格子0=" + fused[0] + " 格子5=" + fused[5]);
        }
        // 悬浮窗端到端自检（开发用）：--es shotImage <图片> [--ei ex0/ex1/ex2/ex3 面板矩形]
        // 直接在真机上跑"悬浮窗那条链路"——检测器 + 排除自己的小面板 + 被面板挡住的格子回填，
        // 再模拟一次吃子，看会不会留下幽灵棋子。用来给悬浮窗的改动取证，不参与正常使用。
        String shotImage = it.getStringExtra("shotImage");
        if (shotImage != null) {
            it.removeExtra("shotImage");
            final String sp = shotImage;
            // 开发自检：--ez noshrink true 强制关预缩（A/B 对照组），用来证明"预缩确实多检出"。
            DetOcr.NO_SHRINK = it.getBooleanExtra("noshrink", false);
            final int[] ex = it.hasExtra("ex0")
                    ? new int[] { it.getIntExtra("ex0", 0), it.getIntExtra("ex1", 0),
                                  it.getIntExtra("ex2", 0), it.getIntExtra("ex3", 0) }
                    : null;
            new Thread(new Runnable() {
                public void run() {
                    DetOcr.init(getApplicationContext());
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(sp);
                    if (bm == null) { android.util.Log.e("xiangqi-ocr", "shotTest: 打不开 " + sp); return; }
                    int[] c1;
                    try {
                        c1 = Ocr.recognizeDetOnly(bm, ex);
                    } finally {
                        // ★★ 2026-09-29：NO_SHRINK 是**全进程共享**的开关。自检跑完必须复位，
                        //   否则用户自检完之后再用扫图/悬浮窗，全程都在"关预缩"的状态下工作。
                        DetOcr.NO_SHRINK = false;
                    }
                    android.util.Log.i("xiangqi-ocr", "shotTest 面板="
                            + (ex == null ? "无" : ex[0] + "," + ex[1] + "," + ex[2] + "," + ex[3])
                            + " 第1帧=" + (c1 == null ? "未建盘" : countOf(c1) + "子") + " " + DetOcr.lastDebug);
                    if (c1 == null) return;
                    float[] g = Ocr.lastCorners == null ? null : Ocr.lastCorners.clone();
                    int[] occ = c1.clone();
                    // 模拟第 2 帧：清掉两个"有子"的格子 —— 一个在面板外（吃子），一个在面板内（被挡住）
                    int outSq = -1, inSq = -1;
                    for (int sq = 0; sq < 90; sq++) {
                        if (c1[sq] == 0) continue;
                        if (ScanFuse.cellUnder(g, sq, ex)) { if (inSq < 0) inSq = sq; }
                        else if (outSq < 0) outSq = sq;
                        if (outSq >= 0 && inSq >= 0) break;
                    }
                    int[] c2 = c1.clone();
                    if (outSq >= 0) c2[outSq] = 0;
                    if (inSq >= 0) c2[inSq] = 0;
                    int[] fused = ScanFuse.restoreOccluded(c2, occ, g, ex);
                    boolean ghost = outSq >= 0 && fused[outSq] != 0;                 // 面板外的空格被回填 = 幽灵
                    boolean kept = inSq < 0 || fused[inSq] == occ[inSq];             // 被挡住的格子要保住
                    android.util.Log.i("xiangqi-ocr", "shotTest 第2帧模拟吃子 面板外格=" + outSq
                            + " 面板内格=" + inSq + " 融合后=" + countOf(fused) + "子 幽灵棋子="
                            + (ghost ? "有(FAIL)" : "无(PASS)") + " 遮挡保住="
                            + (inSq < 0 ? "无格可测" : (kept ? "是(PASS)" : "否(FAIL)")));
                }
            }).start();
        }
        // ★ 2026-09-21：`--ez autoPlay` 这个口子连同"自动走子"功能一起删了（用户要求）。
        // 悬浮窗直接拉起。
        // --ez startScan true 拉起悬浮窗；再加 --ez accshot true 则走无障碍截屏那条抓屏路
        if (it.getBooleanExtra("startScan", false)) {
            it.removeExtra("startScan");
            startFloatScan(it.getBooleanExtra(ShotService.EXTRA_ACC_SHOT, false));
        }
    }

    // ================= 界面 =================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        // ★ 横向 padding 放到 0：棋盘要横着铺满整屏。原来那点左右留白，改成
        //   "给棋盘以外的每个子视图补左右边距" —— 在 return 之前统一补一遍（见下面的循环）。
        root.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));

        // 图标工具条（省地方）：对局 / 分析 / 摆谱 / 扫图识谱 / 悬浮窗识谱
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // ★ 2026-09-23（用户要求）：工具栏最左加「菜单」☰ —— 点开左侧抽屉。
        //   同时把「摆谱」「悬浮窗」从工具栏挪到下面那行功能按钮，工具栏只留高频的 4 个。
        toolbar.addView(iconButton("menu", "菜单", new View.OnClickListener() {
            public void onClick(View v) { openDrawer(); }
        }), iconLp());
        analysisHolder = iconButton("analyze", "分析", new View.OnClickListener() {
            public void onClick(View v) { openAnalysis(); }
        });
        // 长按「分析」＝关掉分析（省电）。原来那行「停止」按钮用户嫌占地方，删了，收进长按里。
        analysisHolder.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (!analysisOn) { toast("分析本来就是关的"); return true; }
                stopAnalysisByUser();
                toast("已停止分析");
                return true;
            }
        });
        toolbar.addView(analysisHolder, iconLp());
        redAiHolder = iconButton("match", "AI执红", new View.OnClickListener() {
            public void onClick(View v) { setAi(true, !aiRed); }
        });
        toolbar.addView(redAiHolder, iconLp());
        blackAiHolder = iconButton("float", "AI执黑", new View.OnClickListener() {
            public void onClick(View v) { setAi(false, !aiBlack); }
        });
        toolbar.addView(blackAiHolder, iconLp());
        toolbar.addView(iconButton("ocr", "扫图", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, OcrActivity.class)); }
        }), iconLp());

        // 红黑条（评估条）
        // ★ 用户要求："红黑条就别用白框框起来了，没意义" —— 原来它套在 `Ui.card`（白底+内边距）里，
        //   于是米色条外面又多一圈白框。现在改用**透明**容器直接贴在页面底色上。
        LinearLayout statusWrap = new LinearLayout(this);
        statusWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scp.topMargin = Ui.dp(this, 8);
        status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setTextSize(13.5f);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);   // 省空间：只留下面的分数条
        statusWrap.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        evalBar = new EvalBar(this);   // 只显示分数，不再提供"改层数"的入口
        LinearLayout.LayoutParams ebp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 26));
        statusWrap.addView(evalBar, ebp);
        root.addView(statusWrap, scp);

        // ★ 不要卡片了：原来是 Ui.card(this,4) = 白底 + 4dp 内边距 → 木纹外面套了一圈白边
        //   （实测白圈 13px）。现在换透明容器（无底色、无内边距）。
        LinearLayout boardCard = new LinearLayout(this);
        boardCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bcp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bcp.topMargin = 0;   // ★ 用户："红黑条和棋盘之间为什么有个空隙" —— 原来是 6dp(20px) 的页底色，
                             //   现在贴着分析条，木纹直接接上去。
        this.boardCard = boardCard;
        boardView = new BoardView(this);
        boardView.setListener(this);
        boardCard.addView(boardView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(boardCard, bcp);

        // 摆谱调色板
        // ★★ 2026-09-25 修 Bug3（按用户要求重做）：原来它是 root 里**独立的一项**，
        //   一展开就向下占空间，把下面的页签栏和底部按钮行整条往下挤。
        //   现在**不占 root 位置**，改成放进 tabCard（见下面 addView），
        //   和 分析/局势/棋谱 三块共用同一片"占满剩余空间"的常驻区域 ——
        //   展开/收起时总高度不变，**底部那一行纹丝不动**。
        paletteBar = new LinearLayout(this);
        paletteBar.setOrientation(LinearLayout.VERTICAL);
        paletteBar.setBackground(Ui.round(Ui.CARD2, 6f, this, Ui.STROKE));
        paletteBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        paletteBar.setVisibility(View.GONE);
        paletteLabel = new TextView(this);
        paletteLabel.setTextColor(Ui.SUB);
        paletteLabel.setTextSize(12f);
        paletteLabel.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
        paletteLabel.setText("点下面的棋子选中，再点棋盘落子；点已有棋子＝删除");
        paletteBar.addView(paletteLabel);
        buildPalette();

        // Tab 内容区（固定常驻：用户要求"把分析那一片固定住"，不再点一下开/点一下收）
        LinearLayout tabCard = Ui.card(this, 4);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tcp.topMargin = Ui.dp(this, 8);
        analysisContent = buildAnalysisTab();
        situationContent = buildSituationTab();
        navContent = buildNavTab();
        tabCard.addView(analysisContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabCard.addView(situationContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabCard.addView(navContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // ★★ 摆谱面板也放进来（和第 3 块叠在同一个位置，靠 visibility 互斥切换）
        tabCard.addView(paletteBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        this.tabCard = tabCard;
        root.addView(tabCard, tcp);

        // Tab 栏
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbp.topMargin = Ui.dp(this, 8);
        root.addView(tabBar, tbp);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = Ui.tab(this, TAB_NAMES[i]);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 点「分析」＝开分析（而不是"开/关面板"），点了才有招法可看
                    if (idx == TAB_ANALYSIS) openAnalysis(); else showTab(idx);
                }
            });
            tabViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            int m = Ui.dp(this, 3);
            lp.setMargins(m, 0, m, 0);
            tabBar.addView(t, lp);
        }
        // ★ 开机必须走一次 showTab：三个页的内容是**叠在一个容器里**的，不调这一下
        //   三页会同时 VISIBLE（下面两页被裁到屏幕外，看不见但白占内存/白测一遍），
        //   而且底部一个页签都不高亮 —— 看着像"没选中任何一页"。默认落在「分析」。
        showTab(TAB_ANALYSIS);

        // 功能按钮（原有功能全保留）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(this, 6);
        root.addView(row, rp);
        row.addView(iconButton("new", "新局", new View.OnClickListener() { public void onClick(View v) { newGame(); } }), iconLp());
        row.addView(iconButton("undo", "悔棋", new View.OnClickListener() { public void onClick(View v) { undo(); } }), iconLp());
        row.addView(iconButton("swap", "换边", new View.OnClickListener() { public void onClick(View v) { boardView.setFlipped(!boardView.isFlipped()); toast("已换边（只影响显示方向）"); } }), iconLp());
        // ★ 2026-09-23：从工具栏挪下来的两个；「导入/复制/关于」已上移到左侧抽屉的菜单里。
        editHolder = iconButton("edit", "摆谱", new View.OnClickListener() {
            public void onClick(View v) { setEditMode(!editMode); }
        });
        row.addView(editHolder, iconLp());
        View floatBtn = iconButton("float", "悬浮窗", new View.OnClickListener() {
            public void onClick(View v) { startFloatScan(false); }
        });
        // 长按「悬浮窗」= 改走无障碍截屏那条路：不用投屏授权弹窗、没有"屏幕共享中"常驻提示。
        // 需要 Android 11+ 且已开启无障碍服务（否则服务端会自动退回投屏并提示）。
        floatBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { startFloatScan(true); return true; }
        });
        row.addView(floatBtn, iconLp());

        // ★ 除了棋盘和红蓝条，其它子视图左右各留 10dp（这段边距原来在 root 的 padding 上）。
        //   棋盘不给边距 → 横着铺满整屏（用户要求"尽量让棋盘大一点"）。
        //   红蓝条也不给 → 用户要求"红蓝条占满一整行，别和四周留空隙"。
        //   ⚠️ 以前试过给棋盘**负边距**往外顶，实测在 LinearLayout 里不可靠（布局位置和实际
        //   绘制位置对不上：uiautomator 报 [0,471][1159,1759]，屏幕上木纹却从 x=33 开始）。
        //   换成"root 不带横向 padding + 别的视图各自补边距"就完全确定。
        int side = Ui.dp(this, 10);
        for (int i = 0; i < root.getChildCount(); i++) {
            View ch = root.getChildAt(i);
            if (ch == boardCard || ch == statusWrap) continue;
            ViewGroup.LayoutParams lp = ch.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).leftMargin = side;
                ((ViewGroup.MarginLayoutParams) lp).rightMargin = side;
            }
        }
        // ★ 2026-09-23（用户要求）：外面套一层 FrameLayout 承载「左侧抽屉」——
        //   三层叠放：主内容 root  →  半透明遮罩 scrim  →  抽屉面板 drawerPanel（默认 GONE）。
        //   遮罩点一下就关抽屉；抽屉本体吃掉点击，别透到遮罩上。
        FrameLayout shell = new FrameLayout(this);
        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x99000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        shell.addView(drawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerWidth = Ui.dp(this, 276);
        ScrollView drawerScroll = new ScrollView(this);
        drawerScroll.setFillViewport(true);
        drawerScroll.addView(buildDrawer());
        drawerPanel = drawerScroll;
        drawerPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.START;
        shell.addView(drawerPanel, dlp);

        return shell;
    }

    private View drawerPanel, drawerScrim;
    private int drawerWidth = 0;
    private EditText cfgHash, cfgThreads, cfgDepth, cfgMs;

    /** ☰ 打开抽屉：遮罩淡入 + 面板从左滑入。 */
    private void openDrawer() {
        if (drawerPanel == null) return;
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.setAlpha(0f);
        drawerScrim.animate().alpha(1f).setDuration(180).start();
        drawerPanel.setVisibility(View.VISIBLE);
        drawerPanel.setTranslationX(-drawerWidth);
        drawerPanel.animate().translationX(0f).setDuration(180).start();
    }

    /** 点遮罩 / 点菜单项 / 应用设置后收起抽屉。 */
    private void closeDrawer() {
        if (drawerPanel == null || drawerPanel.getVisibility() != View.VISIBLE) return;
        drawerPanel.animate().translationX(-drawerWidth).setDuration(160)
                .withEndAction(new Runnable() { public void run() {
                    drawerPanel.setVisibility(View.GONE);
                    drawerScrim.setVisibility(View.GONE);
                } }).start();
        drawerScrim.animate().alpha(0f).setDuration(160).start();
    }

    /** 抽屉里的一条「文字菜单项」。 */
    private View drawerItem(String label, final Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13.5f);
        tv.setTextColor(Ui.TEXT);
        tv.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 12));
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return tv;
    }

    /** 抽屉里的小节标题。 */
    private View drawerSection(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(11.5f);
        tv.setTextColor(Ui.SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 18);
        lp.bottomMargin = Ui.dp(this, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 抽屉里的一行数值输入：左标签 + 右输入框。 */
    private View numberRow(String label, EditText et, int value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTextColor(Ui.TEXT);
        r.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        et.setText(String.valueOf(value));
        et.setTextSize(13f);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setGravity(Gravity.CENTER);
        // ★ 2026-09-25 修 Bug2（用户报"改限时的时候数字被遮住一半"）：
        //   EditText 自带的下划线背景 + 内部 padding，配上 WRAP_CONTENT 高度时文字容易被裁。
        //   现在：① 换掉默认背景；② 明确内边距；③ 给足最小高度；④ 关掉字体额外留白；
        //   ⑤ 宽度 76 → 92dp，五位数字（如 60000）+ 光标也放得下。
        et.setBackgroundColor(0x14000000);
        et.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        et.setMinHeight(Ui.dp(this, 36));
        et.setIncludeFontPadding(false);
        r.addView(et, new LinearLayout.LayoutParams(Ui.dp(this, 92), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** 组装抽屉内容：菜单项 + 核心设置 + AI 执棋设定。 */
    private View buildDrawer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFFF7F2E7);          // 跟页面同色系
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 18));
        box.setClickable(true);                      // 吃掉点击，别透给遮罩

        TextView t1 = new TextView(this);
        t1.setText("象棋分析");
        t1.setTextSize(17f);
        t1.setTextColor(Ui.TEXT);
        t1.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        box.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("v" + appVersionFull() + " · 本地引擎");
        t2.setTextSize(11.5f);
        t2.setTextColor(Ui.SUB);
        box.addView(t2);

        box.addView(drawerItem("导入 FEN", new Runnable() { public void run() { closeDrawer(); showFenImport(); } }));
        box.addView(drawerItem("复制 FEN", new Runnable() { public void run() { closeDrawer(); copyFen(); } }));
        box.addView(drawerItem("关于 / 诊断", new Runnable() { public void run() { closeDrawer(); showAbout(); } }));

        box.addView(drawerSection("核心设置"));
        cfgHash = new EditText(this);
        box.addView(numberRow("Hash (MB)", cfgHash, userHashMb));
        cfgThreads = new EditText(this);
        box.addView(numberRow("搜索线程 · 1~16", cfgThreads, userThreads));

        box.addView(drawerSection("悬浮窗设定（下次启动生效）"));
        cfgFloatDepth = new EditText(this);
        box.addView(numberRow("限层 · 0=不限层 (d)", cfgFloatDepth, floatAnalysisDepth));
        cfgFloatMs = new EditText(this);
        box.addView(numberRow("限时 · 0=不限时 (ms)", cfgFloatMs, (int) floatAnalysisMs));

        box.addView(drawerSection("AI 执棋设定"));
        cfgDepth = new EditText(this);
        box.addView(numberRow("限层 · 0=不限层 (d)", cfgDepth, aiMoveDepth));
        cfgMs = new EditText(this);
        box.addView(numberRow("限时 · 0=不限时 (ms)", cfgMs, (int) aiMoveMs));

        Button defaults = new Button(this);
        defaults.setText("恢复默认");
        defaults.setAllCaps(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 12);
        defaults.setLayoutParams(dlp);
        defaults.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                userHashMb = 32; userThreads = 4;
                aiMoveDepth = 0; aiMoveMs = 200;
                floatAnalysisDepth = 16; floatAnalysisMs = 0;
                cfgHash.setText("32"); cfgThreads.setText("4");
                cfgDepth.setText("0"); cfgMs.setText("200");
                cfgFloatDepth.setText("16"); cfgFloatMs.setText("0");
                saveSettings();
                if (engine != null) { engine.setThreads(userThreads); engine.setHash(hashMb()); }
                // 六项均恢复默认并已保存；输入框显示即为反馈，不弹提示。
            }
        });
        box.addView(defaults);

        Button apply = new Button(this);
        apply.setText("应用设置");
        apply.setAllCaps(false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 20);
        apply.setLayoutParams(alp);
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { applyDrawerSettings(); }
        });
        box.addView(apply);
        return box;
    }

    /** 把抽屉里的六项数值读回来、存盘；悬浮窗下次启动时读取。 */
    private void applyDrawerSettings() {
        userHashMb   = clampInt(cfgHash, 1, 4096, 32);
        userThreads  = clampInt(cfgThreads, 1, 16, 4);
        aiMoveDepth  = clampInt(cfgDepth, 0, 60, 0);
        aiMoveMs     = clampInt(cfgMs, 0, 60000, 200);
        floatAnalysisDepth = clampInt(cfgFloatDepth, 0, 60, 16);
        floatAnalysisMs = clampInt(cfgFloatMs, 0, 60000, 0);
        saveSettings();
        if (engine != null) {
            engine.setThreads(userThreads);
            engine.setHash(hashMb());
        }
        closeDrawer();
    }

    /** 读输入框里的整数，解析失败或越界就退回默认值。 */
    private int clampInt(EditText et, int min, int max, int def) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Throwable t) { return def; }
    }

    private View editHolder, redAiHolder, blackAiHolder;
    /** ★ 2026-09-25：「分析」按钮也提成成员 —— 之前是局部变量，所以它**永远不高亮**
     *   （styleIcon 只对 摆谱/AI执红/AI执黑 调过），用户看到的就是"别人有框、分析没框"。 */
    private View analysisHolder;

    /** 图标按钮：上面图标 + 下面小字，点哪儿都响应。 */
    private View iconButton(String iconKey, String label, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        box.setBackground(Ui.round(0x00000000, 6f, this, 0));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(Icons.get(this, iconKey, Ui.dp(this, 24), Ui.TEXT));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        box.addView(iv, ilp);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(9.5f);
        tv.setTextColor(Ui.SUB);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 2);
        box.addView(tv, tlp);
        box.setOnClickListener(l);
        box.setTag(new Object[] { iv, tv, iconKey });
        return box;
    }

    private LinearLayout.LayoutParams iconLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void setIconLabel(View holder, String label) {
        Object[] tag = (Object[]) holder.getTag();
        ((TextView) tag[1]).setText(label);
    }

    private void styleIcon(View holder, boolean active) {
        Object[] tag = (Object[]) holder.getTag();
        int color = active ? Ui.ACCENT_DARK : Ui.TEXT;
        ((android.widget.ImageView) tag[0]).setImageBitmap(Icons.get(this, (String) tag[2], Ui.dp(this, 24), color));
        ((TextView) tag[1]).setTextColor(active ? Ui.ACCENT_DARK : Ui.SUB);
        holder.setBackground(Ui.round(active ? 0xFFFDEFE0 : 0x00000000, 6f, this, active ? 0xFFF3D3B4 : 0));
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(this, 4);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    // ---------- 三个 Tab（分析 / 局势 / 棋谱） ----------

    private LinearLayout buildAnalysisTab() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBackground(Ui.round(Ui.CARD2, 4f, this, 0));
        head.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        head.addView(cell("招法", 2f, true, Ui.TEXT));
        head.addView(cell("胜率", 0.85f, true, Ui.TEXT));
        head.addView(cell("分数", 0.85f, true, Ui.TEXT));
        head.addView(cell("深度", 0.7f, true, Ui.TEXT));
        box.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 招法列表（可滚动）：第一优 + 备选招法。换局面会自动滚回顶部；同局面逐层加深时不动滚动位置。
        analysisScroll = new ScrollView(this);
        analysisRows = new LinearLayout(this);
        analysisRows.setOrientation(LinearLayout.VERTICAL);
        analysisScroll.addView(analysisRows);
        box.addView(analysisScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        showAnalysisBlank();
        return box;
    }

    /**
     * 局势页（独立页签，跟「分析」并列）。
     * 上半是标题 + 「全盘分析」按钮，剩下全部给折线图（所以图比原来塞在分析页里大得多）。
     */
    private LinearLayout buildSituationTab() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chartHead = new LinearLayout(this);
        chartHead.setOrientation(LinearLayout.HORIZONTAL);
        chartHead.setGravity(Gravity.CENTER_VERTICAL);
        chartHead.setPadding(Ui.dp(this, 4), 0, 0, 0);
        TextView chartTitle = new TextView(this);
        chartTitle.setText("局势（每一步的分数）");
        chartTitle.setTextColor(Ui.TEXT);
        chartTitle.setTextSize(12.5f);
        chartTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        chartTitle.setPadding(Ui.dp(this, 4), 0, 0, 0);
        chartHead.addView(chartTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button scanBtn = smallBtn("逐手回算", new View.OnClickListener() {
            public void onClick(View v) { scanWholeGame(); }
        });
        scanBtn.setTextSize(11f);
        scanBtn.setPadding(Ui.dp(this, 10), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3));
        chartHead.addView(scanBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(chartHead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        evalChart = new EvalChart(this);
        evalChart.bind(chartScores);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        chp.topMargin = Ui.dp(this, 3);
        chp.bottomMargin = Ui.dp(this, 2);
        box.addView(evalChart, chp);
        TextView tip = new TextView(this);
        tip.setText("中线以上红方占优、以下黑方占优；橙点是你现在看到的那一手。");
        tip.setTextColor(Ui.SUB);
        tip.setTextSize(11f);
        tip.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        box.addView(tip);
        return box;
    }

    private LinearLayout buildNavTab() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        navInfo = new TextView(this);
        navInfo.setTextColor(Ui.SUB);
        navInfo.setTextSize(12f);
        navInfo.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 6));
        box.addView(navInfo);
        ScrollView sv = new ScrollView(this);
        navRows = new LinearLayout(this);
        navRows.setOrientation(LinearLayout.VERTICAL);
        sv.addView(navRows);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button first = smallBtn("|◀ 起始", new View.OnClickListener() { public void onClick(View v) { jumpTo(0); } });
        bar.addView(first, btnLp());
        Button prev = smallBtn("◀ 上一步", new View.OnClickListener() { public void onClick(View v) { jumpTo(ply - 1); } });
        bar.addView(prev, btnLp());
        Button next = smallBtn("下一步 ▶", new View.OnClickListener() { public void onClick(View v) { jumpTo(ply + 1); } });
        bar.addView(next, btnLp());
        Button last = smallBtn("最新 ▶|", new View.OnClickListener() { public void onClick(View v) { jumpTo(history.size()); } });
        bar.addView(last, btnLp());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 6);
        box.addView(bar, blp);
        return box;
    }

    private Button smallBtn(String text, View.OnClickListener l) {
        Button b = Ui.button(this, text, false);
        b.setTextSize(11.5f);
        b.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
        b.setOnClickListener(l);
        return b;
    }

    private TextView cell(String text, float weight, boolean header, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        if (header) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 3), Ui.dp(this, 2), Ui.dp(this, 3));
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout tabCard, boardCard;
    private boolean tabOpen = true;

    /**
     * 切换「分析 / 局势 / 棋谱」三个页签。面板本身**常驻**，不再收起 —— 收起会让"点了没反应"更容易发生。
     */
    private void showTab(int t) {
        tab = t;
        tabOpen = true;
        if (tabCard != null) tabCard.setVisibility(View.VISIBLE);
        // ★ 2026-09-25：点页签 = 离开摆谱视图（摆谱面板和这几页共用 tabCard 那块区域）。
        //   顺手把摆谱模式也关掉，免得棋盘还在"落子=摆子"的状态而用户以为在正常对局。
        if (paletteBar != null) paletteBar.setVisibility(View.GONE);
        if (editMode) { editMode = false; styleIcon(editHolder, false); }
        for (int i = 0; i < tabViews.length; i++) Ui.setTabActive(tabViews[i], i == t, this);
        analysisContent.setVisibility(t == TAB_ANALYSIS ? View.VISIBLE : View.GONE);
        situationContent.setVisibility(t == TAB_SITUATION ? View.VISIBLE : View.GONE);
        navContent.setVisibility(t == TAB_NAV ? View.VISIBLE : View.GONE);
        if (t == TAB_NAV) refreshNav();
        if (t == TAB_SITUATION) refreshChart();
    }

    /**
     * 工具栏/底部「分析」的唯一入口：切到分析页 + 打开分析开关 + 立刻开跑。
     * 用户要求"不点分析是空白，点了才有分析招法的选项"，所以这里既是"显示"也是"启动"。
     *
     * ★ 2026-09-23：补上三档互斥的**反方向**。原来只做了"开 AI 走子 → 自动关分析"，
     *   但用户仍可以在 AI 对弈中再点「分析」→ 于是两边每 200ms 互相打断：
     *   AI 每走一步都 `stopAnalysis()`，分析面板一直转却出不来完整结果（已截图取证）。
     *   现在点分析就把 AI 走子**停下来**（不是锁功能，是让位 —— 随时可再点「AI执红/黑」继续）。
     *   注：若此刻 AI 正在思考（thinking=true），startAnalysis() 会先返回；
     *   等那一步落完，`land` 里还会再调一次 startAnalysis()，分析自然接上。
     */
    private void openAnalysis() {
        showTab(TAB_ANALYSIS);
        if (aiRed || aiBlack) {
            aiRed = false;
            aiBlack = false;
            styleIcon(redAiHolder, false);
            styleIcon(blackAiHolder, false);
            updateStatus(null);
            toast("已暂停 AI 走子（分析优先）");
        }
        // ★★ 2026-09-25 修 Bug1（用户报"走两步后点分析无效"）：
        //   startAnalysis() 开头有 `if (thinking) return;` —— AI 正在思考时点分析会被
        //   **静默放弃**，面板一动不动，看起来就是"点了没反应"。而 AI 对弈（maybeAiTurn）
        //   期间 thinking 大部分时间都是 true，所以撞上的概率很高。
        //   现在：① 主动打断 AI 那趟搜索；② 立刻给视觉反馈；③ 按钮高亮。
        if (thinking) {
            if (engine != null) { try { engine.stopAnalysis(); } catch (Throwable ignored) { } }
            toast("已打断 AI 思考，开始分析");
        }
        analysisOn = true;
        styleIcon(analysisHolder, true);      // ★ 补上高亮（原来漏了，所以"分析没框"）
        clearAnalysisRows();
        // 立刻给一行"启动中"：皮卡鱼首次启动要 1~2 秒，这段时间屏幕全空会让人以为"点了没反应"
        addSubRow(analysisRows, "皮卡鱼启动中…", Ui.SUB);
        startAnalysis();
    }

    /** 长按工具栏「分析」＝关掉分析（面板恢复空白）。原来的「停止」按钮已被用户要求删除。 */
    private void stopAnalysisByUser() {
        closeAnalysisQuietly();
    }

    /**
     * 关掉分析：**状态和高亮一起收**。★ 2026-09-25（修"分析框不消失"）：
     * 原来 `setAi` / `startDeepScan` 只写了 `analysisOn = false`，
     * 漏了 `styleIcon(analysisHolder, false)` —— 状态关了，按钮上的框还挂着。
     * 现在所有"关分析"的地方统一走这个方法，以后谁再写就不容易漏。
     */
    private void closeAnalysisQuietly() {
        analysisOn = false;
        styleIcon(analysisHolder, false);
        clearAnalysis();
        showAnalysisBlank();
    }

    /** 把分析列表清空（不动引擎状态）。 */
    private void clearAnalysisRows() {
        if (analysisRows != null) analysisRows.removeAllViews();
    }

    /** 没开始分析时的空面板：真的空白（用户要的就是"不点分析就是空白"）。 */
    private void showAnalysisBlank() {
        clearAnalysisRows();
        analysisScrollFen = "";
        if (boardView != null) boardView.setArrows(null);
    }

    private void buildPalette() {
        paletteBar.removeAllViews();
        // ★ 2026-09-25：buildPalette 一进来就清空 paletteBar，所以说明文字必须在这里补回来
        //   （原来它挂在 root 上、和 paletteBar 是兄弟节点，所以没这问题；现在它俩是父子了）。
        if (paletteLabel != null) {
            paletteLabel.setTextColor(Ui.SUB);
            paletteLabel.setTextSize(12f);
            paletteLabel.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
            paletteBar.addView(paletteLabel);
        }
        paletteBar.setOrientation(LinearLayout.VERTICAL);
        // 两行，每行 7 个：帅仕相马车炮兵 / 将士象马车炮卒
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 7; c++) {
                final int piece = PALETTE[r * 7 + c];
                TextView b = new TextView(this);
                b.setText(pieceName(piece));
                b.setTextSize(19f);
                b.setGravity(Gravity.CENTER);
                b.setTextColor(Board.isRed(piece) ? 0xFFC62828 : 0xFF37474F);
                b.setBackground(Ui.round(0xFFF7EFE2, 6f, this, Ui.STROKE));
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        editPiece = piece;
                        editErase = false;
                        paletteLabel.setText("已选「" + pieceName(piece) + "」：点棋盘落子（可连续点）");
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 32), 1f);
                lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
                row.addView(b, lp);
            }
            paletteBar.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.addView(paletteExtra("删除棋子", new View.OnClickListener() {
            public void onClick(View v) { editErase = true; paletteLabel.setText("点棋盘上的棋子即删除（可连续点）"); }
        }));
        ops.addView(paletteExtra("清空棋盘", new View.OnClickListener() {
            public void onClick(View v) {
                for (int i = 0; i < 90; i++) board.cells[i] = Board.EMPTY;
                board.kingSq[0] = -1; board.kingSq[1] = -1;
                board.sideToMove = editSideToMove;
                boardView.setBoard(board);
                resetHistoryFromBoard();
            }
        }));
        ops.addView(paletteExtra("初始局面", new View.OnClickListener() {
            public void onClick(View v) { board.setupInitial(); boardView.setBoard(board); resetHistoryFromBoard(); }
        }));
        ops.addView(paletteExtra("先手:红", new View.OnClickListener() {
            public void onClick(View v) {
                editSideToMove = Board.opponent(editSideToMove);
                ((Button) v).setText(editSideToMove == Board.RED ? "先手:红" : "先手:黑");
                board.sideToMove = editSideToMove;
                resetHistoryFromBoard();
            }
        }));
        paletteBar.addView(ops, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private Button paletteExtra(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setBackground(Ui.round(Ui.CARD, 6f, this, Ui.STROKE));
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setOnClickListener(l);
        return b;
    }

    private static String pieceName(int p) {
        String[] red = { "", "帅", "仕", "相", "马", "车", "炮", "兵" };
        String[] black = { "", "将", "士", "象", "马", "车", "炮", "卒" };
        int t = Board.type(p);
        if (t < 1 || t > 7) return "?";
        return Board.isRed(p) ? red[t] : black[t];
    }

    private void styleMode(Button b, boolean active) { }

    private boolean aiPlays(int side) {
        return side == Board.RED ? aiRed : aiBlack;
    }

    /** 摆谱模式：棋盘下方展开全部棋子面板。 */
    private void setEditMode(boolean on) {
        Diag.log("App", "setEditMode(" + on + ") 被调用");
        editMode = on;
        styleIcon(editHolder, on);
        setPaletteVisible(on);
        if (on) {
            paletteLabel.setText("点下面的棋子选中，再点棋盘落子；点已有棋子＝删除");
            status.setText("摆谱：摆好后点「分析」看评分，或开红/黑电脑让它自己下");
        } else {
            resetHistoryFromBoard();
        }
        updateStatus(null);
    }

    /** 红/黑电脑：AI 接管某一方。 */
    private void setAi(boolean red, boolean on) {
        if (red) { aiRed = on; styleIcon(redAiHolder, on); }
        else { aiBlack = on; styleIcon(blackAiHolder, on); }
        toast((red ? "红方" : "黑方") + (on ? " 由电脑接管" : " 改回手动"));
        if (on) {
            // ★ 2026-09-22 用户定的规矩：**开 AI 走子就关掉分析面板**。
            //   三档抢同一台引擎，同时开着就是互相打断（AI 那边每 350ms 递归下一手，
            //   分析那边 12 秒长搜，来回 stop 谁都出不来结果）。
            //   关掉分析后：红黑条和箭头跟着 AI 走子的结果走，候选列表不再更新（面板已关）。
            if (analysisOn) {
                closeAnalysisQuietly();          // ★ 2026-09-25：统一入口（补 styleIcon，原来漏了所以"框不消失"）
                toast("已关掉分析面板（AI 对弈优先）");
            }
            maybeAiTurn();
        }
    }

    /** 轮到被接管的某一方就自动走子。 */
    private void maybeAiTurn() {
        if (editMode || gameOver || thinking) return;
        if (!aiPlays(board.sideToMove)) return;
        if (!engineReady) {
            // ★ 2026-09-22：原文案是"首次约 10 秒"——**实测是错的**，冷启动全程 418~553ms
            //   （准备权重 1~4ms + 起进程&uci握手 228~283ms + 加载 nnue 186~269ms）。
            //   那句"10 秒"从来没实测过，白白吓用户。现在照实说。
            toast("皮卡鱼引擎启动中（约半秒）…");
            updateStatus("皮卡鱼引擎启动中…");
            final int side = board.sideToMove;
            new Thread(new Runnable() { public void run() {
                if (engine == null) engine = new Engine(MainActivity.this);
                final boolean ok = engine.start();
                engineReady = ok;
                ui.post(new Runnable() { public void run() {
                    if (ok) toast("皮卡鱼加载完成");
                    if (ok) startAiTurn();
                } });
            } }).start();
            return;
        }
        startAiTurn();
    }

    private void setPaletteVisible(boolean visible) {
        if (paletteBar == null) return;
        // ★★ 2026-09-25 修 Bug3（重做）：摆谱面板和 分析/局势/棋谱 共用 tabCard 这一片区域，
        //   靠 visibility 互斥。这样展开/收起时 **tabCard 的总高度不变**，
        //   底部的页签栏和那一行功能按钮完全不会动。
        paletteBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            if (analysisContent != null) analysisContent.setVisibility(View.GONE);
            if (situationContent != null) situationContent.setVisibility(View.GONE);
            if (navContent != null) navContent.setVisibility(View.GONE);
        } else {
            showTab(tab);      // 收起时把当前页签的内容显示回来
        }
    }

    private void showFenImport() {
        final EditText et = new EditText(this);
        et.setText(board.toFen());
        et.setTextSize(13f);
        et.setTextColor(Ui.TEXT);
        new AlertDialog.Builder(this)
            .setTitle("导入 FEN 局面")
            .setView(et)
            .setPositiveButton("导入", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    if (board.loadFen(et.getText().toString().trim())) {
                        boardView.setBoard(board);
                        resetHistoryFromBoard();
                        gameOver = false;
                        if (editMode) refreshNav();
                        toast("FEN 导入成功");
                    } else toast("FEN 格式不正确");
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String appVersionFull() {
        // ★ 2026-09-29：用户要求"code 标号取消" —— 界面上只显示版本名（2.0），
        //   不再拼 "(code NNN)"。versionCode 本身仍留在 manifest 里（系统升级/覆盖安装要用），
        //   只是不再给人看。
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) { return "?"; }
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
    }

    // ★ 2026-09-21：原来点红黑条右端能改「AI 出招层数」，用户说"别搞什么选择分析多少层了，默认就行"，
    //   所以 showDepthDialog() 整个删掉，层数固定用默认的 aiDepth。

    private void showAbout() {
        String diag = Diag.tail(80);
        TextView tv = new TextView(this);
        tv.setTextSize(11.5f);
        tv.setTextColor(0xFF37474F);
        tv.setTextIsSelectable(true);
        tv.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        tv.setText("象棋分析 v" + appVersionFull() + "\n"
                + "· ⚠️ 识谱注意：目前模型仅适配「天天象棋」，只保证天天象棋的识别准确率，其他象棋软件无法保证。\n"
                + "· 引擎：皮卡鱼 2026-09-06（UCI；限层 / 限时在左侧「菜单」里调）\n"
                + "· 识谱：YOLO 棋子检测（整盘一次推理）+ PP-OCR 读字\n"
                + "    训练集 by chess-we7v5：https://universe.roboflow.com/chess-we7v5/tt-nidj4\n"
                + "· 悬浮窗：投屏 / 无障碍抓屏 → 识别 → 引擎算招 → 悬浮提示\n"
                + "\n【开源许可】\n"
                + "· 引擎 Pikafish（皮卡鱼）：GNU GPL v3，可自由使用 / 分发 / 商用。\n"
                + "  本项目未修改其源码，直接使用官方预编译二进制。\n"
                + "  源码 https://github.com/official-pikafish/Pikafish\n"
                + "· ⚠️ 权重 pikafish.nnue：适用单独的 NNUE 许可，**未经授权不得商用**。\n"
                + "  本 App 未含广告 / 内购 / 收费，属免费使用。\n"
                + "· 识谱模型：YOLO（训练集 by chess-we7v5）、PP-OCR 读字（Apache-2.0）\n"
                + "· ONNX Runtime：MIT\n\n"
                + "· 图片资源：由 Qwen Image 3.0 Pro 生成\n"
                + "· 代码协助：GPT-6-Sol、DeepSeek-V4-Flash\n"
                + "· 作者：dmpkin\n\n"
                + "· 抓屏：点击「悬浮窗」默认使用投屏授权；长按可选无障碍截图（Android 11+），正常使用无需开启无障碍。\n"
                + "\n【诊断日志】\n" + diag);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("关于 / 诊断")
            .setView(sv)
            .setPositiveButton("知道了", null)
            .setNeutralButton("清空日志", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { Diag.clear(); toast("日志已清空"); }
            })
            .show();
    }

    /**
     * 开启悬浮窗识谱：先确认悬浮窗权限，再进录屏授权。每步都有提示，不会"点了没反应"。
     * @param accShot true = 走无障碍截屏（不用投屏授权）；false = 投屏（默认）
     */
    private void startFloatScan(boolean accShot) {
        android.util.Log.i("xiangqi-ocr", "startFloatScan: acc=" + accShot
                + " overlay=" + Bridge.canDrawOverlay(this) + " sdk=" + android.os.Build.VERSION.SDK_INT);
        if (!Bridge.canDrawOverlay(this)) {
            new AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("实时识谱要在别的 App 上面显示小棋盘和提示箭头，请先允许「显示在其他应用上层」。\n\n打开设置后找到「中国象棋AI」→ 允许「显示在其他应用上层 / 悬浮窗」，再回来点一次「悬浮窗」。")
                .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { openOverlaySettings(); }
                })
                .setNegativeButton("取消", null)
                .show();
            return;
        }
        if (accShot) {
            // 无障碍截屏要确认服务真的在跑，否则服务端也会退回投屏，不如在这里就说清楚
            if (!AutoService.accShotReady()) {
                toast(android.os.Build.VERSION.SDK_INT < 30
                        ? "无障碍截屏需要 Android 11 及以上，这次用投屏"
                        : "无障碍服务没开：请到「设置 → 无障碍 → 中国象棋AI」里打开，这次先用投屏");
                accShot = false;
            } else {
                toast("正在用无障碍截屏启动（无投屏弹窗）…");
            }
        } else {
            toast("准备录屏授权…");
        }
        try {
            Intent si = new Intent(this, ShotActivity.class);
            si.putExtra(ShotService.EXTRA_ACC_SHOT, accShot);
            startActivity(si);
        } catch (Throwable t) {
            toast("打不开悬浮窗授权页：" + t);
            android.util.Log.e("xiangqi-ocr", "start ShotActivity failed: " + t);
        }
    }

    /** 跳转悬浮窗权限设置（多套意图兜底，适配国产 ROM）。 */
    private void openOverlaySettings() {
        Intent[] tries = new Intent[] {
            new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", android.net.Uri.parse("package:" + getPackageName())),
            new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION"),
            new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())),
            new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        };
        for (int i = 0; i < tries.length; i++) {
            try {
                tries[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(tries[i]);
                return;
            } catch (Throwable ignored) { }
        }
        toast("打不开设置，请手动到「设置 → 应用 → 中国象棋AI → 显示在其他应用上层」里允许");
    }

    // ================= 对局 =================

    private void newGame() { startNewGame(); }

    private void startNewGame() {
        board = new Board();
        boardView.setBoard(board);
        boardView.setHint(-1, -1);
        resetHistoryFromBoard();
        gameOver = false;
        thinking = false;
        // ★ 用户要求："初始化进去的时候红黑条那栏是空白的，我希望初始化就有东西"。
        //   原来是 evalBar.clear()（hasData=false → 只画一条米色底 = 看着像没内容）。
        //   现在新局直接给 0 分：左红右黑各一半 + "均势 0 分"，引擎一出分马上盖掉。
        evalBar.setScore(0, AI_DISPLAY_DEPTH);
        updateStatus(null);
        startAnalysis();
        maybeAiTurn();
    }

    /** 以当前棋盘为起点重开棋谱记录（新局/摆谱/导入 FEN/识图 后调用）。 */
    private void resetHistoryFromBoard() {
        startFen = board.toFen();
        history.clear();
        notation.clear();
        ply = 0;
        // ★ 2026-09-23：局面快照同步重置，只留"起始局面"这一个点。
        fenAtPly.clear();
        fenAtPly.add(startFen);
        refreshNav();
        resetChart();
    }

    private void updateStatus(String extra) {
        if (extra != null) { status.setText(extra); return; }
        if (gameOver) return;
        String turn = board.sideToMove == Board.RED ? "红方" : "黑方";
        String s;
        if (editMode) s = "摆谱中";
        else s = thinking ? "电脑思考中…" : ("轮到" + turn + (aiPlays(board.sideToMove) ? "（电脑）" : "（你）"));
        if (board.inCheck(board.sideToMove)) s = "将军！ " + s;
        status.setText(s);
    }

    @Override
    public void onSquareTapped(int sq) {
        if (editMode) {
            if (editErase || board.cells[sq] != Board.EMPTY) board.cells[sq] = Board.EMPTY;
            else board.cells[sq] = editPiece;
            board.kingSq[0] = -1; board.kingSq[1] = -1;
            for (int i = 0; i < 90; i++) if (Board.type(board.cells[i]) == Board.KING) board.kingSq[Board.isRed(board.cells[i]) ? 0 : 1] = i;
            boardView.setBoard(board);
            boardView.setHint(-1, -1);
            resetHistoryFromBoard();
            return;
        }
        {
            if (gameOver || thinking) return;
            if (aiPlays(board.sideToMove)) { toast("这一方已由电脑接管"); return; }
            if (ply != history.size()) { jumpTo(history.size()); return; }
        }
        if (ply != history.size()) jumpTo(history.size());
        int p = board.cells[sq];
        if (p != Board.EMPTY && Board.sideOf(p) == board.sideToMove) {
            ArrayList<Integer> legal = board.generateLegalMoves(board.sideToMove);
            ArrayList<Integer> targets = new ArrayList<Integer>();
            for (int i = 0; i < legal.size(); i++) if (Board.moveFrom(legal.get(i)) == sq) targets.add(Board.moveTo(legal.get(i)));
            boardView.select(sq, targets);
            return;
        }
        int from = boardView.getSelected();
        if (from < 0) return;
        if (!doMove(from, sq)) boardView.clearSelection();
    }

    private boolean doMove(int from, int to) {
        ArrayList<Integer> legal = board.generateLegalMoves(board.sideToMove);
        int move = -1;
        for (int i = 0; i < legal.size(); i++) {
            if (Board.moveFrom(legal.get(i)) == from && Board.moveTo(legal.get(i)) == to) { move = legal.get(i); break; }
        }
        if (move < 0) return false;
        applyMove(move);
        boardView.clearSelection();
        checkGameEnd();
        startAnalysis();
        maybeAiTurn();
        return true;
    }

    private void applyMove(int move) {
        String text = Notation.move(board, move);
        int cap = board.makeMove(move);
        history.add(new int[] { move, cap });
        notation.add(text);
        ply = history.size();
        // ★ 2026-09-23：把"这一手之后的局面"存成快照（index = ply）。
        //   先把超出 ply 的旧快照丢掉 —— 悔棋后重走一步（或换了个着法）时，
        //   后面那些快照已经作废，留着会让 boardAtPly 拿到错的局面。
        while (fenAtPly.size() > ply) fenAtPly.remove(fenAtPly.size() - 1);
        fenAtPly.add(board.toFen());
        boardView.setLastMove(Board.moveFrom(move), Board.moveTo(move));
        boardView.animateMove(Board.moveFrom(move), Board.moveTo(move));
        boardView.setHint(-1, -1);
        refreshNav();
        onMoveApplied();
    }

    private boolean checkGameEnd() {
        if (board.isLoss(board.sideToMove)) {
            gameOver = true;
            int winner = Board.opponent(board.sideToMove);
            String who = "（" + (winner == Board.RED ? "红方" : "黑方") + "胜）";
            updateStatus((board.inCheck(board.sideToMove) ? "绝杀！ " : "困毙！ ") + who);
            toast(who);
            return true;
        }
        if (board.inCheck(board.sideToMove)) updateStatus(null);
        return false;
    }

    /** 跳到第 k 手后的局面（0 = 起始局面）。 */
    private void jumpTo(int k) {
        if (startFen == null) startFen = new Board().toFen();
        if (k < 0) k = 0;
        if (k > history.size()) k = history.size();
        Board nb = new Board();
        nb.loadFen(startFen);
        for (int i = 0; i < k; i++) nb.makeMove(history.get(i)[0]);
        board = nb;
        ply = k;
        boardView.setBoard(board);
        boardView.setHint(-1, -1);
        if (k == 0) boardView.setLastMove(-1, -1);
        else boardView.setLastMove(Board.moveFrom(history.get(k - 1)[0]), Board.moveTo(history.get(k - 1)[0]));
        refreshNav();
        syncChart();
        startAnalysis();
        updateStatus(null);
    }

    // ================= 棋谱（每手一行，点了跳转复盘） =================

    private void refreshNav() {
        if (navRows == null) return;
        navRows.removeAllViews();
        if (notation.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("  还没有走子。走子后可在这里点任意一步跳转复盘。");
            t.setTextColor(Ui.SUB);
            t.setTextSize(12.5f);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), 0, 0);
            navRows.addView(t);
        } else {
            for (int i = 0; i < notation.size(); i += 2) {
                final int endPly = Math.min(i + 2, notation.size());
                String redMv = notation.get(i);
                String blackMv = i + 1 < notation.size() ? notation.get(i + 1) : "";
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                boolean active = (ply >= i + 1 && ply <= endPly);
                row.setBackground(Ui.round(active ? Ui.TAB_ACTIVE_BG : 0x00000000, 4f, this, 0));
                row.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
                row.addView(cell(String.valueOf(i / 2 + 1), 0.5f, false, Ui.SUB));
                row.addView(cell(redMv, 1.2f, false, active ? Ui.TAB_ACTIVE_TEXT : Ui.TEXT));
                row.addView(cell(blackMv, 1.2f, false, active ? Ui.TAB_ACTIVE_TEXT : Ui.TEXT));
                row.addView(cell("▶", 0.4f, false, Ui.SUB));
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { jumpTo(endPly); }
                });
                navRows.addView(row);
                View line = new View(this);
                line.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
                navRows.addView(line, lp);
            }
        }
        navInfo.setText("当前：第 " + ply + " / " + history.size() + " 手（点任意一行跳转复盘）");
    }

    // 开局库可视化界面已删除（用户几乎不用）：buildBookTab / refreshBook / OpeningBook 全部移除。


    // ================= 实时分析（逐层加深） =================
    // ★ 2026-09-22：ANALYSIS_MAIN_MS 已挪到上面字段区（合并引擎时统一管理三档参数），这里只留路数。
    /** 同时给几路候选（原来是 3，用户要"多分析几步"，加到 5）。 */
    private static final int ANALYSIS_MULTIPV = 5;
    // 原来这里还有 ANALYSIS_REPLY_MS / ANALYSIS_PV_SHOW / ANALYSIS_REPLY_PV_SHOW 三个常量，
    // 分别是"单独搜应手的时长"和"主变/应手列几手"—— 那两条文字行和那趟慢搜都删了，常量一起删。

    private void startAnalysis() {
        // 没开分析（默认）或界面不在前台，就什么都不做 —— 面板保持空白
        if (!analysisOn || !pageActive) return;
        // AI 思考中不分析：同一台引擎正在出招，抢过来就是互相打断（合并前是靠"两台引擎互抢 CPU"，
        // 现在只有一个进程，抢的后果更严重 —— 会导致两边都拿不到结果）。
        // ★ 2026-09-25：但不是"什么都不做"—— 记下来，等 AI 那趟结束后补触发（见 afterThinkingEnds）。
        if (thinking) { pendingAnalysis = true; return; }
        pendingAnalysis = false;      // 正常启动了，清掉挂起标记
        final int gen = ++analysisGen;
        final Board snapshot = board.copy();
        if (engine != null) engine.stopAnalysis();
        new Thread(new Runnable() {
            public void run() {
                if (!ensureEngineReady()) {
                    final String err = engine.getError();
                    ui.post(new Runnable() { public void run() {
                        if (gen != analysisGen) return;
                        clearAnalysisRows();
                        addSubRow(analysisRows, "分析引擎启动失败：" + err, 0xFFC62828);
                    } });
                    return;
                }
                if (gen != analysisGen) return;      // 等引擎启动期间局面又变了
                useAnalysisProfile();
                // ★★★ 2026-09-29：局面的合法性检查挪进引擎侧（`positionAndSync`），
                //   非法时 `analyzeStream` 会"0 路 + getError()=原因"回调回来。
                //   这里在回调里把原因显示成人话 —— 否则界面会一直停在 refreshAnalysis 的
                //   「等待引擎…」，用户看到的就是"识图后点分析永远加载不出来"。
                engine.analyzeStream(snapshot, ANALYSIS_MAIN_MS, ANALYSIS_MULTIPV, 0, new Engine.StreamListener() {
                    public void onUpdate(final List<Engine.Line> lines, final int depth) {
                        ui.post(new Runnable() {
                            public void run() {
                                if (gen != analysisGen) return;
                                // 非法局面：引擎没跑，error 里带着原因
                                if (lines.isEmpty() && depth == 0) {
                                    final String why = engine.getError();
                                    if (why != null) {
                                        clearAnalysisRows();
                                        addSubRow(analysisRows, "这个局面引擎用不了：" + why, 0xFFC62828);
                                        addSubRow(analysisRows, "多半是识图认错了子。请点格子手工校正，或点「初始局面」重来。", Ui.SUB);
                                        return;
                                    }
                                }
                                analysis = lines;
                                analysisDepth = depth;
                                if (depth > 0) lastEngineDepth = depth;
                                refreshAnalysis(snapshot);
                            }
                        });
                    }
                });
                // ★ 这里以前还会"主搜索跑完后再单独搜一趟子局面"来求敌方应手 —— 那一趟要等主搜索
                //   12 秒跑完才开始、再想 2.5 秒，所以棋盘上的「敌」箭头要 14 秒才出来（用户反馈"太慢"）。
                //   现在直接取主变第 2 手：同一趟深搜里已经算出来了，depth 2 就回调 → 几百毫秒就出箭头，
                //   而且 12 秒深搜给出的应手本来就比那趟 2.5 秒的浅搜更准。多出来的那趟整趟删掉。
            }
        }).start();
    }

    private void clearAnalysis() {
        analysisGen++;
        if (engine != null) engine.stopAnalysis();
        analysis = new ArrayList<Engine.Line>();
        analysisDepth = 0;
        clearAnalysisRows();
        if (boardView != null) boardView.setArrows(null);
    }

    /** 胜率：Lichess 同款映射（由 centipawn 换算成红方胜率百分比）。 */
    private static double winRate(int cpRed) {
        double pct = 50.0 + 50.0 * (2.0 / (1.0 + Math.exp(-0.00368208 * cpRed)) - 1.0);
        return Math.max(0, Math.min(100, pct));
    }

    /** 缩进的小字行（现在只有"分析中…/等待引擎…"和分组标题用它）。 */
    private void addSubRow(LinearLayout box, String text, int color) {
        if (box == null) return;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(11.5f);
        t.setSingleLine(false);
        t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        box.addView(t);
    }

    /** 箭头的三种颜色：第一优＝蓝，第二优＝紫，敌方应手＝橙红。 */
    private static final int ARROW_C1 = 0xFF1565C0, ARROW_C2 = 0xFF7B1FA2, ARROW_C3 = 0xFFE64A19;

    private void refreshAnalysis(Board snapshot) {
        if (analysisRows == null) return;
        analysisRows.removeAllViews();
        boolean redTurn = snapshot.sideToMove == Board.RED;
        ArrayList<BoardView.Arrow> arrows = new ArrayList<BoardView.Arrow>();
        if (analysis.isEmpty()) {
            addSubRow(analysisRows, analysisDepth > 0 ? ("分析中… d" + analysisDepth) : "等待引擎…", Ui.SUB);
        }
        for (int i = 0; i < analysis.size(); i++) {
            Engine.Line l = analysis.get(i);
            int cpRed = redTurn ? l.score : -l.score;
            int mv = Engine.firstMoveOf(l.pv);
            String mvText = mv > 0 ? Notation.move(snapshot, mv) : "?";
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (i == 0) row.setBackground(Ui.round(Ui.TAB_ACTIVE_BG, 4f, this, 0));
            row.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
            row.addView(cell((i + 1) + ". " + mvText, 2f, i == 0, i == 0 ? Ui.TAB_ACTIVE_TEXT : Ui.TEXT));
            row.addView(cell(String.format("%.0f%%", winRate(cpRed)), 0.85f, false, i == 0 ? Ui.TAB_ACTIVE_TEXT : Ui.SUB));
            row.addView(cell(l.mate != 0 ? ("杀" + (l.mate > 0 ? "+" : "-") + Math.abs(l.mate)) : String.format("%+.2f", cpRed / 100.0), 0.85f, false, i == 0 ? Ui.TAB_ACTIVE_TEXT : Ui.SUB));
            row.addView(cell("d" + l.depth, 0.7f, false, Ui.SUB));
            if (i == 0) {
                analysisRows.addView(row);
                // ★ 原来这里还有两行：「主变： …」和「对方最优应手： …」/「应手后续： …」。
                //   用户说"主变应手这一系列没意义"，整串删掉了。应手只保留棋盘上那支「敌」箭头。
            } else {
                if (i == 1) {
                    View line = new View(this);
                    line.setBackgroundColor(Ui.LINE);
                    analysisRows.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
                    // ★ 原来这里还有一行小字「备选招法（棋盘上只画前两路）」，用户要求去掉。
                }
                analysisRows.addView(row);
            }
        }
        // ★ 棋盘上的箭头：①第一优 ②第二优 ③敌方最可能应手。
        //   应手＝主变第 2 手（同一趟深搜的结论，depth≥2 就有 → 几百毫秒出箭头，不再等那趟慢搜）。
        int mv1 = analysis.isEmpty() ? -1 : Engine.firstMoveOf(analysis.get(0).pv);
        int mv2 = analysis.size() > 1 ? Engine.firstMoveOf(analysis.get(1).pv) : -1;
        if (mv1 > 0) arrows.add(new BoardView.Arrow(mv1, ARROW_C1, "1"));
        if (mv2 > 0) arrows.add(new BoardView.Arrow(mv2, ARROW_C2, "2"));
        int mv3 = replyMoveFromPv();
        if (mv3 > 0) arrows.add(new BoardView.Arrow(mv3, ARROW_C3, "敌"));
        // 只在"分析的就是当前局面"时才画；翻了棋谱/摆谱中就不要误导
        boardView.setArrows(snapshot.toFen().equals(board.toFen()) && !arrows.isEmpty() ? arrows : null);
        // 只在"换了局面"时滚回顶部：同一局面逐层加深时不碰滚动位置（否则会把用户的手动滚动顶掉）
        final String fenNow = snapshot.toFen();
        if (analysisScroll != null && !fenNow.equals(analysisScrollFen)) {
            analysisScrollFen = fenNow;
            analysisScroll.post(new Runnable() { public void run() { analysisScroll.fullScroll(View.FOCUS_UP); } });
        }
        Engine.Line l0 = analysis.isEmpty() ? null : analysis.get(0);
        if (l0 != null) {
            int cpRed = redTurn ? l0.score : -l0.score;
            evalBar.setScore(cpRed, lastEngineDepth);
            // 实时分析的结果顺手记进局势图：只有"分析的就是当前局面"时这一手才算数
            // （翻了棋谱/摆谱中，分析的是别的局面，记到当前手上就错了）
            if (snapshot.toFen().equals(board.toFen())) {
                int cp = cpRed;
                if (cp > 20000) cp = 3000; else if (cp < -20000) cp = -3000;
                recordChart(ply, cp);
            }
        }
    }

    /**
     * 「我走这个最优招之后，对方最可能的应手」= 主变里的第 2 手。
     * ★ 这是**同一趟深搜**里的结论，depth 到 2 就回调了 → 箭头几百毫秒就出来。
     * 以前是等主搜索 12 秒跑完再单独搜 2.5 秒，所以要 14 秒才见箭头（用户反馈"太慢"）；
     * 而且 12 秒那趟给出的应手本来就更准，那趟浅搜纯属白等。
     * 返回 0 / -1 表示还没有（主变太短或还没算到）。
     */
    private int replyMoveFromPv() {
        if (analysis.isEmpty()) return -1;
        String pv = analysis.get(0).pv;
        if (pv == null || pv.trim().isEmpty()) return -1;
        String[] ms = pv.trim().split("\\s+");
        return ms.length < 2 ? -1 : Engine.moveOf(ms[1]);
    }

    // ================= 局势图（每一步的分数连成一条线） =================

    /** 重开一局 / 摆谱 / 导入：把局势图清空，只留"开局"这一个待算的点。 */
    private void resetChart() {
        deepScanGen++;                  // 正在回算的那趟作废
        deepScanning = false;
        deepScanPly = -1;
        chartScores.clear();
        chartScores.add(EvalChart.UNKNOWN);
        if (evalChart != null) evalChart.setStatus("");
        refreshChart();
    }

    /** 手数变了 → 把点数补齐到"手数 + 1"（只补不删，悔棋来回翻不丢已经算过的分数）。 */
    private void syncChart() {
        while (chartScores.size() < history.size() + 1) chartScores.add(EvalChart.UNKNOWN);
        refreshChart();
    }

    /** 刚走了一步：这一手之后的旧分数作废（后面可能换成完全不同的着法），先丢掉。 */
    private void onMoveApplied() {
        while (chartScores.size() > ply) chartScores.remove(chartScores.size() - 1);
        syncChart();
    }

    /** 实时分析出结果时，把当前这一手的分数记进局势图。 */
    private void recordChart(int idx, int cp) {
        if (idx < 0) return;
        while (chartScores.size() <= idx) chartScores.add(EvalChart.UNKNOWN);
        chartScores.set(idx, cp);
        refreshChart();
    }

    private void refreshChart() {
        if (evalChart == null) return;
        evalChart.setView(history.size() + 1, ply);
    }

    /** 第 k 手之后的局面（0 = 起始局面）。优先用存好的快照，拿不到才从着法重推。 */
    private Board boardAtPly(int k) {
        // ★ 2026-09-23（用户要求）：优先用**局面快照** —— 最可靠，也不怕着法记录有偏差。
        if (k >= 0 && k < fenAtPly.size()) {
            Board snap = new Board();
            if (snap.loadFen(fenAtPly.get(k))) return snap;
        }
        // 兜底：从起始局面 + 着法重推（快照缺失时才走这里）
        if (startFen == null) startFen = new Board().toFen();
        Board nb = new Board();
        nb.loadFen(startFen);
        for (int i = 0; i < k && i < history.size(); i++) nb.makeMove(history.get(i)[0]);
        return nb;
    }

    /**
     * 「逐手回算」：从开局到当前，每一手都问一遍引擎，把分数填进局势图。
     *
     * ★★ 2026-09-22 重写。原来这里开的是**第三台引擎**（`chartEngine`），理由是"那台分析引擎
     *   正被 12 秒长搜占着，共用就得排队"。用户点破了这个设计："局势图不是分析的子选项吗？
     *   直接记录分析面板的分数不行吗？" —— 现在改成：
     *     · 就一台引擎（`engine`），三个档位共用
     *     · **点这个按钮时强制关掉分析面板和 AI 走子**（见下面开头那段），保证引擎空闲
     *     · 逐手回算期间**边跑边把结果显示在棋盘上**（分数条 + 最佳着法箭头），
     *       所以用户能看见它走到哪一手了、每手什么评分，而不是盯着一根空进度条
     *     · 每手想 0.6 秒 / 12 层即收手；算完停在最后一手的局面上
     *   点第二次＝中断（停在当前算到的那一手）。
     */
    private void scanWholeGame() {
        if (history.isEmpty()) { toast("还没走子，没什么可回算的"); return; }
        if (deepScanning) { deepScanGen++; deepScanning = false; refreshChart(); toast("已停止逐手回算"); return; }
        // ★ 用户要求：点这个就强制关掉别的模式 —— 三档抢同一台引擎，不互斥就是互相打断
        if (aiRed || aiBlack) {
            aiRed = false; aiBlack = false;
            styleIcon(redAiHolder, false); styleIcon(blackAiHolder, false);
            thinking = false;
            toast("已关掉 AI 走子，专心逐手回算");
        }
        if (analysisOn) {
            closeAnalysisQuietly();              // ★ 2026-09-25：同上，补 styleIcon
        }
        // ★★ 2026-09-23 修 bug：原来这两行顺序反了 ——
        //   先 `final int gen = ++deepScanGen;` 再 `resetChart()`，
        //   而 resetChart() 内部还会 `deepScanGen++`、并把 `deepScanning` 置回 false。
        //   ⇒ gen 立刻过期 ⇒ 回算线程第一句 `if (gen != deepScanGen) break;` 就成立
        //   ⇒ **点「逐手回算」一步都不跑**（界面毫无反应）。
        //   正确顺序：先用 resetChart() 作废可能还在跑的那趟，再取新的 gen。
        resetChart();                                    // 干净重算，免得旧点混在里面
        final int gen = ++deepScanGen;
        deepScanning = true;
        final int total = history.size();
        new Thread(new Runnable() {
            public void run() {
                if (!ensureEngineReady()) {
                    final String err = engine.getError();
                    ui.post(new Runnable() { public void run() {
                        if (gen != deepScanGen) return;
                        deepScanning = false;
                        refreshChart();
                        toast("引擎启动失败：" + err);
                    } });
                    return;
                }
                useDeepScanProfile();
                for (int k = 0; k <= total; k++) {
                    if (gen != deepScanGen) break;
                    final int kk = k;
                    ui.post(new Runnable() { public void run() {
                        if (gen != deepScanGen || evalChart == null) return;
                        evalChart.setStatus("回算 " + kk + "/" + total);
                    } });
                    final Board b = boardAtPly(k);
                    final int side = b.sideToMove;
                    // 边跑边把"这一手"的盘面摆出来：分数条 + 最佳着法箭头（用户要能看见它在干什么）
                    final int[] mvHolder = new int[] { -1 };
                    final int[] scoreHolder = new int[] { 0 };
                    boolean ok = engine.searchDepthTimed(b, DEEP_SCAN_DEPTH, DEEP_SCAN_MS, new Engine.DepthListener() {
                        public void onDepth(final int sc, final int d) {
                            scoreHolder[0] = sc;
                            ui.post(new Runnable() { public void run() {
                                if (gen != deepScanGen) return;
                                int cp = sc;
                                if (cp > 20000) cp = 3000; else if (cp < -20000) cp = -3000;
                                evalBar.setScore(side == Board.RED ? cp : -cp, d);
                            } });
                        }
                    });
                    if (!ok) break;                      // 引擎出问题（或该局面已无着可走）
                    mvHolder[0] = engine.getTimedMove();
                    int cp = scoreHolder[0];
                    if (cp > 20000) cp = 3000;                // 杀棋分数压缩到 ±30 兵，别把图压平
                    else if (cp < -20000) cp = -3000;
                    final int cpRed = (side == Board.RED) ? cp : -cp;
                    final int mvFinal = mvHolder[0];
                    ui.post(new Runnable() { public void run() {
                        if (gen != deepScanGen) return;
                        recordChart(kk, cpRed);
                        // 把棋盘跳到这一手，并画出引擎给这手推荐的最佳着法
                        if (kk <= history.size()) {
                            ply = kk;
                            board = boardAtPly(kk);
                            boardView.setBoard(board);
                            if (kk == 0) boardView.setLastMove(-1, -1);
                            else boardView.setLastMove(Board.moveFrom(history.get(kk - 1)[0]), Board.moveTo(history.get(kk - 1)[0]));
                            boardView.setHint(-1, -1);
                            ArrayList<BoardView.Arrow> arrows = new ArrayList<BoardView.Arrow>();
                            if (mvFinal > 0) arrows.add(new BoardView.Arrow(mvFinal, ARROW_C1, "1"));
                            boardView.setArrows(arrows.isEmpty() ? null : arrows);
                            refreshNav();
                        }
                    } });
                }
                ui.post(new Runnable() { public void run() {
                    if (gen != deepScanGen) return;
                    deepScanning = false;
                    deepScanPly = history.size();
                    if (evalChart != null) evalChart.setStatus("");
                    refreshChart();
                    toast("逐手回算完成（" + total + " 手）");
                } });
            }
        }).start();
    }

    // ================= 引擎走子 =================

    private volatile boolean engineReady = false;

    // ★ 2026-09-22 删掉 `warmUpEngine()`：它原来是"一进 App 就无条件拉引擎"的唯一调用者，
    //   改成懒启动后没有任何调用点（死代码）。要做的事已由 `ensureEngineReady()` 承担。
    //   删它的直接动机：里面的文案「首次约 10 秒 / 预热中」是**没实测过的错数据**，
    //   而且"预热"这个说法本身就错 —— 引擎加载就是 418~553ms，谈不上预热。

    /**
     * ★ 2026-09-22：合并后唯一的"取得可用引擎"入口。
     * 走子、分析、深度分析都从这里拿 —— 但它们的参数档不同，所以这里只负责"确保活着"，
     * 具体档位由调用方在开跑前设（见 `useMoveProfile` / `useAnalysisProfile`）。
     * 返回 true = 引擎已就绪；false = 起不来（调用方应当把错误显示给用户）。
     * 注意：**方法本身不切档**，因为"切档"会打断正在跑的搜索。
     */
    private boolean ensureEngineReady() {
        if (engine == null) engine = new Engine(MainActivity.this);
        if (engine.isStarted()) { engineReady = true; return true; }
        Diag.log("Engine", "ensureEngineReady 起引擎: analysisOn=" + analysisOn
                + " aiRed=" + aiRed + " aiBlack=" + aiBlack + " deepScanning=" + deepScanning);
        final boolean ok = engine.start();
        engineReady = ok;
        return ok;
    }

    /** 走子档：只要一路、按用户设的限层/限时（见侧边栏「AI 执棋设定」）。 */
    private void useMoveProfile() { if (engine != null) engine.setHash(hashMb()); }
    /** ★ Hash 三个档位统一用一个值（见 userHashMb 注释）。 */
    private int hashMb() { return userHashMb < 1 ? 32 : userHashMb; }

    /** 分析档：要 5 路候选、不限深跑满 12 秒。 */
    private void useAnalysisProfile() { if (engine != null) engine.setHash(hashMb()); }

    /** 回算档：一路、限深 12、Hash 小一点（回算是扫一串局面，置换表留着快速局面也没用）。 */
    private void useDeepScanProfile() { if (engine != null) engine.setHash(hashMb()); }

    private void startAiTurn() {
        if (gameOver) return;
        // ★ 2026-09-22 加重入保护：原来只挡了 gameOver，没挡 thinking。
        //   实测出现"走子兜底时限 -> stop"反复刷屏，就是同一轮里被重复进入造成的：
        //   每次进都注册一个兜底定时器 + 对引擎发一次 stop，自己把自己打断。
        if (thinking) return;
        thinking = true;
        updateStatus("皮卡鱼思考中…");
        // ★ 2026-09-22 合并引擎后：这里不再需要"让分析引擎闭嘴"那一步 —— 现在只有一台引擎，
        //   分析那趟被下面这行的 gen++ 作废，而真正的打断由 searchDepthTimed 的 positionAndSync 完成。
        analysisGen++;
        if (engine != null) { try { engine.stopAnalysis(); } catch (Throwable ignored) { } }
        final Board snapshot = board.copy();
        new Thread(new Runnable() {
            public void run() {
                if (!ensureEngineReady()) {
                    final String err = engine.getError();
                    ui.post(new Runnable() { public void run() {
                        thinking = false;
                        updateStatus("引擎启动失败：" + err);
                        toast("皮卡鱼启动失败：" + err);
                        if (pendingAnalysis) { pendingAnalysis = false; startAnalysis(); }
                    } });
                    return;
                }
                // 走子档：**限深 12 层**（不是限时）。皮卡鱼精简版没有 Skill Level 选项，
                // 限深是唯一能压住棋力的手段；限时 300ms 实测能搜到 d20+，AI 强得没法下。
                useMoveProfile();
                final long t0 = System.currentTimeMillis();
                final int[] outScore = new int[] { 0 };
                final int[] outDepth = new int[] { 0 };
                // 兜底：万一某层搜太久（残局层数暴涨），到点强制收手出招。
                // ★ 2026-09-22 修：原来这个 Runnable 用完不取消，而且判据是 `thinking`。
                //   实测出现「走子兜底时限 -> stop」每 1.7 秒刷屏 —— 因为定时器过期后才跑、
                //   跑完又因为 thinking 还挂着让下一轮继续注册，反复对引擎发 stop，
                //   把正常搜索打断。现在：① 用独立的 `moveBudgetExpired` 标记（跑过就不再跑）；
                //   ② 搜索一结束立刻 removeCallbacks 撤掉它。
                final boolean[] moveBudgetExpired = new boolean[] { false };
                final Runnable moveBudget = new Runnable() {
                    public void run() {
                        if (moveBudgetExpired[0]) return;
                        moveBudgetExpired[0] = true;
                        if (engine != null) { Diag.log("Engine", "走子兜底时限 -> stop"); engine.stopAnalysis(); }
                    }
                };
                ui.postDelayed(moveBudget, AI_MOVE_TIMEOUT_MS + 1500);
                boolean ok;
                try {
                    // ★ 2026-09-23：两个参数都来自侧边栏「AI 执棋设定」。
                    //   aiMoveDepth = 0 → 不限层；aiMoveMs = 0 → 不限时（都由用户决定）。
                    ok = engine.searchDepthTimed(snapshot, aiMoveDepth, aiMoveMs, new Engine.DepthListener() {
                        public void onDepth(int score, int d) {
                            outScore[0] = score;
                            outDepth[0] = d;
                        }
                    });
                } finally {
                    ui.removeCallbacks(moveBudget);      // 搜索结束，撤掉兜底（别让它留在队列里再跑一次）
                }
                final long used = System.currentTimeMillis() - t0;
                if (!ok) {
                    final String err = engine.getError();
                    ui.post(new Runnable() { public void run() {
                        thinking = false;
                        updateStatus("引擎未返回着法：" + err);
                        // ★ 2026-09-25：AI 这趟失败/被打断，但用户可能正等着分析 —— 补触发
                        if (pendingAnalysis) { pendingAnalysis = false; startAnalysis(); }
                    } });
                    return;
                }
                final int mv = engine.getTimedMove();
                final int cpRed = (snapshot.sideToMove == Board.BLACK ? -outScore[0] : outScore[0]);
                final String info = "皮卡鱼 · d" + outDepth[0] + " · " + used + "ms · " + String.format("%+.2f", cpRed / 100.0);
                if (mv <= 0) {
                    final String err = engine.getError();
                    ui.post(new Runnable() { public void run() {
                        thinking = false;
                        updateStatus("引擎未返回着法：" + err);
                        // ★ 2026-09-25：AI 这趟失败/被打断，但用户可能正等着分析 —— 补触发
                        if (pendingAnalysis) { pendingAnalysis = false; startAnalysis(); }
                    } });
                    return;
                }
                ui.post(new Runnable() { public void run() {
                    if (gameOver) { thinking = false; return; }
                    // ★★ 2026-09-23：把「落子」拆成两段，让**搜索和动画并行**。
                    //   原来（串行）：落子A → 等动画300ms → 才开始搜B → 落子B，每步 450ms。
                    //   现在（并行）：落子A → 立刻搜B（动画同时在播）→ 搜完就落子B。
                    //   又经用户提点：搜索只要 ~90ms 而动画 300ms，**搜索几乎总是先完成** ——
                    //   那就没必要再等动画了，直接让它提前结束、棋子归位，一步省下 200 多毫秒。
                    //   （实测每步 450ms → 310ms → 改成跳动画后应降到 ~100ms 量级）
                    final Runnable land = new Runnable() { public void run() {
                        if (gameOver) { thinking = false; return; }
                        thinking = false;
                        pendingAnalysis = false;   // ★ AI 正常走完，下面会直接 startAnalysis，不用再"补触发"
                        // ★ 2026-09-23（用户要求）：**AI 走子也记进局势图**。
                        //   原来只有"点分析"那条路会调 recordChart()，所以不开分析时局势页永远空白。
                        //   ⚠️ 必须在 applyMove 之前记：cpRed 是"落子前局面"（第 ply 手之后）的分数，
                        //      而 applyMove 会把 ply 加一。
                        int chartCp = cpRed;
                        if (chartCp > 20000) chartCp = 3000; else if (chartCp < -20000) chartCp = -3000;
                        recordChart(ply, chartCp);
                        applyMove(mv);
                        updateStatus(null);
                        status.setText(info);
                        evalBar.setScore(cpRed, outDepth[0] > 0 ? outDepth[0] : AI_DISPLAY_DEPTH);
                        checkGameEnd();
                        startAnalysis();
                        // 落完立刻触发下一手 → 下一轮搜索与这一轮的新动画重叠
                        maybeAiTurn();
                    } };
                    // 搜索已经出结果了：上一步动画若还在播就直接掐掉（不再等它排队）
                    // 只有"搜索比动画还慢"的少数局面，动画才会自然播完，这里不用管。
                    if (boardView.isAnimating()) boardView.finishAnimNow();
                    land.run();
                } });
            }
        }).start();
    }

    private void undo() {
        if (editMode) { toast("摆谱模式请用「清空 / 初始」"); return; }
        if (thinking) { toast("引擎思考中，稍等"); return; }
        if (history.isEmpty()) { toast("没有可悔的棋"); return; }
        jumpTo(Math.max(0, Math.min(ply, history.size()) - 1));
        // 记录模式下真正删掉最后一步
        if (ply < history.size()) {
            while (history.size() > ply) history.remove(history.size() - 1);
            while (notation.size() > ply) notation.remove(notation.size() - 1);
        }
        gameOver = false;
        refreshNav();
        syncChart();
        startAnalysis();
        updateStatus("已悔棋");
    }

    private void copyFen() {
        String fen = board.toFen();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("fen", fen));
        toast("FEN 已复制：\n" + fen);
    }

    void loadRecognized(int[] cells, String source) {
        // ★★★ 2026-09-29 修「识图后点分析永远等引擎」的真根因：
        //   识别器交出来的 cells 是**屏幕原样**（屏幕上红方在上就是"红在上"），
        //   而 `Board` 的口径是**钉死的"红在下"**（行 0 = FEN 第 1 段 = 黑方底线）。
        //   这里以前**原样照抄**，少了 180° 归一 —— 于是"红在上"的图里，红方所有子
        //   （帅/仕/相/兵…）都被登记到了黑方那半场 → `Board.illegalReason` 判"红帅走出九宫"
        //   → 皮卡鱼收到非法局面 `exit(1)` → 应用反复重启引擎 → 界面永远「等待引擎…」。
        //   ⚠️ 悬浮窗那条路（`ShotService`）**一直是对的** —— 它在 :908/:1025 用
        //   `if (!DetOcr.lastRedBottom) rotCells(...)` 归一成了"红在下"。主界面漏了这一步。
        if (!DetOcr.lastRedBottom) cells = DetOcr.rotCells(cells);
        Board nb = new Board();
        nb.clear();
        int redKings = 0, blackKings = 0, placed = 0;
        for (int i = 0; i < 90; i++) {
            if (cells[i] > 0) {
                nb.cells[i] = cells[i];
                placed++;
                if (Board.type(cells[i]) == Board.KING) { if (Board.isRed(cells[i])) redKings++; else blackKings++; }
            }
        }
        nb.kingSq[0] = -1; nb.kingSq[1] = -1;
        for (int i = 0; i < 90; i++) if (Board.type(nb.cells[i]) == Board.KING) nb.kingSq[Board.isRed(nb.cells[i]) ? 0 : 1] = i;
        nb.sideToMove = Board.RED;

        board = nb;
        boardView.setBoard(board);
        // ★★★ 2026-10-01：**显示方向要跟用户屏幕上的视角一致**（这就是"检测到就换边"）。
        //
        //   `cells` 上面已经归一化成"红在下"（内部口径，喂引擎必须这样）；
        //   但**显示**不能也写死"红在下" —— 用户执黑时，天天象棋把棋盘转成"红在上"，
        //   这时界面若还画"红在下"，用户看到的就是一个上下颠倒的棋盘
        //   （表现为"己方莫名其妙变成红方"）。
        //
        //   `DetOcr.lastRedBottom` = **原图**是否"红在下"：
        //     · true  （原图红在下，用户执红）→ setFlipped(false) → 红在下，即己方在下 ✅
        //     · false （原图红在上，用户执黑）→ setFlipped(true)  → 黑在下，即己方在下 ✅
        //
        //   改之前这里是写死的 `setFlipped(false)`，导致**永远**把红方画在下方。
        boardView.setFlipped(!DetOcr.lastRedBottom);
        boardView.setHint(-1, -1);
        resetHistoryFromBoard();
        setEditMode(true);
        setPaletteVisible(true);
        toast(source + "完成：识别到 " + placed + " 个棋子" + (redKings != 1 || blackKings != 1 ? "（将帅异常，请手工校正）" : "，可切分析/对局"));
    }

    /** 日常按钮操作不弹浮层；只在皮卡鱼加载完成时提示一次。 */
    private void toast(String s) {
        if ("皮卡鱼加载完成".equals(s))
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 数一数格面里有几个子（自检日志用）。 */
    private static int countOf(int[] cells) {
        int n = 0;
        for (int i = 0; i < cells.length; i++) if (cells[i] > 0) n++;
        return n;
    }
}

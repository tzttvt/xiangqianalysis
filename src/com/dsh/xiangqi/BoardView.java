package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/** 棋盘视图：绘制棋盘与棋子，处理点击选子/落子。 */
public class BoardView extends View {

    public interface Listener {
        void onSquareTapped(int sq);
    }

    private Board board;
    private int selected = -1;
    private ArrayList<Integer> legalTargets = new ArrayList<Integer>();
    private int lastFrom = -1, lastTo = -1;
    // 走子动画
    private int animFrom = -1, animTo = -1, animPiece = 0;
    private long animStart = 0;
    private static final long ANIM_MS = 300;
    private boolean flipped = false;
    private Listener listener;

    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPiece = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPieceBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRiverText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cell, originX, originY, radius;

    /** 棋子半径，单位＝格（0.44 格 ≈ 老版画的圆）。棋盘几何和贴图缩放都靠它。 */
    private static final float PIECE_R = 0.44f;

    private static final String[] RED_CHARS = { "", "帅", "仕", "相", "马", "车", "炮", "兵" };
    private static final String[] BLACK_CHARS = { "", "将", "士", "象", "马", "车", "炮", "卒" };

    // ---------- 棋子贴图（assets/pieces/*.png） ----------
    /**
     * 棋子现在用的是实体棋子照片风格的贴图（木盘 + 刻字 + 立体倒角）。当前这套是
     * 用户提供的一张 14 子整图（qwen 生成的），由 `apkwork\onnx\_pieces_from_qwen.py`
     * 自动找盘、按「盘面水平直径 -> 198px」裁成 256x256 透明底 PNG。
     * 上一套（`Kadagaden/chess-pieces` 的 `xiangqi_gmchess_style_wood`，CC-BY-4.0，
     * 由 `_pieces_raster.py` 栅格化）备份在 `apkwork\bak\pieces_wood_20260921\`。
     *
     * 索引 = 兵种 * 2 + (红 ? 0 : 1)，兵种见 Board.KING..Board.PAWN（1..7）。
     * **加载失败就自动退回老的"圆 + 汉字"画法**（`drawPieceText`），不会白屏。
     */
    private static Bitmap[] sPieceBmp;
    private static boolean sPieceTried;
    /** 兵种 -> 贴图文件名。顺序必须跟 Board.KING(1)..Board.PAWN(7) 对齐。 */
    private static final String[] PIECE_FILE = { "", "king", "advisor", "bishop", "knight", "rook", "cannon", "pawn" };
    /**
     * 贴图里的圆盘几何（320x320，带投影版）。实测：盘**面**圆心就是图心 (159.5,159.5)、
     * 盘面（不透明圆）直径 198 → 盘半径 = 99。（含木纹侧壁的整体轮廓会往下多探十几像素、
     * 盘面下方还有一小圈投影，所以画布才留到 320。）
     * ★ 换贴图时必须保持这个比例（盘半径 : 画布半宽 = 99 : 160），否则棋子大小会变。
     * 画的时候按 `盘半径 -> radius` 反算缩放，这样棋子的相对大小跟以前一模一样
     * （radius = cell*0.44，也就是直径 0.88 格）。
     * 注意：bitmap 是**整张居中**贴到盘心上的，盘面圆心=图心，所以格子对的是盘面圆心，
     * 侧壁/投影只是往下多铺一点，不会让棋子整体上移。
     */
    private static final float BMP_HALF = 160f, BMP_DISC_R = 99f;

    /**
     * ★★ 贴图半宽 ÷ 盘面半径 = 160/99 ≈ 1.616（整张方图比盘面大出来那一圈是倒角/侧壁/投影）。
     * 但**留边不用按整张贴图**：再往外全是完全透明的角落，切掉也看不见。
     * 实测贴图里"看得见的内容"（alpha 通道 bbox）比盘面半径大：横向 1.05×、下方投影 1.333×。
     */
    private static final float SPRITE_PAD = BMP_HALF / BMP_DISC_R;
    /** 四周按"盘面半径的多少倍"留边。必须 ≥1.333（下方投影）—— 取 1.34 刚好够。
     *  ⚠️ 这个值必须和 `apkwork\onnx\_board_pad.py` 里的 `WANT` 一致：底图是按它补的木纹。 */
    private static final float SPRITE_VIS = 1.34f;
    /** 棋盘外沿余量（单位＝格）：一格棋子宽 = 2×PIECE_R 格，再乘上面那个倍数。≈1.179 */
    private static final float BOARD_EXT = 2f * PIECE_R * SPRITE_VIS;
    /** 棋盘区（含外沿余量）的高宽比：横 8 格、竖 9 格。≈1.1089 */
    private static final float BOARD_ASPECT = (9f + BOARD_EXT) / (8f + BOARD_EXT);
    private final Paint pBmpFilter = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF bmpDst = new RectF();

    /** 把贴图取到 (x,y) 为中心、盘半径 = radius 的位置（不落笔，只算矩形）。 */
    private boolean pieceBmp(int type, boolean red, float x, float y) {
        ensurePieceBitmaps();
        if (sPieceBmp == null || type < 1 || type > 7) return false;
        Bitmap b = sPieceBmp[type * 2 + (red ? 0 : 1)];
        if (b == null || b.isRecycled()) return false;
        float k = radius / BMP_DISC_R;
        float half = BMP_HALF * k;
        bmpDst.set(x - half, y - half, x + half, y + half);
        return true;
    }

    private void ensurePieceBitmaps() {
        if (sPieceTried) return;
        sPieceTried = true;
        Bitmap[] a = new Bitmap[16];
        try {
            android.content.res.AssetManager am = getContext().getAssets();
            for (int t = 1; t <= 7; t++) {
                for (int side = 0; side < 2; side++) {
                    String f = "pieces/" + (side == 0 ? "red_" : "black_") + PIECE_FILE[t] + ".png";
                    java.io.InputStream is = am.open(f);
                    a[t * 2 + side] = BitmapFactory.decodeStream(is);
                    is.close();
                }
            }
        } catch (Throwable e) {
            android.util.Log.w("xiangqi-ocr", "BoardView 棋子贴图加载失败，退回文字棋子: " + e);
        }
        sPieceBmp = a;
    }

    // ---------- 棋盘底图（assets/board.png） ----------
    /**
     * 棋盘底图，由 `apkwork\onnx\_board_from_qipan.py` 从用户给的整张棋盘图生成。
     * ★ 生成时做了两件事：① 把原图**不匀的格距**分段线性拉伸成均匀格（原图楚河那行比别行窄约 10%）；
     *   ② 画布尺寸做成正好 `(8 + 2*PIECE_R) x (9 + 2*PIECE_R)` 格。
     * 所以这里可以直接铺到 [originX-radius, originY-radius, originX+8cell+radius, originY+9cell+radius]，
     * 格线跟 `cell` 严丝合缝。
     * ★ **不要用 (0,0,getWidth(),getHeight())**：view 宽高比是 9:10，棋盘是 8.88:9.88，
     *   差 0.16%，按 view 铺会让横向格距慢慢漂，右边几列的棋子就骑到线外了。
     * 加载失败自动退回"代码画线"（下面 onDraw 的 else 分支），不会白屏。
     */
    private static Bitmap sBoardBmp;
    private static boolean sBoardTried;

    private void ensureBoardBitmap() {
        if (sBoardTried) return;
        sBoardTried = true;
        try {
            java.io.InputStream is = getContext().getAssets().open("board.png");
            sBoardBmp = BitmapFactory.decodeStream(is);
            is.close();
        } catch (Throwable e) {
            android.util.Log.w("xiangqi-ocr", "BoardView 棋盘底图加载失败，退回代码画线: " + e);
        }
    }

    /**
     * 画棋盘底图。成功返回 true，调用方就别再自己画格线/楚河/九宫/标记了。
     *
     * ★★ v7.12：**直接铺满整个 View**，不再缩到「棋盘 + 半子外沿」那个矩形里。
     *   原因：棋子贴图比盘面大一圈（`SPRITE_PAD`≈1.616），几何为此要多留边，
     *   而底图自带的边距只有「半个盘面」那么多 —— 多出来那一圈只能露 View 的底色
     *   （一块平色木底），再加上底图最外边十几个像素本来就是**浅色倒角**，
     *   看起来就是"棋盘外面又套了一圈白边"（用户原话）。
     *   现在底图已经用 `apkwork\onnx\_board_pad.py` **镜像补过木纹**，自己就是
     *   9.1792 × 10.1792 格那么大（和 `BOARD_EXT` 完全对应），所以直接铺满即可：
     *   网格位置/格子大小与 `computeGeometry()` 对齐到 1px 以内（补图脚本里已居中）。
     *   原图备份：`apkwork\bak\board_pre_pad_20260921.png`。
     */
    private boolean drawBoardBitmap(Canvas canvas) {
        ensureBoardBitmap();
        if (sBoardBmp == null || sBoardBmp.isRecycled()) return false;
        bmpDst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(sBoardBmp, null, bmpDst, pBmpFilter);
        return true;
    }

    public BoardView(Context c) { super(c); init(); }
    public BoardView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBg.setColor(Color.parseColor("#F2D9A8"));
        pLine.setColor(Color.parseColor("#8A6A3B"));
        pLine.setStrokeWidth(2f);
        pLine.setStyle(Paint.Style.STROKE);
        pPiece.setStyle(Paint.Style.STROKE);
        pPiece.setStrokeWidth(3f);
        pPieceBg.setStyle(Paint.Style.FILL);
        pPieceBg.setColor(Color.parseColor("#FFF9EC"));
        pPieceBg.setShadowLayer(4f, 1f, 2f, Color.parseColor("#55000000"));
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        pHint.setColor(Color.parseColor("#3F7D3F"));
        pHint.setStyle(Paint.Style.FILL);
        pSel.setStyle(Paint.Style.STROKE);
        pSel.setStrokeWidth(4f);
        pSel.setColor(Color.parseColor("#1565C0"));
        pRiverText.setColor(Color.parseColor("#8A6A3B"));
        pRiverText.setTextAlign(Paint.Align.CENTER);
        pRiverText.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    }

    public void setBoard(Board b) {
        animFrom = animTo = -1;
        this.board = b;
        selected = -1;
        legalTargets.clear();
        lastFrom = lastTo = -1;
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }
    public int getSelected() { return selected; }
    public void setFlipped(boolean f) { this.flipped = f; invalidate(); }
    public boolean isFlipped() { return flipped; }
    public void setLastMove(int from, int to) { lastFrom = from; lastTo = to; invalidate(); }

    /** 让最近一步棋从起点滑到终点（棋子已在目标格，动画期间改为画在插值位置）。 */
    public void animateMove(int from, int to) {
        if (board == null || to < 0 || to >= 90) return;
        animFrom = from;
        animTo = to;
        animPiece = board.cells[to];
        animStart = System.currentTimeMillis();
        invalidate();
    }

    private boolean animating() { return animFrom >= 0 && animTo >= 0 && System.currentTimeMillis() - animStart < ANIM_MS; }

    /**
     * 落子动画是否还在播。
     * ★ 2026-09-23：AI 对弈用它把「引擎搜索」和「落子动画」**并行**起来 ——
     *   原来流程是"落子 → 等动画 300ms → 才开始搜下一步"（串行，每步 450ms）。
     *   现在改成"落子后立刻开始搜下一步，搜完再看动画播完没"（并行，每步约 300ms）。
     */
    public boolean isAnimating() { return animating(); }

    /**
     * 立即结束落子动画 —— 棋子直接归位，不再播放剩余部分。
     * ★ 2026-09-23（用户方案）：AI 对弈时，如果**下一步搜索比动画还快**
     *   （实测搜索 ~90ms、动画 300ms，几乎总是更快），就没必要再等动画播完 ——
     *   直接把棋子落到目标格，立刻走下一步。这样每步耗时从"动画时长"降到"搜索时长"。
     *   只在 AI 走子那条路径上调；用户手动走子仍然正常播动画。
     */
    public void finishAnimNow() {
        if (animFrom < 0 && animTo < 0) return;
        animFrom = animTo = -1;
        invalidate();
    }

    /**
     * ★ 2026-09-23：动画播放结束的回调。
     *  AI 对弈用它替代原来写死的 `postDelayed(350ms)` —— 那个 350 是"猜"动画时长，
     *  实际 ANIM_MS = 300：猜短了会在动画中途落子（看着像跳子），猜长了白等。
     *  现在改成"动画真播完才触发"，既更快又不会错位。
     *  传 null 取消；当前没有动画在播时，回调会**立即**执行（防止漏触发把 AI 卡死）。
     */
    private Runnable animEndCallback = null;

    public void setAnimEndCallback(Runnable r) {
        if (r == null) { animEndCallback = null; return; }
        if (!animating()) { post(r); return; }
        animEndCallback = r;
    }


    /** 引擎推荐着法：在棋盘上以蓝色高亮标出（单箭头，老的调用口）。 */
    public void setHint(int from, int to) {
        if (from < 0 || to < 0) { arrows = null; }
        else {
            ArrayList<Arrow> a = new ArrayList<Arrow>();
            a.add(new Arrow(Board.makeMove(from, to), 0xFF1E88E5, ""));
            arrows = a;
        }
        invalidate();
    }

    /**
     * 棋盘上的着法箭头。可以同时画好几条，用来一次说清「第一优 / 第二优 / 敌方最优应手」。
     * move 是内部着法编码（from<<8|to），label 是箭头中间那个小圆牌上的字（"1" / "2" / "敌"）。
     */
    public static class Arrow {
        public final int move;
        public final int color;
        public final String label;
        /**
         * 编号小圆牌挂在箭头上的位置（0~1，0.5 = 正中）。
         *
         * ★ 2026-09-27：三条箭头（"1"最优 / "2"第二优 / "敌"对方应手）经常**起点相同**
         *   —— 最优招和第二优招多半是同一个子走出去的。都画在正中时最紧的一组只差 23.4px，
         *   而白圆牌直径有 20px ⇒ 边缘几乎相切，看着就是糊成一坨。
         *   实测（悬浮窗小棋盘 429px 宽 · 格距 46.7px · 牌子直径 20px）：
         *     场景                  全部 0.5     0.42 / 0.60
         *     进1 vs 进2             23.4px       31.3px   ← 最紧的一组
         *     进1 vs 进3             46.7px       59.3px
         *     马八进七 vs 马八进九    43.1px       45.4px
         *   ⇒ 错开之后最紧也留 11.3px 余量（原来只有 3.4px），稳定分得开。
         *
         * ★ 为什么**不**改成"垂直于箭头偏移"（另一条更直觉的思路）：实测在"同一个子走
         *   两个方向"的场景反而退化到 18.9px —— 两条箭头的法向量不同，偏移方向可能正好相向。
         *   "沿箭头方向错开位置"才是普适的那一个。
         */
        public final float labelT;
        public Arrow(int move, int color, String label) { this(move, color, label, 0.5f); }
        public Arrow(int move, int color, String label, float labelT) {
            this.move = move; this.color = color; this.label = label;
            this.labelT = labelT < 0.12f ? 0.12f : (labelT > 0.88f ? 0.88f : labelT);
        }
        public int from() { return Board.moveFrom(move); }
        public int to() { return Board.moveTo(move); }
    }

    private ArrayList<Arrow> arrows;

    /** 一次设置全部箭头（传 null 就是清空）。 */
    public void setArrows(ArrayList<Arrow> as) { this.arrows = as; invalidate(); }

    public void select(int sq, ArrayList<Integer> targets) { selected = sq; legalTargets = targets; invalidate(); }
    public void clearSelection() { selected = -1; legalTargets = new ArrayList<Integer>(); invalidate(); }

    /**
     * 棋盘**横着必须占满屏幕宽度**（用户反馈右边空了一条）。
     * 老写法先按「屏高 × 比例」压高度、再由高度反算宽度，一旦高度被压就必然右侧留白。
     * 现在反过来：宽度优先吃满，高度 = 宽 × `BOARD_ASPECT`，只有屏幕实在太矮才退让。
     *
     * ★ 0.52 → **0.58**（2026-09-21）：0.52 会把棋盘高度卡在 `2478×0.52 = 1288`，
     * 宽度被反算成 1159 —— **横着就没铺满**（右边多出一条 41px 的页底色）。
     * 实测这台机 `heightPixels = 2478`（已扣掉状态栏 140 + 导航栏 52），
     * 铺满 1200 宽需要的高度 = `1200 × 1.10894 ≈ 1331`，所以 0.58（= 1437）放得下还留余量。
     */
    private static final float MAX_SCREEN_RATIO = 0.58f;

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // ★ 高宽比用含余量的 BOARD_ASPECT（≈1.1061），不是裸的 10/9（1.1111）——
        //   余量算进去以后棋盘才刚好"宽铺满 + 上下棋子不被切"。
        int width = w, height = Math.round(w * BOARD_ASPECT);
        int cap = (int) (screenH * MAX_SCREEN_RATIO);
        if (cap > 0 && height > cap) {            // 屏幕矮：宁可缩一点，也别把下面的分析区挤没
            height = cap;
            width = Math.round(cap / BOARD_ASPECT);
        }
        if (h > 0 && height > h) {                 // 父容器给的高度更小就听父容器的（保持比例）
            height = h;
            width = Math.round(h / BOARD_ASPECT);
        }
        setMeasuredDimension(width, height);
    }

    private void computeGeometry() {
        float w = getWidth(), h = getHeight();
        // ★ 按「棋盘 + 棋子贴图外沿」定格距，而不是先给个百分比边距：
        //   宽要放进 8 格 + 左右各一张贴图的半宽，高要放进 9 格 + 上下各一张贴图的半宽。
        //   BOARD_EXT 已经把贴图比盘面大出来的那一圈算进去了（见它上面的注释）。
        //   这样棋盘横着刚好铺满屏幕，最外圈的棋子（连同倒角、投影）也不会被上边/下边切掉。
        float ext = BOARD_EXT;
        cell = Math.min(w / (8f + ext), h / (9f + ext));
        originX = (w - cell * 8f) / 2f;
        originY = (h - cell * 9f) / 2f;
        radius = cell * PIECE_R;
        pLine.setStrokeWidth(Math.max(2f, cell * 0.035f));
        pText.setTextSize(cell * 0.62f);
        pRiverText.setTextSize(cell * 0.52f);
    }

    private float cxOf(int c) {
        int cc = flipped ? 8 - c : c;
        return originX + cc * cell;
    }

    private float cyOf(int r) {
        int rr = flipped ? 9 - r : r;
        return originY + rr * cell;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        computeGeometry();
        canvas.drawRect(0, 0, getWidth(), getHeight(), pBg);

        float left = originX, top = originY, right = originX + cell * 8, bottom = originY + cell * 9;

        if (drawBoardBitmap(canvas)) {
            // 底图自带了格线、九宫斜线、外框、炮兵位标记、楚河汉界，就不再重复画了
        } else {
            // ---- 以下是没有底图时的"代码画棋盘"老路径 ----
            // 横线
            for (int r = 0; r < 10; r++) canvas.drawLine(left, originY + r * cell, right, originY + r * cell, pLine);
            // 竖线（中间 7 条在楚河汉界处断开）
            for (int c = 0; c < 9; c++) {
                float x = originX + c * cell;
                if (c == 0 || c == 8) canvas.drawLine(x, top, x, bottom, pLine);
                else {
                    canvas.drawLine(x, top, x, originY + 4 * cell, pLine);
                    canvas.drawLine(x, originY + 5 * cell, x, bottom, pLine);
                }
            }
            // 九宫斜线
            canvas.drawLine(originX + 3 * cell, top, originX + 5 * cell, originY + 2 * cell, pLine);
            canvas.drawLine(originX + 5 * cell, top, originX + 3 * cell, originY + 2 * cell, pLine);
            canvas.drawLine(originX + 3 * cell, originY + 7 * cell, originX + 5 * cell, bottom, pLine);
            canvas.drawLine(originX + 5 * cell, originY + 7 * cell, originX + 3 * cell, bottom, pLine);
            // 外框加粗
            Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
            frame.setStyle(Paint.Style.STROKE);
            frame.setColor(pLine.getColor());
            frame.setStrokeWidth(pLine.getStrokeWidth() * 1.8f);
            canvas.drawRect(left - cell * 0.12f, top - cell * 0.12f, right + cell * 0.12f, bottom + cell * 0.12f, frame);

            // 炮兵位标记
            drawMarkers(canvas);

            // 楚河汉界
            canvas.drawText("楚 河", originX + cell * 1.8f, originY + cell * 4.62f, pRiverText);
            canvas.drawText("汉 界", originX + cell * 6.2f, originY + cell * 4.62f, pRiverText);
        }

        // 上一步
        if (lastFrom >= 0) {
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lp.setStyle(Paint.Style.STROKE);
            lp.setStrokeWidth(Math.max(2f, cell * 0.06f));
            lp.setColor(Color.parseColor("#E65100"));
            drawCornerRing(canvas, lastFrom, lp);
            drawCornerRing(canvas, lastTo, lp);
        }

        // 棋子
        if (board != null) {
            boolean anim = animating();
            for (int sq = 0; sq < 90; sq++) {
                int p = board.cells[sq];
                if (p == Board.EMPTY) continue;
                if (anim && sq == animTo) continue;   // 动画中该子单独画在插值位置
                drawPiece(canvas, sq, p);
            }
            if (anim) {
                float t = (System.currentTimeMillis() - animStart) / (float) ANIM_MS;
                if (t > 1f) t = 1f;
                float e = 1f - (float) Math.pow(1f - t, 3);   // easeOutCubic
                float x0 = cxOf(Board.colOf(animFrom)), y0 = cyOf(Board.rowOf(animFrom));
                float x1 = cxOf(Board.colOf(animTo)), y1 = cyOf(Board.rowOf(animTo));
                float x = x0 + (x1 - x0) * e, y = y0 + (y1 - y0) * e;
                drawPieceAt(canvas, x, y, animPiece);
                postInvalidateOnAnimation();
            } else if (animFrom >= 0) {
                animFrom = animTo = -1;   // 动画结束，恢复常规绘制
                // ★ 动画刚播完 → 通知外面（AI 对弈靠这个串联下一手，不再写死延时）
                if (animEndCallback != null) {
                    Runnable r = animEndCallback;
                    animEndCallback = null;      // 只触发一次
                    post(r);
                }
            }
        }

        // 引擎推荐着法（多箭头：第一优 / 第二优 / 敌方应手）
        if (arrows != null) {
            for (int i = 0; i < arrows.size(); i++) {
                Arrow a = arrows.get(i);
                if (a == null || a.from() < 0 || a.to() < 0) continue;
                drawArrow(canvas,
                        cxOf(Board.colOf(a.from())), cyOf(Board.rowOf(a.from())),
                        cxOf(Board.colOf(a.to())), cyOf(Board.rowOf(a.to())),
                        a.color, a.label, a.labelT);
            }
        }

        // 可走点提示
        for (int i = 0; i < legalTargets.size(); i++) {
            int sq = legalTargets.get(i);
            float x = cxOf(Board.colOf(sq)), y = cyOf(Board.rowOf(sq));
            boolean capture = board != null && board.cells[sq] != Board.EMPTY;
            if (capture) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(cell * 0.07f);
                ring.setColor(Color.parseColor("#2E7D32"));
                canvas.drawCircle(x, y, radius * 1.06f, ring);
            } else {
                canvas.drawCircle(x, y, cell * 0.13f, pHint);
            }
        }

        // 选中框
        if (selected >= 0) {
            float x = cxOf(Board.colOf(selected)), y = cyOf(Board.rowOf(selected));
            canvas.drawCircle(x, y, radius * 1.12f, pSel);
        }
    }

    /**
     * 画一条着法箭头：线从**源棋子边缘**起，到**目标棋子边缘**收，箭头三角落在目标子外沿
     * —— 这样既看得清方向，又不会把两头的棋子盖住。箭头中间挂一个小圆牌显示编号（1/2/敌）。
     * ★ 用户反馈"箭头有点短"：起点从 1.02R 收到 0.50R、箭头尖从 1.10R 收到 0.90R，
     *   可见长度由 `len-2.12R` 变成 `len-1.40R`（走一格的兵/卒原本只剩 0.15R，几乎看不见，
     *   现在有 0.87R）。同时给三角头加"不超过整条 55%"的上限，短箭头时头自动收小、不会把线吃掉。
     */
    private void drawArrow(Canvas canvas, float fx, float fy, float tx, float ty, int color, String label, float labelT) {
        float dx = tx - fx, dy = ty - fy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        float ux = dx / len, uy = dy / len;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(3f, cell * 0.10f));
        float head = Math.max(cell * 0.30f, radius * 0.85f);
        float sx = fx + ux * radius * 0.50f, sy = fy + uy * radius * 0.50f;   // 起点：靠源子中心
        float ex = tx - ux * radius * 0.90f, ey = ty - uy * radius * 0.90f;   // 终点：贴目标子边
        float vis = (ex - sx) * ux + (ey - sy) * uy;
        if (vis < radius * 0.35f) {                                           // 实在太短就退回整段
            sx = fx; sy = fy; ex = tx; ey = ty; vis = len;
        }
        head = Math.min(head, vis * 0.55f);
        canvas.drawLine(sx, sy, ex - ux * head * 0.7f, ey - uy * head * 0.7f, p);
        Path tri = new Path();
        float w = head * 0.52f;
        tri.moveTo(ex, ey);
        tri.lineTo(ex - ux * head - uy * w, ey - uy * head + ux * w);
        tri.lineTo(ex - ux * head + uy * w, ey - uy * head - ux * w);
        tri.close();
        p.setStyle(Paint.Style.FILL);
        canvas.drawPath(tri, p);
        if (label == null || label.length() == 0) return;
        // 编号小圆牌：白底 + 彩边 + 彩字，压在箭头中段（那里一般是空格交叉点，不挡子）
        float lx = sx + (ex - sx) * labelT, ly = sy + (ey - sy) * labelT;
        float lr = Math.max(cell * 0.15f, 10f);
        Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        chip.setColor(0xF2FFFFFF);
        canvas.drawCircle(lx, ly, lr, chip);
        chip.setColor(color);
        chip.setStyle(Paint.Style.STROKE);
        chip.setStrokeWidth(Math.max(2f, cell * 0.035f));
        canvas.drawCircle(lx, ly, lr, chip);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(cell * 0.24f);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint.FontMetrics fm = tp.getFontMetrics();
        canvas.drawText(label, lx, ly - (fm.ascent + fm.descent) / 2f, tp);
    }

    private void drawCornerRing(Canvas canvas, int sq, Paint paint) {        float x = cxOf(Board.colOf(sq)), y = cyOf(Board.rowOf(sq));
        float r = radius * 1.02f;
        RectF oval = new RectF(x - r, y - r, x + r, y + r);
        canvas.drawArc(oval, 40, 60, false, paint);
        canvas.drawArc(oval, 130, 60, false, paint);
        canvas.drawArc(oval, 220, 60, false, paint);
        canvas.drawArc(oval, 310, 60, false, paint);
    }

    private void drawMarkers(Canvas canvas) {
        int[][] pts = { { 2, 1 }, { 2, 7 }, { 7, 1 }, { 7, 7 }, { 3, 0 }, { 3, 2 }, { 3, 4 }, { 3, 6 }, { 3, 8 },
                        { 6, 0 }, { 6, 2 }, { 6, 4 }, { 6, 6 }, { 6, 8 } };
        float d = cell * 0.10f, len = cell * 0.16f;
        for (int i = 0; i < pts.length; i++) {
            float x = originX + pts[i][1] * cell, y = originY + pts[i][0] * cell;
            boolean leftEdge = pts[i][1] == 0, rightEdge = pts[i][1] == 8;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    if (leftEdge && sx < 0) continue;
                    if (rightEdge && sx > 0) continue;
                    float px = x + sx * d, py = y + sy * d;
                    canvas.drawLine(px, py, px + sx * len, py, pLine);
                    canvas.drawLine(px, py, px, py + sy * len, pLine);
                }
            }
        }
    }

    private void drawPiece(Canvas canvas, int sq, int p) {
        drawPieceAt(canvas, cxOf(Board.colOf(sq)), cyOf(Board.rowOf(sq)), p);
    }

    /** 在任意位置画一颗子（走子动画也走这里）。优先用贴图，贴图没了才退回"圆 + 汉字"。 */
    private void drawPieceAt(Canvas canvas, float x, float y, int p) {
        boolean red = Board.isRed(p);
        int t = Board.type(p);
        if (pieceBmp(t, red, x, y)) {
            canvas.drawBitmap(sPieceBmp[t * 2 + (red ? 0 : 1)], null, bmpDst, pBmpFilter);
            return;
        }
        drawPieceText(canvas, x, y, t, red);
    }

    /** 老的"圆 + 汉字"画法。贴图加载不出来时的退路（留着也是 A/B 的对比基准）。 */
    private void drawPieceText(Canvas canvas, float x, float y, int t, boolean red) {
        pPieceBg.setColor(Color.parseColor("#FFF9EC"));
        canvas.drawCircle(x, y, radius, pPieceBg);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(2f, cell * 0.045f));
        ring.setColor(red ? Color.parseColor("#C62828") : Color.parseColor("#212121"));
        canvas.drawCircle(x, y, radius, ring);
        canvas.drawCircle(x, y, radius * 0.82f, ring);
        pText.setColor(red ? Color.parseColor("#C62828") : Color.parseColor("#212121"));
        String s = (t >= 1 && t <= 7) ? (red ? RED_CHARS[t] : BLACK_CHARS[t]) : "?";
        Paint.FontMetrics fm = pText.getFontMetrics();
        float baseline = y - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(s, x, baseline, pText);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
        computeGeometry();
        float x = e.getX(), y = e.getY();
        int c = Math.round((x - originX) / cell);
        int r = Math.round((y - originY) / cell);
        if (c < 0 || c > 8 || r < 0 || r > 9) return true;
        // 距离最近交叉点太远则忽略
        float dx = Math.abs(x - (originX + c * cell)), dy = Math.abs(y - (originY + r * cell));
        if (dx > cell * 0.6f || dy > cell * 0.6f) return true;
        int cc = flipped ? 8 - c : c, rr = flipped ? 9 - r : r;
        if (listener != null) listener.onSquareTapped(Board.idx(rr, cc));
        return true;
    }
}

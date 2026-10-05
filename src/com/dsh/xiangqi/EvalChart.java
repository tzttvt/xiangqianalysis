package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

/**
 * 局势折线图（用户要求："类似天天象棋的局势分析，把每一步的分数连成一条直线"）。
 *
 * 横轴 = 第几手（0 = 开局），纵轴 = 分数（红方视角，centipawn；1.00 = 一个兵）。
 * 中线之上是红方占优、之下是黑方占优，折线本身是一条连续的线；当前手用一条竖线标出来。
 * 还没算出来的手记为 {@link #UNKNOWN}，折线在那里断开（不会瞎连）。
 *
 * 纵轴量程自适应：取已知分数的最大绝对值，但不小于 400（4 个兵），
 * 免得双方接近均势时一点点小波动就被放大成大起大落。
 */
public class EvalChart extends View {

    /** 该手还没有分数。 */
    public static final int UNKNOWN = Integer.MIN_VALUE;
    /** 纵轴满刻度至少这么大（centipawn）。 */
    private static final int MIN_RANGE = 400;
    /** 量程上限：防止"杀棋"的 100000 把整张图压平。 */
    private static final int MAX_RANGE = 3000;

    private List<Integer> data;      // 外部（MainActivity）持有，这里只读
    private int viewCount = 0;       // 显示前几个点（0 = 有多少显示多少）
    private int cursorPly = 0;
    private String status = "";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EvalChart(Context c) {
        super(c);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(11f * c.getResources().getDisplayMetrics().density);
    }

    /** 绑定数据源（MainActivity 的那个 ArrayList，改了之后调 invalidate 即可）。 */
    public void bind(List<Integer> d) { data = d; invalidate(); }

    /** 显示前 count 个点（= 当前棋谱手数 + 1），游标落在第 cursor 手。 */
    public void setView(int count, int cursor) { viewCount = count; cursorPly = cursor; invalidate(); }

    /** 右上角的小字（扫描进度之类）。 */
    public void setStatus(String s) { status = s == null ? "" : s; invalidate(); }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 2 || h <= 2) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFDF8);
        cv.drawRect(0, 0, w, h, p);

        float mid = h / 2f;
        float padTop = h * 0.10f, padBot = h - h * 0.06f;   // 上下留白（给折线起伏的空间）
        float amp = (mid - padTop);                          // 满刻度到中线的像素距离

        // 中线 + 上下两个参考刻度线
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0xFFE0D5C2);
        cv.drawLine(0, mid, w, mid, p);
        p.setColor(0xFFF0E9DC);
        cv.drawLine(0, mid - amp * 0.5f, w, mid - amp * 0.5f, p);
        cv.drawLine(0, mid + amp * 0.5f, w, mid + amp * 0.5f, p);

        if (data == null || data.isEmpty()) return;

        int n = (viewCount > 0) ? Math.min(viewCount, data.size()) : data.size();
        if (n <= 0) return;
        int range = MIN_RANGE;
        for (int i = 0; i < n; i++) {
            Integer v = data.get(i);
            if (v == null || v == UNKNOWN) continue;
            range = Math.max(range, Math.abs(v));
        }
        range = Math.min(range, MAX_RANGE);

        float x0 = 4f, x1 = w - 4f;
        float span = Math.max(1, n - 1);
        float[] xs = new float[n], ys = new float[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x0 + (x1 - x0) * i / span;
            Integer v = data.get(i);
            ok[i] = (v != null && v != UNKNOWN);
            int cp = ok[i] ? Math.max(-range, Math.min(range, v)) : 0;
            ys[i] = mid - amp * (cp / (float) range);
        }
        if (n == 1) xs[0] = w / 2f;              // 只有一个点（开局）放正中，别贴在左边缘

        // ---- 折线（遇到 UNKNOWN 就断开）----
        Path line = new Path();
        Path fill = new Path();
        boolean seg = false;
        int firstOk = -1, lastOk = -1;
        for (int i = 0; i < n; i++) {
            if (!ok[i]) { seg = false; continue; }
            if (firstOk < 0) firstOk = i;
            lastOk = i;
            if (!seg) { line.moveTo(xs[i], ys[i]); fill.moveTo(xs[i], mid); fill.lineTo(xs[i], ys[i]); seg = true; }
            else { line.lineTo(xs[i], ys[i]); fill.lineTo(xs[i], ys[i]); }
        }
        if (firstOk >= 0 && lastOk > firstOk) {
            // 填充：折线 → 回到中线 → 闭合；上半截裁红、下半截裁黑灰
            fill.lineTo(xs[lastOk], mid);
            fill.close();
            p.setStyle(Paint.Style.FILL);
            cv.save(); cv.clipRect(0, 0, w, mid);
            p.setColor(0x2EC62828); cv.drawPath(fill, p); cv.restore();
            cv.save(); cv.clipRect(0, mid, w, h);
            p.setColor(0x2E4E5A66); cv.drawPath(fill, p); cv.restore();
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFF5A4636);
        cv.drawPath(line, p);

        // ---- 每一手一个小点（当前手画大一点、用强调色）----
        float dotR = 2f * getResources().getDisplayMetrics().density;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            if (!ok[i]) continue;
            p.setColor(i == cursorPly ? 0xFFE07B39 : 0xFF8C7A66);
            cv.drawCircle(xs[i], ys[i], i == cursorPly ? dotR * 1.8f : dotR, p);
        }

        // ---- 当前手竖线 ----
        if (cursorPly >= 0 && cursorPly < n && ok[cursorPly]) {
            p.setColor(0x66E07B39);
            float lw = 1.5f * getResources().getDisplayMetrics().density;
            cv.drawRect(xs[cursorPly] - lw, 0, xs[cursorPly] + lw, h, p);
        }

        // ---- 刻度数字（左端）----
        t.setColor(0xFFB3A28C);
        t.setTextAlign(Paint.Align.LEFT);
        cv.drawText("红 +" + (range / 100), 4, mid - amp + t.getTextSize(), t);
        cv.drawText("黑 +" + (range / 100), 4, mid + amp, t);

        // ---- 右上角状态 ----
        if (!status.isEmpty()) {
            t.setColor(0xFF8C7A66);
            t.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(status, w - 6, t.getTextSize() + 2, t);
        }
        // ---- 一笔数据都没有时的提示 ----
        if (firstOk < 0) {
            t.setColor(0xFFB3A28C);
            t.setTextAlign(Paint.Align.CENTER);
            cv.drawText("点「全盘分析」把每一步的分数连成线", w / 2f, mid + t.getTextSize() / 3f, t);
        }
    }
}

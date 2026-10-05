package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 局面条：红黑分色 + 条内直接显示"分"。
 * 分数采用天天象棋口径：以兵为 100，吃一个马约 +300 分（引擎 centipawn 恰好就是这个量级）。
 */
public class EvalBar extends View {

    private int score = 0;        // 红方视角（分）
    private boolean hasData = false;
    private int depth = 0;          // AI 出招层数（可点击修改）
    private int analysisDepth = 0;  // 分析引擎当前层数（只显示在分析表里）
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EvalBar(Context c) {
        super(c);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setColor(Color.WHITE);
    }

    public void setScore(int scoreRed) { setScore(scoreRed, this.depth); }

    /** 分数 + 深度：深度显示在条的右端 */
    public void setScore(int scoreRed, int depth) { this.score = scoreRed; this.depth = depth; this.hasData = true; invalidate(); }
    public void clear() { this.hasData = false; invalidate(); }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        // ★ 用户要求：分析条做成"长条方形"——原来是两端半圆的胶囊，现在一律直角。
        p.setColor(0xFFEFE6D8);
        cv.drawRect(0, 0, w, h, p);
        if (!hasData) return;
        double win = 1.0 / (1.0 + Math.pow(10, -score / 400.0));
        float redW = (float) Math.max(0.06, Math.min(0.94, win)) * w;
        p.setColor(0xFFC62828);
        cv.drawRect(0, 0, redW, h, p);
        p.setColor(0xFF4E5A66);
        cv.drawRect(redW, 0, w, h, p);
        p.setColor(0x55FFFFFF);
        cv.drawRect(w / 2f - 1, 0, w / 2f + 1, h, p);
        // 文字
        String txt;
        if (score == 0) txt = "均势 0 分";                     // 开局/未出分：别写成"黑 +0 分"
        else if (Math.abs(score) > 20000) txt = score > 0 ? "红方绝杀" : "黑方绝杀";
        else txt = (score > 0 ? "红 +" : "黑 +") + Math.abs(score) + " 分";
        t.setTextSize(h * 0.62f);
        Paint.FontMetrics fm = t.getFontMetrics();
        cv.drawText(txt, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, t);
        // ★ 2026-09-23（用户要求）：把「当前分析层数」固定在**条的最右端**显示。
        //   2026-09-21 曾按用户要求删掉过（当时是"点击改层数"的入口），现在只做**只读显示**。
        //   加半透明底衬，免得压在红/黑分色上看不清。
        if (depth > 0) {
            String dtxt = "d" + depth;
            t.setTextSize(h * 0.52f);
            float tw = t.measureText(dtxt);
            float padX = h * 0.24f;
            p.setColor(0x77000000);
            cv.drawRect(w - tw - padX * 2f, 0, w, h, p);
            t.setColor(Color.WHITE);
            Paint.FontMetrics dfm = t.getFontMetrics();
            cv.drawText(dtxt, w - padX - tw / 2f, h / 2f - (dfm.ascent + dfm.descent) / 2f, t);
        }
    }
}

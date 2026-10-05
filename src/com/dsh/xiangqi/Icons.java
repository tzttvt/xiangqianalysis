package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;

/** 用 Canvas 现场绘制的矢量图标，省去文字按钮占用的空间。 */
public class Icons {

    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();
    private static final int S = 72;   // 内部绘制尺寸

    public static Bitmap get(Context c, String key, int sizePx, int color) {
        String k = key + ":" + sizePx + ":" + color;
        Bitmap b = CACHE.get(k);
        if (b != null) return b;
        Bitmap big = draw(key, color);
        b = Bitmap.createScaledBitmap(big, sizePx, sizePx, true);
        big.recycle();
        CACHE.put(k, b);
        return b;
    }

    private static Bitmap draw(String key, int color) {
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStrokeWidth(6f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float m = 12f, e = S - 12f;

        if ("match".equals(key)) {                 // 棋盘 + 棋子
            p.setStyle(Paint.Style.STROKE);
            cv.drawRect(m, m, e, e, p);
            cv.drawLine(S / 2f, m, S / 2f, e, p);
            cv.drawLine(m, S / 2f, e, S / 2f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(S / 2f, S / 2f, 11f, p);
        } else if ("analyze".equals(key)) {        // 柱状图
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(new RectF(14, 44, 26, e), 3, 3, p);
            cv.drawRoundRect(new RectF(31, 30, 43, e), 3, 3, p);
            cv.drawRoundRect(new RectF(48, 16, 60, e), 3, 3, p);
        } else if ("edit".equals(key)) {           // 网格 + 加号（摆谱）
            p.setStyle(Paint.Style.STROKE);
            cv.drawRect(10, 10, 50, 50, p);
            cv.drawLine(30, 10, 30, 50, p);
            cv.drawLine(10, 30, 50, 30, p);
            cv.drawLine(56, 46, 56, 66, p);
            cv.drawLine(46, 56, 66, 56, p);
        } else if ("ocr".equals(key)) {            // 相机
            p.setStyle(Paint.Style.STROKE);
            cv.drawRoundRect(new RectF(10, 22, 62, 58), 8, 8, p);
            cv.drawCircle(36, 40, 12, p);
            cv.drawLine(26, 22, 32, 13, p);
            cv.drawLine(46, 22, 40, 13, p);
        } else if ("float".equals(key)) {          // 悬浮窗
            p.setStyle(Paint.Style.STROKE);
            cv.drawRoundRect(new RectF(10, 14, 62, 58), 7, 7, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(new RectF(18, 44, 40, 54), 3, 3, p);
            cv.drawCircle(24, 28, 5, p);
        } else if ("new".equals(key)) {            // 加号
            cv.drawLine(S / 2f, 16, S / 2f, 56, p);
            cv.drawLine(16, S / 2f, 56, S / 2f, p);
        } else if ("undo".equals(key)) {           // 回退箭头
            p.setStyle(Paint.Style.STROKE);
            RectF r = new RectF(18, 22, 58, 58);
            cv.drawArc(r, 180, -220, false, p);
            Path path = new Path();
            path.moveTo(18, 22); path.lineTo(14, 40); path.lineTo(32, 38);
            p.setStyle(Paint.Style.FILL);
            cv.drawPath(path, p);
        } else if ("swap".equals(key)) {           // 双向箭头
            cv.drawLine(14, 26, 56, 26, p);
            cv.drawLine(14, 46, 56, 46, p);
            Path a1 = new Path(); a1.moveTo(56, 26); a1.lineTo(44, 18); a1.lineTo(44, 34); a1.close();
            Path a2 = new Path(); a2.moveTo(14, 46); a2.lineTo(26, 38); a2.lineTo(26, 54); a2.close();
            p.setStyle(Paint.Style.FILL);
            cv.drawPath(a1, p);
            cv.drawPath(a2, p);
        } else if ("level".equals(key)) {          // 力度表（难度）
            p.setStyle(Paint.Style.STROKE);
            cv.drawArc(new RectF(14, 20, 58, 64), 180, 180, false, p);
            cv.drawLine(36, 42, 52, 26, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(36, 42, 6, p);
        } else if ("fenIn".equals(key)) {          // 文档 + 下箭头
            p.setStyle(Paint.Style.STROKE);
            cv.drawRoundRect(new RectF(14, 10, 58, 62), 6, 6, p);
            cv.drawLine(36, 22, 36, 44, p);
            cv.drawLine(27, 36, 36, 46, p);
            cv.drawLine(45, 36, 36, 46, p);
        } else if ("fenOut".equals(key)) {         // 复制
            p.setStyle(Paint.Style.STROKE);
            cv.drawRoundRect(new RectF(12, 12, 46, 46), 6, 6, p);
            cv.drawRoundRect(new RectF(26, 26, 60, 60), 6, 6, p);
        } else if ("reset".equals(key)) {          // 房子（初始局面）
            p.setStyle(Paint.Style.STROKE);
            Path roof = new Path();
            roof.moveTo(12, 36); roof.lineTo(36, 14); roof.lineTo(60, 36);
            cv.drawPath(roof, p);
            cv.drawRect(20, 36, 52, 60, p);
        } else if ("menu".equals(key)) {           // 左三点 + 右三条（☰ 变体）
            // ★ 2026-09-23（用户要求）：左边加三个点；三条杠"间隔宽一点、粗一点、短一点"。
            float[] ys = { 20f, 36f, 52f };        // 行距 16（原来最大行距 24，间距 12）
            p.setStyle(Paint.Style.FILL);
            for (float y : ys) cv.drawCircle(15f, y, 4.5f, p);   // 左侧三点
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(8f);                                // 加粗（原 6）
            for (float y : ys) cv.drawLine(34f, y, 58f, y, p);   // 右侧三条，长度压到 24（原 48）
        } else {                                   // about（i）
            p.setStyle(Paint.Style.STROKE);
            cv.drawCircle(S / 2f, S / 2f, 26, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(S / 2f, 24, 4.5f, p);
            cv.drawRoundRect(new RectF(33, 33, 39, 52), 3, 3, p);
        }
        return bmp;
    }
}

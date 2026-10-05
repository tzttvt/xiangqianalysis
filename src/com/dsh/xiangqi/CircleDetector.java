package com.dsh.xiangqi;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;

/**
 * 霍夫圆检测棋子盘（纯 Java，不依赖 OpenCV）。
 * 棋子是圆形木盘，比"靠颜色阈值找字"稳得多——字会被拆碎，圆不会。
 * 返回每个圆的 {cx, cy, r}。
 */
public class CircleDetector {

    public static ArrayList<double[]> detect(Bitmap src, int rx0, int ry0, int rx1, int ry1, double cell) {
        int W = src.getWidth(), H = src.getHeight();
        rx0 = Math.max(0, rx0); ry0 = Math.max(0, ry0);
        rx1 = Math.min(W, rx1); ry1 = Math.min(H, ry1);
        int rw = rx1 - rx0, rh = ry1 - ry0;
        if (rw < 40 || rh < 40) return null;
        int[] px = new int[rw * rh];
        src.getPixels(px, 0, rw, rx0, ry0, rw, rh);
        int[] g = new int[rw * rh];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
        }
        int rfix = Math.max(6, (int) Math.round(cell * 0.30));   // 棋子盘半径 ≈ 0.30 格（按格距定，别乱投票半径）
        int[] acc = new int[rw * rh];
        int thr = 60;
        for (int y = 1; y < rh - 1; y++) {
            int row = y * rw;
            for (int x = 1; x < rw - 1; x++) {
                int i = row + x;
                int gx = g[i + 1] - g[i - 1];
                int gy = g[i + rw] - g[i - rw];
                int mag = Math.abs(gx) + Math.abs(gy);
                if (mag < thr) continue;
                double nx = gx / (double) mag, ny = gy / (double) mag;
                for (int r = rfix - 2; r <= rfix + 2; r += 2) {
                    for (int s = -1; s <= 1; s += 2) {
                        int cx = (int) Math.round(x + s * r * nx), cy = (int) Math.round(y + s * r * ny);
                        if (cx < 0 || cy < 0 || cx >= rw || cy >= rh) continue;
                        acc[cy * rw + cx]++;
                    }
                }
            }
        }
        int amax = 0;
        for (int v : acc) if (v > amax) amax = v;
        if (amax <= 0) return null;
        int vt = Math.max(4, (int) (amax * 0.30));     // 投票阈值（相对峰值）
        int minDist = Math.max(6, (int) (cell * 0.55));
        int half = Math.max(3, minDist / 2);
        long[] keys = new long[Math.max(64, rw * rh / 64)];
        int kn = 0;
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int i = y * rw + x;
                int a = acc[i];
                if (a < vt) continue;
                boolean peak = true;
                for (int dy = -half; dy <= half && peak; dy += 2) {
                    int ny2 = y + dy;
                    if (ny2 < 0 || ny2 >= rh) continue;
                    for (int dx = -half; dx <= half; dx += 2) {
                        int nx2 = x + dx;
                        if (nx2 < 0 || nx2 >= rw) continue;
                        if (acc[ny2 * rw + nx2] > a) { peak = false; break; }
                    }
                }
                if (!peak) continue;
                if (kn >= keys.length) keys = java.util.Arrays.copyOf(keys, keys.length * 2);
                keys[kn++] = ((long) a << 32) | (i & 0xFFFFFFFFL);
            }
        }
        java.util.Arrays.sort(keys, 0, kn);            // 升序，高 32 位=票数
        ArrayList<double[]> out = new ArrayList<double[]>();
        double dd = minDist * 0.8;
        for (int q = kn - 1; q >= 0 && out.size() < 40; q--) {   // 只取票数最高的 40 个，防假圆拖慢读字
            int i = (int) (keys[q] & 0xFFFFFFFFL);
            int x = i % rw, y = i / rw;
            boolean dup = false;
            for (int z = 0; z < out.size(); z++) {
                double[] o = out.get(z);
                double dx = (x + rx0) - o[0], dy = (y + ry0) - o[1];
                if (dx * dx + dy * dy < dd * dd) { dup = true; break; }
            }
            if (dup) continue;
            out.add(new double[] { x + rx0, y + ry0, rfix });
        }
        return out;
    }
}

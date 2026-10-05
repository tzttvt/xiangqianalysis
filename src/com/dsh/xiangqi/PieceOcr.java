package com.dsh.xiangqi;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;

/**
 * 不依赖棋子画风的识谱：自适应找棋盘亮区 -> 找"棋子盘"（红/黑墨连通域）
 * -> 逐个用 PP-OCR rec 读字（OnnxRec）-> 1D 格点拟合关联到 9x10 棋盘。
 *
 * 为什么不用分类模型：Swin 分类模型只认训练时那种画风，遇到木纹/3D 棋子会把中局
 * 读成"开局摆法"；读字只认字形，跨画风稳得多。
 *
 * 关键：棋盘区域必须自己找（亮区），不能依赖 pose 四角——pose 在木纹棋盘上会跑偏。
 *
 * 输出与 Ocr.recognize 一致：长度 90 的 int[]（0 空，否则 type|RED/BLACK）。
 * 同时把拟合出的棋盘四角写进 lastGrid（顺序 左上,右上,右下,左下），供小窗算点击坐标。
 */
public class PieceOcr {

    /** TL,TR,BR,BL（左上,右上,右下,左下），与 Ocr.recognize 的 corners 顺序一致。 */
    public static float[] lastGrid = null;
    public static int lastCount = 0;
    /** 平均读字置信度。 */
    public static float lastScore = 0f;
    private static long lastDump = 0;

    private static final String CHARS = "帥帅將将仕士相象馬马傌車车俥炮砲兵卒";
    private static final int[] TYPES = {
        Board.KING, Board.KING, Board.KING, Board.KING,          // 帥帅將将
        Board.ADVISOR, Board.ADVISOR,                             // 仕士
        Board.ELEPHANT, Board.ELEPHANT,                           // 相象
        Board.HORSE, Board.HORSE, Board.HORSE,                    // 馬马傌
        Board.ROOK, Board.ROOK, Board.ROOK,                       // 車车俥
        Board.CANNON, Board.CANNON,                               // 炮砲
        Board.PAWN, Board.PAWN                                    // 兵卒
    };
    /** 字自带红黑：0=按颜色, 1=红, 2=黑 */
    private static final int[] SIDE = {
        1, 1, 2, 2,
        1, 2,
        1, 2,
        0, 0, 1,
        0, 0, 1,
        0, 0,
        1, 2
    };

    /** 内缩比例：先试最有效的中等内缩，命中即停（提速）。 */
    private static final double[] INSETS = { 0.18, 0.10, 0.26, 0.02, 0.34 };

    /** 一个候选亮区的识别结果。 */
    private static class RegionResult {
        int[] cells;
        double xsp, ysp, gx0, gy0;
        int used;
        double conf, resid;
    }

    public static int[] recognize(Bitmap src, float[] rough) {
        lastGrid = null; lastCount = 0; lastScore = 0f;
        if (!OnnxRec.isReady()) return null;
        try {
            int W = src.getWidth(), H = src.getHeight();

            // 棋盘区域：多亮区候选择优（单取最大亮块会在"双悬浮窗"场景选中悬浮窗）
            ArrayList<int[]> regions = boardRegions(src, 4);
            if (regions.isEmpty()) {
                int[] rr = fallbackRegion(src, rough);
                if (rr != null) regions.add(rr);
            }
            if (regions.isEmpty()) return null;

            RegionResult best = null;
            double bestQ = -1e18;
            for (int ri = 0; ri < regions.size(); ri++) {
                int[] reg = regions.get(ri);
                RegionResult r = tryRegion(src, reg[0], reg[1], reg[2], reg[3]);
                if (r == null) {
                    android.util.Log.i("xiangqi-ocr", "region" + ri + " box=" + reg[0] + "," + reg[1]
                            + "," + reg[2] + "," + reg[3] + " -> 候选不足，放弃");
                    continue;
                }
                double ratio = r.ysp > 0 ? r.xsp / r.ysp : 0;
                // 打分：棋子数 + 置信度*8 - 残差*2 - 格距不方正*10（离线在真机截图上标定）
                double score = r.used * 1.0 + r.conf * 8.0 - r.resid * 2.0 - Math.abs(ratio - 1.0) * 10.0;
                android.util.Log.i("xiangqi-ocr", String.format(
                        "region%d box=%d,%d,%d,%d n=%d conf=%.2f resid=%.1f sp=%.1f/%.1f ratio=%.2f => q=%.2f",
                        ri, reg[0], reg[1], reg[2], reg[3], r.used, r.conf, r.resid, r.xsp, r.ysp, ratio, score));
                if (score > bestQ) {
                    bestQ = score;
                    best = r;
                }
            }
            if (best == null || best.used < 8) {
                android.util.Log.i("xiangqi-ocr", "pieceocr all regions failed");
                return null;
            }
            lastGrid = new float[] {
                (float) best.gx0, (float) best.gy0,
                (float) (best.gx0 + 8 * best.xsp), (float) best.gy0,
                (float) (best.gx0 + 8 * best.xsp), (float) (best.gy0 + 9 * best.ysp),
                (float) best.gx0, (float) (best.gy0 + 9 * best.ysp)
            };
            lastCount = best.used;
            lastScore = (float) best.conf;
            android.util.Log.i("xiangqi-ocr", String.format(
                "pieceocr n=%d sp=%.1f/%.1f conf=%.2f q=%.2f", best.used, best.xsp, best.ysp, best.conf, bestQ));
            return best.cells;
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "PieceOcr.recognize failed: " + t);
            return null;
        }
    }

    /**
     * 在一个候选亮区内跑完整流程：找棋子盘 -> rec 读字 -> 1D 格点拟合。
     */
    private static RegionResult tryRegion(Bitmap src, int rx0, int ry0, int rx1, int ry1) {
        int rw = rx1 - rx0, rh = ry1 - ry0;
        if (rw < 80 || rh < 80) return null;
        double cell = Math.min(rw / 9.0, rh / 10.0);
        if (cell < 12) return null;

        ArrayList<double[]> circles = CircleDetector.detect(src, rx0, ry0, rx1, ry1, cell);
        if (circles == null || circles.size() < 4) return null;
        ArrayList<double[]> pieces = new ArrayList<double[]>();   // {cx, cy, type, redFlag, score}
        for (int i = 0; i < circles.size(); i++) {
            double[] c = circles.get(i);
            int cr = Math.max(6, (int) Math.round(c[2]));
            double[] p = readComp(src, (int) Math.round(c[0]) - cr, (int) Math.round(c[1]) - cr, cr * 2, cr * 2);
            if (p == null) continue;
            p[0] = c[0];
            p[1] = c[1];
            pieces.add(p);
        }
        if (pieces.size() < 8) return null;

        double[] xs = new double[pieces.size()], ys = new double[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) { xs[i] = pieces.get(i)[0]; ys[i] = pieces.get(i)[1]; }
        double[] fx = fit1d(xs, cell * 0.65, cell * 1.35, 9);
        double[] fy = fit1d(ys, cell * 0.65, cell * 1.35, 10);
        double xsp = fx[2], ysp = fy[2];
        if (xsp < 6 || ysp < 6) return null;
        double ox = fx[1], oy = fy[1];
        int kminX = Integer.MAX_VALUE, kminY = Integer.MAX_VALUE;
        for (int i = 0; i < pieces.size(); i++) {
            double[] p = pieces.get(i);
            int kx = (int) Math.round((p[0] - ox) / xsp);
            int ky = (int) Math.round((p[1] - oy) / ysp);
            if (kx < kminX) kminX = kx;
            if (ky < kminY) kminY = ky;
        }
        double gx0 = ox + kminX * xsp;
        double gy0 = oy + kminY * ysp;

        int[] cells = new int[90];
        double[] bestScore = new double[90];
        int used = 0;
        double scoreSum = 0;
        for (int i = 0; i < pieces.size(); i++) {
            double[] p = pieces.get(i);
            int col = (int) Math.round((p[0] - gx0) / xsp);
            int row = (int) Math.round((p[1] - gy0) / ysp);
            if (col < 0 || col > 8 || row < 0 || row > 9) continue;
            if (Math.abs(p[0] - (gx0 + col * xsp)) > 0.45 * xsp) continue;
            if (Math.abs(p[1] - (gy0 + row * ysp)) > 0.45 * ysp) continue;
            int sq = row * 9 + col;
            if (p[4] <= bestScore[sq]) continue;
            int type = (int) p[2];
            boolean red = p[3] > 0;
            cells[sq] = type | (red ? Board.RED : Board.BLACK);
            bestScore[sq] = p[4];
            used++;
            scoreSum += p[4];
        }
        if (used < 8) return null;
        RegionResult out = new RegionResult();
        out.cells = cells;
        out.xsp = xsp; out.ysp = ysp;
        out.gx0 = gx0; out.gy0 = gy0;
        out.used = used;
        out.conf = scoreSum / used;
        // fit1d 第一项是归一化分数（残差/sp + 0.15*|格点数-nlines|）。
        // 乘回 sp 换算成"像素级残差"，与离线脚本的打分口径一致（离线实测主棋盘 1.7px / 悬浮窗 7.9px）。
        out.resid = fx[0] * xsp + fy[0] * ysp;
        return out;
    }

    /** 亮区候选不足时，退回给定四角/整图。 */
    private static int[] fallbackRegion(Bitmap src, float[] rough) {
        int W = src.getWidth(), H = src.getHeight();
        if (rough != null && rough.length == 8) {
            float mnx = 1e9f, mxx = -1e9f, mny = 1e9f, mxy = -1e9f;
            for (int i = 0; i < 4; i++) {
                float x = rough[i * 2], y = rough[i * 2 + 1];
                if (x < mnx) mnx = x; if (x > mxx) mxx = x;
                if (y < mny) mny = y; if (y > mxy) mxy = y;
            }
            int pad = (int) (Math.max(mxx - mnx, mxy - mny) * 0.06f);
            return new int[] {
                (int) Math.max(0, mnx - pad), (int) Math.max(0, mny - pad),
                (int) Math.min(W, mxx + pad), (int) Math.min(H, mxy + pad)
            };
        }
        return new int[] { 0, 0, W, H };
    }


    /**
     * 亮区候选：返回面积最大的前 topN 个连通块（按面积降序），各为 {x0,y0,x1,y1}。
     *
     * 为什么要多个：真实悬浮窗场景下屏幕上有多个棋盘（天天象棋主棋盘 + 我们的悬浮窗
     * + 别的悬浮窗），只取最大亮块会选错。多给几个候选，由调用方用"格点质量"打分择优。
     */
    private static ArrayList<int[]> boardRegions(Bitmap src, int topN) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        int W = src.getWidth(), H = src.getHeight();
        int sw = 320;
        int sh = Math.max(24, (int) Math.round(H * (sw / (double) W)));
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] p = new int[sw * sh];
        small.getPixels(p, 0, sw, 0, 0, sw, sh);
        small.recycle();
        int[] lum = new int[sw * sh];
        long sum = 0;
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            int l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            lum[i] = l;
            sum += l;
        }
        boolean[] b = new boolean[sw * sh];
        int cnt = 0;
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            int R = Color.red(c), G = Color.green(c), B = Color.blue(c);
            // 木纹棋盘：橙棕（红>绿>蓝），排除白底/黑字/彩色 UI
            boolean wood = (R - B > 35) && (R > 110) && (G > 85) && (R - G > 12) && (R < 250);
            b[i] = wood;
            if (wood) cnt++;
        }
        if (cnt < sw * sh * 0.10) {           // 不是木纹棋盘 -> 退回亮度法
            int thr = (int) Math.max(150, sum / lum.length);
            for (int i = 0; i < lum.length; i++) b[i] = lum[i] > thr;
        }
        b = morphClose(b, sw, sh, 3);
        ArrayList<int[]> comps = componentBoxes(b, sw, sh, 4);
        if (comps.isEmpty()) return out;
        for (int i = 0; i < comps.size() && out.size() < topN; i++) {
            int[] box = comps.get(i);
            if ((box[2] - box[0]) * (box[3] - box[1]) < sw * sh * 0.08) continue;
            int pad = (int) (Math.max(box[2] - box[0], box[3] - box[1]) * 0.03);
            out.add(new int[] {
                Math.max(0, (int) ((box[0] - pad) * W / (double) sw)),
                Math.max(0, (int) ((box[1] - pad) * H / (double) sh)),
                Math.min(W, (int) ((box[2] + pad) * W / (double) sw)),
                Math.min(H, (int) ((box[3] + pad) * H / (double) sh))
            });
        }
        return out;
    }

    /** 连通块外接框，按面积降序返回前 topN 个。 */
    private static ArrayList<int[]> componentBoxes(boolean[] m, int w, int h, int topN) {
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        ArrayList<int[]> boxes = new ArrayList<int[]>();
        ArrayList<Integer> areas = new ArrayList<Integer>();
        for (int sy = 0; sy < h; sy++) {
            for (int sx = 0; sx < w; sx++) {
                int idx = sy * w + sx;
                if (!m[idx] || seen[idx]) continue;
                int head = 0, tail = 0, area = 0, x0 = sx, x1 = sx, y0 = sy, y1 = sy;
                stack[tail++] = idx; seen[idx] = true;
                while (head < tail) {
                    int cur = stack[head++]; area++;
                    int cx = cur % w, cy = cur / w;
                    if (cx < x0) x0 = cx; if (cx > x1) x1 = cx;
                    if (cy < y0) y0 = cy; if (cy > y1) y1 = cy;
                    for (int d = 0; d < 4; d++) {
                        int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int ny = cy + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int ni = ny * w + nx;
                        if (m[ni] && !seen[ni]) { seen[ni] = true; stack[tail++] = ni; }
                    }
                }
                // 插入排序（保持面积降序）
                int pos = 0;
                while (pos < areas.size() && areas.get(pos) >= area) pos++;
                if (pos < topN) {
                    boxes.add(pos, new int[] { x0, y0, x1, y1 });
                    areas.add(pos, area);
                    while (boxes.size() > topN) { boxes.remove(boxes.size() - 1); areas.remove(areas.size() - 1); }
                }
            }
        }
        return boxes;
    }

    private static boolean[] erode(boolean[] m, int w, int h, int r) {
        boolean[] o = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                boolean v = true;
                for (int dy = -r; dy <= r && v; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) { v = false; break; }
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w || !m[yy * w + xx]) { v = false; break; }
                    }
                }
                o[row + x] = v;
            }
        }
        return o;
    }

    private static boolean[] morphClose(boolean[] m, int w, int h, int r) {
        return erode(dilate(m, w, h, r), w, h, r);
    }

    /** 返回 {cx, cy, type, redFlag, score}，读不出返回 null。命中够自信就提前停（提速）。 */
    private static double[] readComp(Bitmap src, int cx0, int cy0, int ww, int hh) {
        double best = -1;
        int bestType = 0, bestSide = 0;
        for (int ii = 0; ii < INSETS.length; ii++) {
            int mx = (int) (ww * INSETS[ii]), my = (int) (hh * INSETS[ii]);
            int cw = ww - 2 * mx, ch = hh - 2 * my;
            if (cw < 8 || ch < 8) continue;
            if (cx0 + mx < 0 || cy0 + my < 0 || cx0 + mx + cw > src.getWidth() || cy0 + my + ch > src.getHeight()) continue;
            Bitmap crop = Bitmap.createBitmap(src, cx0 + mx, cy0 + my, cw, ch);
            double hereScore = -1;
            int hereType = 0, hereSide = 0;
            for (int variant = 0; variant < 2; variant++) {
                Bitmap in = crop;
                if (variant == 1) in = toBinary(crop);
                String t = OnnxRec.read(in);
                float sc = OnnxRec.lastScore;
                if (variant == 1 && in != crop) in.recycle();
                if (t.length() == 1) {
                    int idx = CHARS.indexOf(t.charAt(0));
                    if (idx >= 0 && sc > 0.30f && sc > hereScore) {
                        hereScore = sc;
                        hereType = TYPES[idx];
                        hereSide = SIDE[idx];
                    }
                }
                if (hereScore >= 0.55) break;
            }
            if (hereScore > best) {
                best = hereScore;
                bestType = hereType;
                bestSide = hereSide != 0 ? hereSide : (redFraction(crop) > 0.5 ? 1 : 2);
            }
            crop.recycle();
            if (best >= 0.55) break;
        }
        if (best < 0) return null;
        return new double[] { 0, 0, bestType, bestSide == 1 ? 1 : 0, best };
    }

    /** 判定裁剪图里"字"是红还是黑（只看墨像素）。返回红色占比 0~1。 */
    private static double redFraction(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] p = new int[w * h];
        b.getPixels(p, 0, w, 0, 0, w, h);
        int ink = 0, red = 0;
        for (int c : p) {
            int R = Color.red(c), G = Color.green(c), B = Color.blue(c);
            int l = (R * 299 + G * 587 + B * 114) / 1000;
            if (l < 110) {
                ink++;
                if (R - G > 40 && R - B > 55) red++;
            }
        }
        return ink < 5 ? 0 : red / (double) ink;
    }

    private static Bitmap toBinary(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] p = new int[w * h];
        b.getPixels(p, 0, w, 0, 0, w, h);
        long s = 0;
        for (int c : p) s += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
        int thr = (int) (s / p.length);
        for (int i = 0; i < p.length; i++) {
            int l = (Color.red(p[i]) * 299 + Color.green(p[i]) * 587 + Color.blue(p[i]) * 114) / 1000;
            int v = l > thr ? 255 : 0;
            p[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        o.setPixels(p, 0, w, 0, 0, w, h);
        return o;
    }

    private static boolean[] dilate(boolean[] m, int w, int h, int r) {
        boolean[] t = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int a = Math.max(0, x - r), b = Math.min(w - 1, x + r);
                boolean v = false;
                for (int k = a; k <= b; k++) if (m[row + k]) { v = true; break; }
                t[row + x] = v;
            }
        }
        boolean[] o = new boolean[w * h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int a = Math.max(0, y - r), b = Math.min(h - 1, y + r);
                boolean v = false;
                for (int k = a; k <= b; k++) if (t[k * w + x]) { v = true; break; }
                o[y * w + x] = v;
            }
        }
        return o;
    }

    /** 1D 格点拟合：返回 {平均残差, 起点偏移, 间距}。 */
    /** 1D 格点拟合：返回 {评分, 起点偏移, 间距}。加"格点数≈nlines"约束，避免选到 sp/2 这种倍频。 */
    private static double[] fit1d(double[] v, double minsp, double maxsp, int nlines) {
        double best = 1e18, bo = 0, bs = minsp;
        double vmin = v[0], vmax = v[0];
        for (double d : v) { if (d < vmin) vmin = d; if (d > vmax) vmax = d; }
        for (double sp = minsp; sp <= maxsp; sp += 0.5) {
            for (double o = vmin - sp; o < vmin; o += sp / 50.0) {
                double s = 0;
                for (double d : v) {
                    double m = (d - o + sp / 2) % sp;
                    if (m < 0) m += sp;
                    s += Math.abs(m - sp / 2);
                }
                s /= v.length;
                int kmin = (int) Math.round((vmin - o) / sp), kmax = (int) Math.round((vmax - o) / sp);
                int nl = kmax - kmin + 1;
                double score = s / sp + 0.15 * Math.abs(nl - nlines);
                if (score < best) { best = score; bo = o; bs = sp; }
            }
        }
        return new double[] { best, bo, bs };
    }
}

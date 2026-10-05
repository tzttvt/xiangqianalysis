package com.dsh.xiangqi;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * 棋盘图识别（纯 Java 实现，不依赖任何 OCR 库）：
 *  1. 自动定位棋盘四角（截图场景）或使用用户手点的四角
 *  2. 单应变换做透视校正
 *  3. 逐格判定有无棋子（圆盘占比）
 *  4. 取棋子内部最深的墨色判断红/黑
 *  5. 把字形归一化后与用不同字体渲染的模板做匹配，得出具体兵种
 */
public class Ocr {

    private static final int N = 24;              // 字形归一化尺寸
    private static final float MARGIN = 0.30f;    // 棋盘四周留白（单位：格）

    // ---------- 模板 ----------
    private static class Tpl {
        String name;
        int type;
        boolean red;
        float[] bits;
    }

    private static List<Tpl> TEMPLATES = null;

    private static final String[] RED_CHARS = { "帅", "仕", "相", "马", "车", "炮", "兵" };
    private static final String[] BLACK_CHARS = { "将", "士", "象", "马", "车", "炮", "卒" };
    private static final int[] TYPES = { Board.KING, Board.ADVISOR, Board.ELEPHANT, Board.HORSE, Board.ROOK, Board.CANNON, Board.PAWN };

    private static synchronized void initTemplates() {
        if (TEMPLATES != null) return;
        TEMPLATES = new ArrayList<Tpl>();
        Typeface[] fonts = new Typeface[] {
            Typeface.create(Typeface.SERIF, Typeface.BOLD),
            Typeface.create(Typeface.SERIF, Typeface.NORMAL),
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
            Typeface.create("serif-monospace", Typeface.NORMAL),
        };
        for (int f = 0; f < fonts.length; f++) {
            for (int i = 0; i < 7; i++) {
                TEMPLATES.add(makeTemplate(RED_CHARS[i], TYPES[i], true, fonts[f]));
                TEMPLATES.add(makeTemplate(BLACK_CHARS[i], TYPES[i], false, fonts[f]));
            }
        }
    }

    private static Tpl makeTemplate(String ch, int type, boolean red, Typeface tf) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(tf);
        p.setTextSize(size * 0.82f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(ch, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p);
        Tpl t = new Tpl();
        t.name = ch; t.type = type; t.red = red;
        t.bits = normalize(inkMask(bmp), bmp.getWidth(), bmp.getHeight());
        bmp.recycle();
        return t;
    }

    /** 把位图转成墨迹强度数组（1=最黑）。 */
    private static float[] inkMask(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int min = 255, max = 0;
        int[] lum = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            lum[i] = l;
            if (l < min) min = l;
            if (l > max) max = l;
        }
        float[] m = new float[w * h];
        int hi = Math.max(1, max - min);
        for (int i = 0; i < lum.length; i++) m[i] = Math.max(0f, Math.min(1f, (max - lum[i]) / (float) hi));
        return m;
    }

    /** 裁剪墨迹包围盒 → 归一化到 N×N 的灰度墨迹强度（1=最黑，0=背景）。 */
    private static float[] normalize(float[] src, int w, int h) {
        boolean[] mask = new boolean[src.length];
        for (int i = 0; i < src.length; i++) mask[i] = src[i] > 0.35f;
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (mask[y * w + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        float[] out = new float[N * N];
        if (maxX < 0) return out;
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        // 保持长宽比，居中放到 N×N
        float scale = Math.max(bw, bh) / (float) (N - 2);
        int offX = (int) ((N - bw / scale) / 2), offY = (int) ((N - bh / scale) / 2);
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int sx = (int) ((x - offX) * scale) + minX;
                int sy = (int) ((y - offY) * scale) + minY;
                if (sx < minX || sx > maxX || sy < minY || sy > maxY) continue;
                out[y * N + x] = src[sy * w + sx];
            }
        }
        return out;
    }

    /** 象棋规则先验：某些兵种不可能出现在某些位置，用于剪掉误判候选。 */
    private static boolean legalAt(int type, int row, int col, boolean red) {
        boolean inPalace = col >= 3 && col <= 5 && (red ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2));
        if (type == Board.KING || type == Board.ADVISOR) return inPalace;
        if (type == Board.ELEPHANT) return red ? row >= 5 : row <= 4;   // 象/相不能过河
        return true;
    }

    /** 灰度软 IoU：对笔画粗细、抗锯齿差异更宽容，同时保留区分度。 */
    private static float similarity(float[] a, float[] b) {
        float inter = 0, uni = 0;
        for (int i = 0; i < a.length; i++) {
            float x = a[i], y = b[i];
            inter += Math.min(x, y);
            uni += Math.max(x, y);
        }
        return uni <= 0 ? 0 : inter / uni;
    }

    /** 圆环检测：棋子外圈是一整圈深色边框；空交叉点/九宫斜线不满足。 */
    private static boolean hasRing(int[] px, int w, int h, float cx, float cy, float cell) {
        float[] radii = { 0.34f, 0.38f, 0.42f, 0.46f, 0.50f };
        float best = 0;
        for (int ri = 0; ri < radii.length; ri++) {
            int dark = 0, total = 0;
            float rad = radii[ri] * cell;
            for (int a = 0; a < 360; a += 4) {
                double ra = Math.toRadians(a);
                int x = (int) (cx + Math.cos(ra) * rad), y = (int) (cy + Math.sin(ra) * rad);
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                total++;
                if (lumOf(px[y * w + x]) < 150) dark++;
            }
            if (total > 0) best = Math.max(best, dark / (float) total);
        }
        return best > 0.55f;
    }

    private static int lumOf(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }

    // ---------- 主流程 ----------

    /** 自动定位棋盘并识别；失败返回 null。 */
    public static int[] recognize(Bitmap src) {
        // 不要在这里先跑 pose：一来白花 ~250ms，二来 pose 一失败就直接 return，
        // 检测器根本没机会出场（真机上就吃过这个亏）。交给统一入口按 ①检测器 -> ②pose/投影 的顺序试。
        return recognize(src, (float[]) null, null);
    }

    /** 自动定位棋盘并识别，可指定一个需要忽略的屏幕矩形（例如扫谱悬浮球自身）。 */
    public static int[] recognize(Bitmap src, int[] excludeRect) {
        // 走统一入口：内部会用「多区域 pose 模型 + 投影法」一起找棋盘（原来这里漏用，导致小窗识别很差）
        return recognize(src, (float[]) null, excludeRect);
    }

    /** 用给定四角（左上、右上、右下、左下）识别。 */
    public static int[] recognize(Bitmap src, float[] corners) {
        return recognize(src, corners, null);
    }

    public static int[] recognize(Bitmap src, float[] corners, int[] excludeRect) {
        // 用户/上层显式给了四角 = 他就是要认那块区域，检测器结论只能"同区域"才采信
        boolean userGaveCorners = (corners != null);

        // ① 首选：YOLO26s 棋子检测器。一次推理直接出"框 + 兵种 + 颜色"，
        //    再靠棋子自成的点阵定棋盘，不用猜亮区、不用爬山（原来一次识别几十遍推理）。
        //    离线实测：真机截图 31/32 全对、0 错类；干净 holdout 盘面级准确率 86.9%；
        //    一次推理 ~0.2s，而老链路 2~3s。放在最前面还能省掉下面 1200x2670 的 getPixels。
        if (DetOcr.isReady()) {
            int[] det = DetOcr.recognize(src, excludeRect);
            if (det != null && count(det) >= 6
                    && (!userGaveCorners || nearGrid(corners, DetOcr.lastGrid))) {
                lastCorners = DetOcr.lastGrid == null ? null : DetOcr.lastGrid.clone();
                hintCorners = null;          // 检测器自带网格，旧的爬山对齐缓存作废
                android.util.Log.i("xiangqi-ocr", "use DetOcr pieces=" + count(det)
                        + " " + DetOcr.lastDebug);
                return det;
            }
            android.util.Log.i("xiangqi-ocr", "DetOcr no-go: " + DetOcr.lastDebug);
        }

        initTemplates();
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        if (corners == null) corners = detectBoardSmart(src);
        if (corners == null) return null;
        double[] hm = homography(corners);
        if (hm == null) return null;
        float[][] center = new float[90][2];
        for (int r = 0; r < 10; r++) {
            for (int col = 0; col < 9; col++) {
                double[] xy = apply(hm, col, r);
                center[r * 9 + col][0] = (float) xy[0];
                center[r * 9 + col][1] = (float) xy[1];
            }
        }
        double cellX = Math.hypot(center[1][0] - center[0][0], center[1][1] - center[0][1]);
        double cellY = Math.hypot(center[9][0] - center[0][0], center[9][1] - center[0][1]);
        double cell = (cellX + cellY) / 2.0;
        if (cell < 6) return null;
        // 定位质量关：格子必须接近正方（透视可以有轻微差异），否则判定定位失败
        double ratio = cellX / cellY;
        if (ratio < 0.72 || ratio > 1.38) {
            android.util.Log.i("xiangqi-ocr", "grid rejected: cellX=" + (int) cellX + " cellY=" + (int) cellY);
            return null;
        }

        // ② 退回：读字自适应定位棋盘（不依赖 pose）：木纹/3D 棋子上 pose 会跑偏，导致后面
        // 爬山搜到的全是错误网格。定位准了之后，分类模型在正确定位下其实读得很准（实测与
        // 读字 100% 一致），所以用 PieceOcr 的网格跑分类模型，读字结果只作一致性校验。
        if (OnnxRec.isReady()) {
            int[] recCells = PieceOcr.recognize(src, corners);
            if (recCells != null && PieceOcr.lastGrid != null && OnnxOcr.isReady()) {
                int[] cls = OnnxOcr.recognize(src, PieceOcr.lastGrid);
                if (cls != null && plausible(cls)) {
                    int rn = count(recCells), cn = count(cls);
                    float agree = agreeRatio(recCells, cls);
                    android.util.Log.i("xiangqi-ocr", "pieceocr grid check cls=" + cn
                            + " rec=" + rn + " agree=" + String.format("%.2f", agree));
                    // 两者不一致 -> 这个网格不可信（例：App 自己画风上亮区定位偏了）-> 回退老流程
                    if (agree >= 0.6f) {
                        int[] out = (cn >= rn) ? cls : recCells;
                        lastCorners = PieceOcr.lastGrid.clone();
                        hintCorners = null;      // 读字自带网格，旧的对齐缓存作废
                        android.util.Log.i("xiangqi-ocr", "use pieceocr grid pieces=" + count(out));
                        return out;
                    }
                }
                android.util.Log.i("xiangqi-ocr", "pieceocr grid rejected cls=" + (cls == null ? -1 : count(cls)));
            }
        }

        // 优先用开源深度学习模型（对任意棋子样式鲁棒），失败再走模板匹配
        if (OnnxOcr.isReady()) {
            // 快速路径：沿用上次成功的对齐，只跑一次分类模型（稳定状态下识别从 2~3 秒降到 ~0.2 秒）
            long now = System.currentTimeMillis();
            boolean cacheFresh = (now - hintCornersAt) < 8000;   // 缓存最多用 8 秒，之后强制全量重算
            if (hintCorners != null && hintCorners.length == 8 && cacheFresh) {
                int[] fast = OnnxOcr.recognize(src, hintCorners);
                // 门槛从 8 提到 20：避免把"看着还行其实错了"的对齐长期缓存住
                if (fast != null && plausible(fast) && count(fast) >= 20) {
                    lastCorners = hintCorners.clone();
                    android.util.Log.i("xiangqi-ocr", "fast path hit, pieces=" + count(fast));
                    return fast;
                }
            }
            // 两套定位都跑：① 开源 pose 模型（适合真实照片）② 投影网格（适合规整截图）
            java.util.List<float[]> bases = new java.util.ArrayList<float[]>();
            if (OnnxPose.isReady()) {
                float[] pc = OnnxPose.detect(src);
                if (pc != null && sane(pc, w, h)) bases.add(pc);
            }
            float[] proj = detectBoard(src);
            if (proj != null) bases.add(proj);
            if (corners != null) bases.add(corners);

            int[] bestCells = null;
            float bestQ = -1;
            float[] lastBase = null;
            float[] bestBaseForClimb = null;
            for (int bi = 0; bi < bases.size(); bi++) {
                float[] base = refine(bases.get(bi), px, w, h);
                lastBase = base;
                // 再试 ±半格偏移，兼顾"外框线 vs 交叉点"的差别
                float[][] cands = new float[][] {
                    base,
                    warpGrid(base, 0.5f, 0f, 1f), warpGrid(base, -0.5f, 0f, 1f),
                    warpGrid(base, 0f, 0.5f, 1f), warpGrid(base, 0f, -0.5f, 1f),
                    warpGrid(base, 0.25f, 0f, 1f), warpGrid(base, -0.25f, 0f, 1f),
                    warpGrid(base, 0f, 0.25f, 1f), warpGrid(base, 0f, -0.25f, 1f),
                    warpGrid(base, 0.25f, 0.25f, 1f), warpGrid(base, -0.25f, -0.25f, 1f),
                    warpGrid(base, 0f, 0f, 0.93f),   // 离线标定：三张真机截图都收敛到 0.92~0.95
                    warpGrid(base, 0f, 0f, 0.90f),
                    warpGrid(base, 0f, 0f, 0.95f),
                };
                for (int ci = 0; ci < cands.length; ci++) {
                    int[] r = OnnxOcr.recognize(src, cands[ci]);
                    if (r == null) continue;
                    int n = count(r);
                    float q = (plausible(r) ? 1.0f : 0f) + Math.min(n, 32) / 32f;
                    if (q > bestQ) { bestQ = q; bestCells = r; bestBaseForClimb = cands[ci]; }
                    if (plausible(r) && n >= 26) break;
                }
                if (bestQ >= 1.9f) break;
            }
            // 以"识别出的棋子数最多"为目标再爬山微调一轮（离线实验证明能明显提升）
            if (bestCells != null && bestBaseForClimb != null) {
                float[] cur = bestBaseForClimb.clone();
                int curN = count(bestCells);
                // 目标函数：棋子数 + 置信度*20 + 合法性*3（离线在真机截图上验证过更优）
                float curScore = climbScore(bestCells, OnnxOcr.lastConfidence);
                float step = 0.10f;
                for (int round = 0; round < 3; round++) {
                    boolean improved = false;
                    for (int d = -1; d <= 1; d += 2) {
                        float[][] trial = {
                            warpGrid(cur, step * d, 0f, 1f),
                            warpGrid(cur, 0f, step * d, 1f),
                            warpGrid(cur, 0f, 0f, 1f + step * d * 0.5f),
                        };
                        for (int t = 0; t < trial.length; t++) {
                            int[] r2 = OnnxOcr.recognize(src, trial[t]);
                            if (r2 == null) continue;
                            float s2 = climbScore(r2, OnnxOcr.lastConfidence);
                            if (s2 > curScore) { curScore = s2; curN = count(r2); cur = trial[t]; bestCells = r2; improved = true; }
                        }
                    }
                    if (!improved) step *= 0.5f;
                }
                android.util.Log.i("xiangqi-ocr", "climb done pieces=" + curN);
                if (lastBase != null) { lastCorners = cur.clone(); hintCorners = cur.clone(); hintCornersAt = System.currentTimeMillis(); }
                else if (bestCells != null && count(bestCells) >= 3) { }
            }
            if (bestCells != null && count(bestCells) >= 3) {
                if (lastBase != null && hintCorners == null) { lastCorners = lastBase.clone(); hintCorners = lastBase.clone(); hintCornersAt = System.currentTimeMillis(); }
                android.util.Log.i("xiangqi-ocr", "use onnx result, pieces=" + count(bestCells)
                        + " plausible=" + plausible(bestCells) + " q=" + String.format("%.2f", bestQ));
                return bestCells;   // 即便不完全合法也返回，用户可在摆谱模式里改
            }
            return null;
        }
        double[] bg = boardBackground(px, w, h, center, (int) cell);
        int[] cells = pass(px, w, h, center, cell, bg, excludeRect);
        int found = count(cells);
        // 暗底棋盘/黑白反色图：反色后再试一次，取识别到棋子更多的那次
        if (found < 12) {
            int[] inv = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                inv[i] = 0xFF000000 | ((255 - Color.red(c)) << 16) | ((255 - Color.green(c)) << 8) | (255 - Color.blue(c));
            }
            double[] bg2 = boardBackground(inv, w, h, center, (int) cell);
            int[] cells2 = pass(inv, w, h, center, cell, bg2, excludeRect);
            if (count(cells2) > found) cells = cells2;
        }
        int found2 = count(cells);
        android.util.Log.i("xiangqi-ocr", "recognize found=" + found2 + " cell=" + (int) cell);
        if (found2 < 4) return null;
        return cells;
    }

    private static int count(int[] cells) {
        int n = 0;
        for (int i = 0; i < 90; i++) if (cells[i] > 0) n++;
        return n;
    }

    /**
     * 悬浮窗扫描专用：**只**走 YOLO 检测器，检不到直接返回 null，绝不退回下面那条
     * "猜亮区 → 读字 → 爬山"的老链路。老链路一次要跑几十遍推理（2~3 秒），
     * 而悬浮窗每 0.5 秒就要扫一帧，放进来必然把窗口卡死（上一帧还没算完下一帧又到）。
     * 失败就交给调用方当一次 miss，留着上一帧结果等下一次。
     */
    public static int[] recognizeDetOnly(Bitmap src, int[] excludeRect) {
        if (!DetOcr.isReady() || src == null) return null;
        int[] det = DetOcr.recognize(src, excludeRect);
        if (det != null && count(det) >= 6) {
            lastCorners = DetOcr.lastGrid == null ? null : DetOcr.lastGrid.clone();
            hintCorners = null;
            return det;
        }
        android.util.Log.i("xiangqi-ocr", "DetOcr(only) no-go: " + DetOcr.lastDebug);
        return null;
    }

    /**
     * 两个棋盘四角是否指向"同一块棋盘"（中心距离 + 尺寸比）。
     * 用途：① 上层显式给了四角时，只有检测器认出来的也是那块棋盘，才采信检测器结论；
     * ② 悬浮窗扫描时判断"是不是换了棋盘"，换了就清掉多帧投票历史与遮挡回填的记忆。
     */
    public static boolean nearGrid(float[] a, float[] b) {
        if (a == null || b == null || a.length != 8 || b.length != 8) return false;
        double acx = (a[0] + a[4]) / 2.0, acy = (a[1] + a[5]) / 2.0;
        double bcx = (b[0] + b[4]) / 2.0, bcy = (b[1] + b[5]) / 2.0;
        double adiag = Math.hypot(a[4] - a[0], a[5] - a[1]);
        double bdiag = Math.hypot(b[4] - b[0], b[5] - b[1]);
        if (adiag < 1 || bdiag < 1) return false;
        if (Math.hypot(acx - bcx, acy - bcy) > 0.35 * Math.max(adiag, bdiag)) return false;
        double ratio = adiag / bdiag;
        return ratio > 0.62 && ratio < 1.62;
    }

    /** 一次识别：先在 90 格上自适应判定有无棋子，再聚类分红黑，最后模板匹配（低分直接判为未知）。 */
    private static int[] pass(int[] px, int w, int h, float[][] center, double cell, double[] bg, int[] excludeRect) {
        double[] ink = new double[90];
        for (int sq = 0; sq < 90; sq++) {
            float cx = center[sq][0], cy = center[sq][1];
            if (excludeRect != null && cx >= excludeRect[0] && cx <= excludeRect[2] && cy >= excludeRect[1] && cy <= excludeRect[3]) { ink[sq] = -1; continue; }
            ink[sq] = diskInkRatio(px, w, h, cx, cy, (float) (cell * 0.42), bg);
        }
        // Otsu 自适应阈值：把 90 个墨迹占比分成"空格 / 有子"两类
        double thr = otsu(ink);
        if (thr < 0.30) thr = 0.30;      // 兜底下限，避免把网格线当棋子
        // 红黑聚类：用"红色通道占优度"做 2 均值
        double[] redness = new double[90];
        int n = 0;
        double sum = 0;
        for (int sq = 0; sq < 90; sq++) {
            if (ink[sq] < thr) continue;
            float cx = center[sq][0], cy = center[sq][1];
            double[] c = darkestColor(px, w, h, cx, cy, (float) (cell * 0.30), 0.20);
            redness[sq] = c == null ? 0 : (c[0] - Math.max(c[1], c[2]));
            sum += redness[sq];
            n++;
        }
        double mean = n == 0 ? 0 : sum / n;
        int[] cells = new int[90];
        for (int sq = 0; sq < 90; sq++) {
            if (ink[sq] < thr) continue;
            float cx = center[sq][0], cy = center[sq][1];
            boolean red = redness[sq] >= mean;      // 相对判定，适应各种配色
            int row = sq / 9, col = sq % 9;
            float[] glyph = glyphOf(px, w, h, cx, cy, (float) (cell * 0.36), (float) (cell * 0.34));
            if (glyph == null) continue;
            int type = 0;
            float best = 0, second = 0;
            for (int i = 0; i < TEMPLATES.size(); i++) {
                Tpl t = TEMPLATES.get(i);
                if (t.red != red) continue;
                if (!legalAt(t.type, row, col, red)) continue;
                float s = similarity(glyph, t.bits);
                if (s > best) { second = best; best = s; type = t.type; }
                else if (s > second) second = s;
            }
            // 拒绝阈值：分数太低就判未知（宁可留空让用户点，也不要瞎猜）
            if (type == 0 || best < 0.50f) continue;
            // 第一名和第二名太接近也不可信
            if (best - second < 0.015f && best < 0.62f) continue;
            cells[sq] = type | (red ? Board.RED : Board.BLACK);
        }
        return cells;
    }

    /** 读字结果与分类结果的一致率（只在读字有子的格子上算）。 */
    private static float agreeRatio(int[] rec, int[] cls) {
        if (rec == null || cls == null) return 0f;
        int n = 0, same = 0;
        for (int i = 0; i < 90; i++) {
            if (rec[i] == 0) continue;
            n++;
            if (rec[i] == cls[i]) same++;
        }
        return n == 0 ? 0f : same / (float) n;
    }

    /** 粗略合法性：每种子力数量不超过上限、双方加起来别太离谱。 */
    public static boolean plausible(int[] cells) {
        int[] redMax = new int[8];
        int[] blackMax = new int[8];
        int[] cnt = new int[16];
        for (int i = 0; i < 90; i++) {
            int p = cells[i];
            if (p == 0) continue;
            int t = Board.type(p);
            if (t < 1 || t > 7) return false;
            cnt[t + (Board.isRed(p) ? 0 : 8)]++;
        }
        int[][] limit = { { 1, 2, 2, 2, 2, 2, 5 }, { 1, 2, 2, 2, 2, 2, 5 } };
        for (int t = 1; t <= 7; t++) {
            if (cnt[t] > limit[0][t - 1]) return false;
            if (cnt[t + 8] > limit[1][t - 1]) return false;
        }
        int red = 0, black = 0;
        for (int t = 1; t <= 7; t++) { red += cnt[t]; black += cnt[t + 8]; }
        if (red < 1 || black < 1) return false;
        if (red + black > 34) return false;
        return true;
    }

    /** Otsu 阈值：把一组数值分成两类，返回分界值。 */
    private static double otsu(double[] v) {
        double maxV = 0;
        for (int i = 0; i < v.length; i++) if (v[i] > maxV) maxV = v[i];
        if (maxV <= 0) return 0;
        int bins = 32;
        int[] hist = new int[bins];
        for (int i = 0; i < v.length; i++) {
            if (v[i] < 0) continue;
            int b = (int) (v[i] / maxV * (bins - 1));
            if (b < 0) b = 0;
            if (b >= bins) b = bins - 1;
            hist[b]++;
        }
        int total = 0;
        double sumAll = 0;
        for (int b = 0; b < bins; b++) { total += hist[b]; sumAll += (double) b * hist[b]; }
        if (total == 0) return 0;
        double sumB = 0, wB = 0, best = -1, bestThr = 0;
        for (int b = 0; b < bins; b++) {
            wB += hist[b];
            if (wB == 0) continue;
            double wF = total - wB;
            if (wF == 0) break;
            sumB += (double) b * hist[b];
            double mB = sumB / wB, mF = (sumAll - sumB) / wF;
            double between = wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; bestThr = b; }
        }
        return (bestThr + 0.5) / bins * maxV;
    }

    /** 最近一次识别的棋盘四角（屏幕坐标），供悬浮窗算走子点击位置。 */
    public static float[] lastCorners;
    /** 上次成功的对齐角点：棋盘位置基本不动，用它走快速路径（只跑 1 次模型）。 */
    public static float[] hintCorners;
    public static long hintCornersAt = 0;

    private static final int GLYPH = 40;

    /** 取棋子中央区域的灰度（0..1，1=白），供 OCR 使用。 */
    private static float[] grayCrop(int[] px, int w, int h, float cx, float cy, float half) {
        int s = GLYPH;
        float[] out = new float[s * s];
        int min = 255, max = 0;
        float[] lum = new float[s * s];
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float fx = cx - half + (2 * half) * x / (float) s;
                float fy = cy - half + (2 * half) * y / (float) s;
                int sx = (int) fx, sy = (int) fy;
                int v = 255;
                if (sx >= 0 && sy >= 0 && sx < w && sy < h) v = lumOf(px[sy * w + sx]);
                lum[y * s + x] = v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        int hi = Math.max(1, max - min);
        for (int i = 0; i < lum.length; i++) {
            float t = (lum[i] - min) / (float) hi;
            out[i] = Math.max(0f, Math.min(1f, t));
        }
        return out;
    }

    /** 圆盘内的“非背景”像素比例。 */
    private static double diskInkRatio(int[] px, int w, int h, float cx, float cy, float rad, double[] bg) {
        int total = 0, ink = 0;
        int r0 = (int) -rad, r1 = (int) rad;
        for (int dy = r0; dy <= r1; dy++) {
            for (int dx = r0; dx <= r1; dx++) {
                if (dx * dx + dy * dy > rad * rad) continue;
                int x = (int) cx + dx, y = (int) cy + dy;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                total++;
                if (dist(px[y * w + x], bg) > 48) ink++;   // 实测：棋子本体与棋盘底色距离≈57，网格线≈188
            }
        }
        return total == 0 ? 0 : ink / (double) total;
    }

    private static double[] darkestColor(int[] px, int w, int h, float cx, float cy, float rad, double frac) {
        int r0 = (int) -rad, r1 = (int) rad;
        List<int[]> list = new ArrayList<int[]>();
        for (int dy = r0; dy <= r1; dy++) {
            for (int dx = r0; dx <= r1; dx++) {
                if (dx * dx + dy * dy > rad * rad) continue;
                int x = (int) cx + dx, y = (int) cy + dy;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int c = px[y * w + x];
                int l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                list.add(new int[] { l, Color.red(c), Color.green(c), Color.blue(c) });
            }
        }
        if (list.isEmpty()) return null;
        java.util.Collections.sort(list, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[0] - b[0]; }
        });
        int n = Math.max(3, (int) (list.size() * frac));
        double r = 0, g = 0, b = 0;
        for (int i = 0; i < n; i++) { r += list.get(i)[1]; g += list.get(i)[2]; b += list.get(i)[3]; }
        return new double[] { r / n, g / n, b / n };
    }

    /** 取棋子中央的字形：圆形遮罩内、明显偏暗的像素，然后按墨迹包围盒归一化。 */
    private static float[] glyphOf(int[] px, int w, int h, float cx, float cy, float half, float maskRad) {
        int s = (int) (half * 2);
        if (s < 8) return null;
        int[] lum = new int[s * s];
        int min = 255, max = 0;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int sx = (int) (cx - half) + x, sy = (int) (cy - half) + y;
                int l = 255;
                if (sx >= 0 && sy >= 0 && sx < w && sy < h) l = lumOf(px[sy * w + sx]);
                lum[y * s + x] = l;
                if (l < min) min = l;
                if (l > max) max = l;
            }
        }
        int hi = Math.max(1, max - min);
        int r2 = (int) (maskRad * maskRad);
        float[] intensity = new float[s * s];
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int dx = x - s / 2, dy = y - s / 2;
                if ((dx * dx + dy * dy) > r2) continue;
                intensity[y * s + x] = Math.max(0f, Math.min(1f, (max - lum[y * s + x]) / (float) hi));
            }
        }
        return normalize(intensity, s, s);
    }

    /** N×N 掩码膨胀一圈，提升细笔画的对齐容错。 */
    private static float[] dilate(float[] a) {
        float[] out = new float[a.length];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                if (a[y * N + x] <= 0.5f) continue;
                out[y * N + x] = 1f;
                if (x > 0) out[y * N + x - 1] = 1f;
                if (x < N - 1) out[y * N + x + 1] = 1f;
                if (y > 0) out[(y - 1) * N + x] = 1f;
                if (y < N - 1) out[(y + 1) * N + x] = 1f;
            }
        }
        return out;
    }

    private static double dist(int color, double[] bg) {
        double dr = Color.red(color) - bg[0], dg = Color.green(color) - bg[1], db = Color.blue(color) - bg[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** 棋盘底色：在棋盘范围内取较亮像素的中位色。 */
    private static double[] boardBackground(int[] px, int w, int h, float[][] center, int cell) {
        List<int[]> list = new ArrayList<int[]>();
        for (int sq = 0; sq < 90; sq++) {
            int cx = (int) center[sq][0], cy = (int) center[sq][1];
            for (int dy = -cell / 2; dy <= cell / 2; dy += 3) {
                for (int dx = -cell / 2; dx <= cell / 2; dx += 3) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    int c = px[y * w + x];
                    int l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                    list.add(new int[] { l, Color.red(c), Color.green(c), Color.blue(c) });
                }
            }
        }
        if (list.isEmpty()) return new double[] { 235, 215, 175 };
        java.util.Collections.sort(list, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[0] - b[0]; }
        });
        int from = (int) (list.size() * 0.6), to = list.size();
        double r = 0, g = 0, b = 0;
        for (int i = from; i < to; i++) { r += list.get(i)[1]; g += list.get(i)[2]; b += list.get(i)[3]; }
        int n = Math.max(1, to - from);
        return new double[] { r / n, g / n, b / n };
    }

    private static double[] sampleMean(int[] px, int w, int h, float[] corners, double fx, double fy, double span) {
        double r = 0, g = 0, b = 0; int n = 0;
        double[] hm = homography(corners);
        if (hm == null) return new double[] { 230, 210, 170 };
        for (double u = fx; u <= fx + span; u += 0.02) {
            for (double v = fy; v <= fy + span; v += 0.02) {
                double[] xy = apply(hm, u * 8, v * 9);
                int x = (int) xy[0], y = (int) xy[1];
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int c = px[y * w + x];
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++;
            }
        }
        if (n == 0) return new double[] { 230, 210, 170 };
        return new double[] { r / n, g / n, b / n };
    }

    // ---------- 单应变换（四点 DLT，纯 Java 解 8x8 线性方程组） ----------

    /** 求 格坐标(0..8, 0..9) → 图像坐标 的单应矩阵（H 作用于格坐标，得到像素坐标）。 */
    static double[] homography(float[] c) {
        double[][] src = new double[][] { { 0, 0 }, { 8, 0 }, { 8, 9 }, { 0, 9 } };
        double[][] dst = new double[4][2];
        for (int i = 0; i < 4; i++) { dst[i][0] = c[i * 2]; dst[i][1] = c[i * 2 + 1]; }
        double[][] A = new double[8][8];
        double[] bv = new double[8];
        for (int i = 0; i < 4; i++) {
            double x = src[i][0], y = src[i][1], u = dst[i][0], v = dst[i][1];
            A[i * 2] = new double[] { x, y, 1, 0, 0, 0, -u * x, -u * y };
            bv[i * 2] = u;
            A[i * 2 + 1] = new double[] { 0, 0, 0, x, y, 1, -v * x, -v * y };
            bv[i * 2 + 1] = v;
        }
        double[] sol = solve(A, bv);
        if (sol == null) return null;
        return new double[] { sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1 };
    }

    private static double[] solve(double[][] A, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) { System.arraycopy(A[i], 0, m[i], 0, n); m[i][n] = b[i]; }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-9) return null;
            double[] t = m[col]; m[col] = m[piv]; m[piv] = t;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col] / m[col][col];
                for (int c2 = col; c2 <= n; c2++) m[r][c2] -= f * m[col][c2];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }

    static double[] apply(double[] h, double u, double v) {
        double d = h[6] * u + h[7] * v + h[8];
        if (Math.abs(d) < 1e-9) return new double[] { 0, 0 };
        return new double[] { (h[0] * u + h[1] * v + h[2]) / d, (h[3] * u + h[4] * v + h[5]) / d };
    }

    /**
     * 自动定位棋盘：
     *  1) 亮度阈值找"棋盘底色"区域，取最大连通块（棋盘本体）
     *  2) 在该区域内做暗像素投影，搜索最优的 10 条横线与 9 条竖线（网格拟合，
     *     可容忍棋子遮挡/文字造成的断线）
     * 适用于截图与扫描件；有透视的照片请在界面上手动点四角。
     */
    /** 先用开源 RTMPose 模型找四角（对真实照片最稳），失败再退回投影法。 */
    static float[] detectBoardSmart(Bitmap src) {
        if (OnnxPose.isReady()) {
            int w = src.getWidth(), h = src.getHeight();
            // 棋盘可能只占画面一部分（竖长截图很常见），分区域多试几次，取置信度最高的
            int[][] boxes = {
                { 0, 0, w, h },
                { 0, 0, w, (int) (h * 0.55f) },
                { 0, 0, w, (int) (h * 0.42f) },
                { 0, (int) (h * 0.25f), w, (int) (h * 0.75f) },
                { 0, (int) (h * 0.30f), w, h },
            };
            float[] best = null;
            float bestConf = -1;
            for (int i = 0; i < boxes.length; i++) {
                float[] c = OnnxPose.detect(src, boxes[i][0], boxes[i][1], boxes[i][2], boxes[i][3]);
                float conf = OnnxPose.lastConfidence;
                boolean ok = c != null && sane(c, w, h);
                android.util.Log.i("xiangqi-ocr", "pose box" + i + " conf=" + String.format("%.3f", conf) + " sane=" + ok);
                if (ok && conf > bestConf) { best = c; bestConf = conf; }
                if (ok && conf > 0.30f) break;   // 足够可信就不用再试
            }
            if (best != null) {
                android.util.Log.i("xiangqi-ocr", "pose picked conf=" + String.format("%.3f", bestConf));
                return best;
            }
        }
        return detectBoard(src);
    }

    /** 四角是否合理（在画面内、面积够大、格子近似正方）。 */
    static boolean sane(float[] c, int w, int h) {
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (int i = 0; i < 4; i++) {
            float x = c[i * 2], y = c[i * 2 + 1];
            if (x < -w * 0.1f || y < -h * 0.1f || x > w * 1.1f || y > h * 1.1f) return false;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        float cw = (maxX - minX) / 8f, ch = (maxY - minY) / 9f;
        if (cw < 6 || ch < 6) return false;
        if (maxX - minX < w * 0.15f) return false;
        float ratio = cw / ch;
        return ratio > 0.55f && ratio < 1.8f;
    }

    static float[] detectBoard(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int sw = 560, sh = Math.max(1, Math.round(h * (560f / w)));
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        byte[] bright = new byte[sw * sh];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            bright[i] = (byte) (l > 165 ? 1 : 0);
        }
        int[] stack = new int[sw * sh];
        byte[] seen = new byte[sw * sh];
        int bx0 = 0, by0 = 0, bx1 = 0, by1 = 0, bestArea = 0;
        for (int sy = 0; sy < sh; sy++) {
            for (int sx = 0; sx < sw; sx++) {
                int idx = sy * sw + sx;
                if (bright[idx] == 0 || seen[idx] != 0) continue;
                int head = 0, tail = 0, area = 0, x0 = sx, x1 = sx, y0 = sy, y1 = sy;
                stack[tail++] = idx; seen[idx] = 1;
                while (head < tail) {
                    int cur = stack[head++]; area++;
                    int cx = cur % sw, cy = cur / sw;
                    if (cx < x0) x0 = cx; if (cx > x1) x1 = cx;
                    if (cy < y0) y0 = cy; if (cy > y1) y1 = cy;
                    for (int d = 0; d < 4; d++) {
                        int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = cy + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue;
                        int ni = ny * sw + nx;
                        if (bright[ni] != 0 && seen[ni] == 0) { seen[ni] = 1; stack[tail++] = ni; }
                    }
                }
                if (area > bestArea) { bestArea = area; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1; }
            }
        }
        if (bestArea < sw * sh * 0.04f) return null;
        float k = (float) w / sw;
        int ox0 = Math.round(bx0 * k), oy0 = Math.round(by0 * k), ox1 = Math.round(bx1 * k), oy1 = Math.round(by1 * k);
        int bw = ox1 - ox0 + 1, bh = oy1 - oy0 + 1;
        if (bw < w * 0.3f || bh < h * 0.2f) return null;
        // 只用"线状"暗像素做投影：棋子圆盘是局部暗块，会被过滤掉，
        // 这样木纹棋盘 + 木质棋子的图源（如 Pro象棋）也能正确定位。
        int gap = 4;
        int[] dark = new int[bw * bh];
        for (int i = 0; i < bw * bh; i++) dark[i] = 0;
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                if (lumAt(src, ox0 + x, oy0 + y) < 150) dark[y * bw + x] = 1;
            }
        }
        float[] rowDark = new float[bh], colDark = new float[bw];
        for (int y = 0; y < bh; y++) {
            int c = 0;
            for (int x = 0; x < bw; x++) {
                if (dark[y * bw + x] == 0) continue;
                int xl = x - gap, xr = x + gap;
                if (xl >= 0 && xr < bw && dark[y * bw + xl] != 0 && dark[y * bw + xr] != 0) c++;
            }
            rowDark[y] = c / (float) bw;
        }
        for (int x = 0; x < bw; x++) {
            int c = 0;
            for (int y = 0; y < bh; y++) {
                if (dark[y * bw + x] == 0) continue;
                int yt = y - gap, yb = y + gap;
                if (yt >= 0 && yb < bh && dark[yt * bw + x] != 0 && dark[yb * bw + x] != 0) c++;
            }
            colDark[x] = c / (float) bh;
        }
        // 先拟合列（棋盘宽度通常就是候选框宽度），再用列间距约束行间距 ——
        // 这样即使棋盘和周围亮区被连通域并成一块，也不会拟合出 2 倍行距
        float[] fx = fitGrid(colDark, 9, bw / 11.5f, bw / 7.5f);
        if (fx == null) return null;
        float sx0 = fx[1];
        float[] fy = fitGrid(rowDark, 10, sx0 * 0.62f, sx0 * 1.62f);
        if (fy == null) {
            fy = fitGrid(rowDark, 10, bh / 11.5f, bh / 8.0f);
            if (fy == null) return null;
        }
        // 关键闸门：棋盘格子必须接近正方，否则说明定位到了别的东西（表格/文字区）
        float ratio = fx[1] / fy[1];
        if (ratio < 0.62f || ratio > 1.62f) {      // 放宽以兼容斜拍透视，极端畸变仍拒绝
            android.util.Log.i("xiangqi-ocr", "grid rejected: cellX=" + (int) fx[1] + " cellY=" + (int) fy[1] + " ratio=" + String.format("%.2f", ratio));
            return null;
        }
        float gx0 = ox0 + fx[0], gy0 = oy0 + fy[0];
        float gx1 = gx0 + 8 * fx[1], gy1 = gy0 + 9 * fy[1];
        android.util.Log.i("xiangqi-ocr", "board box=" + ox0 + "," + oy0 + "," + ox1 + "," + oy1
                + " grid=" + (int) gx0 + "," + (int) gy0 + "," + (int) gx1 + "," + (int) gy1
                + " cell=" + (int) fx[1] + "x" + (int) fy[1]
                + " score=" + String.format("%.2f/%.2f", fy[2], fx[2]));
        return new float[] { gx0, gy0, gx1, gy0, gx1, gy1, gx0, gy1 };
    }

    /**
     * 亚像素对齐：在原始像素上小范围搜索（平移 + 缩放），
     * 让"交点处墨迹最多、格心处墨迹最少"——把网格精确锁在交叉点上。
     */
    private static float[] refine(float[] c, int[] px, int w, int h) {
        float[] best = c.clone();
        float bestScore = alignScore(best, px, w, h);
        // 4 个角各自独立做坐标下降 —— 能纠正透视（斜拍照片）
        float step = 0.20f;
        float cellX = Math.abs(c[2] - c[0]) / 8f;
        float cellY = Math.abs(c[5] - c[1]) / 9f;
        if (cellX < 4 || cellY < 4) return c;
        for (int iter = 0; iter < 6; iter++) {
            boolean improved = false;
            for (int corner = 0; corner < 4; corner++) {
                for (int axis = 0; axis < 2; axis++) {
                    for (int dir = -1; dir <= 1; dir += 2) {
                        float[] t = best.clone();
                        t[corner * 2 + axis] += dir * step * (axis == 0 ? cellX : cellY);
                        float s = alignScore(t, px, w, h);
                        if (s > bestScore) { bestScore = s; best = t; improved = true; }
                    }
                }
            }
            if (!improved) step *= 0.45f;
            if (step < 0.02f) break;
        }
        android.util.Log.i("xiangqi-ocr", "refine score=" + String.format("%.1f", bestScore)
                + " quad=" + (int) best[0] + "," + (int) best[1] + " " + (int) best[2] + "," + (int) best[3]
                + " " + (int) best[4] + "," + (int) best[5] + " " + (int) best[6] + "," + (int) best[7]);
        return best;
    }

    /** 以网格中心为基准做平移（单位：格）与缩放。 */
    private static float[] warpGrid(float[] c, float du, float dv, float scale) {
        float cx = (c[0] + c[2] + c[4] + c[6]) / 4f;
        float cy = (c[1] + c[3] + c[5] + c[7]) / 4f;
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float x = c[i * 2], y = c[i * 2 + 1];
            float nx = cx + (x - cx) * scale;
            float ny = cy + (y - cy) * scale;
            // 平移量按半格换算（用相邻角点的间距估计格宽）
            float cellX = Math.abs(c[2] - c[0]) / 8f, cellY = Math.abs(c[5] - c[1]) / 9f;
            out[i * 2] = nx + du * cellX;
            out[i * 2 + 1] = ny + dv * cellY;
        }
        return out;
    }

    /** 对齐评分：交点亮、格心暗 => 分数高。 */
    private static float alignScore(float[] grid, int[] px, int w, int h) {
        double[] hm = homography(grid);
        if (hm == null) return -1e9f;
        float score = 0;
        for (int r = 0; r < 10; r++) {
            for (int col = 0; col < 9; col++) {
                double[] p = apply(hm, col, r);
                score += 1f - localContrast(px, w, h, (float) p[0], (float) p[1]);
            }
        }
        for (int r = 0; r < 9; r++) {
            for (int col = 0; col < 8; col++) {
                double[] p = apply(hm, col + 0.5, r + 0.5);
                score += localContrast(px, w, h, (float) p[0], (float) p[1]) * 0.7f;
            }
        }
        return score / 154f;   // 154 = 90 交点 + 64 格心
    }

    /** 局部"墨迹"程度（0=亮，1=暗），取十字采样。 */
    private static float localContrast(int[] px, int w, int h, float cx, float cy) {
        int sum = 0, n = 0;
        for (int d = -2; d <= 2; d++) {
            int x = Math.round(cx) + d, y = Math.round(cy);
            if (x >= 0 && y >= 0 && x < w && y < h) { sum += lumOf(px[y * w + x]); n++; }
            int x2 = Math.round(cx), y2 = Math.round(cy) + d;
            if (x2 >= 0 && y2 >= 0 && x2 < w && y2 < h) { sum += lumOf(px[y2 * w + x2]); n++; }
        }
        if (n == 0) return 0;
        float l = sum / (float) n;
        return Math.max(0f, Math.min(1f, (200f - l) / 200f));
    }

    /** 网格质量：线要暗、格心要亮、交点要有棋子感，再惩罚过小的棋盘。 */
    private static float gridScore(int[] lum, int sw, int sh, float gx0, float gy0, float sx, float sy, int darkThr) {
        float lineSum = 0;
        int n = 0;
        for (int r = 0; r < 10; r++) {
            int y = Math.round(gy0 + r * sy);
            for (int x = Math.round(gx0); x <= Math.round(gx0 + 8 * sx); x += 2) {
                if (x < 0 || y < 0 || x >= sw || y >= sh) continue;
                lineSum += (lum[y * sw + x] < darkThr) ? 1 : 0;
                n++;
            }
        }
        for (int c = 0; c < 9; c++) {
            int x = Math.round(gx0 + c * sx);
            for (int y = Math.round(gy0); y <= Math.round(gy0 + 9 * sy); y += 2) {
                if (x < 0 || y < 0 || x >= sw || y >= sh) continue;
                lineSum += (lum[y * sw + x] < darkThr) ? 1 : 0;
                n++;
            }
        }
        if (n == 0) return 0;
        float lineRatio = lineSum / n;
        // 格心（相邻两线中间）应当比线上的像素亮
        float interior = 0, onLine = 0;
        int m = 0;
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 8; c++) {
                int cx = Math.round(gx0 + (c + 0.5f) * sx), cy = Math.round(gy0 + (r + 0.5f) * sy);
                if (cx < 0 || cy < 0 || cx >= sw || cy >= sh) continue;
                interior += lum[cy * sw + cx];
                m++;
            }
        }
        for (int c = 0; c < 9; c++) {
            int x = Math.round(gx0 + c * sx), y = Math.round(gy0 + 4.5f * sy);
            if (x < 0 || y < 0 || x >= sw || y >= sh) continue;
            onLine += lum[y * sw + x];
        }
        if (m == 0) return 0;
        interior /= m;
        onLine /= 9f;
        float contrast = (interior - onLine) / 255f;             // 格心比线亮多少
        // 交点处的"棋子感"：交点邻域暗像素占比
        float piecy = 0;
        int pn = 0;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                int cx = Math.round(gx0 + c * sx), cy = Math.round(gy0 + r * sy);
                int dark = 0, tot = 0;
                int rad = Math.max(2, Math.round(Math.min(sx, sy) * 0.22f));
                for (int dy = -rad; dy <= rad; dy += 2) {
                    for (int dx = -rad; dx <= rad; dx += 2) {
                        int x = cx + dx, y = cy + dy;
                        if (x < 0 || y < 0 || x >= sw || y >= sh) continue;
                        tot++;
                        if (lum[y * sw + x] < darkThr) dark++;
                    }
                }
                if (tot > 0 && dark / (float) tot > 0.5f) piecy++;
                pn++;
            }
        }
        float pieceRatio = pn == 0 ? 0 : piecy / pn;             // 典型 0.2~0.6
        float cover = (8 * sx) / sw;
        float sizeBonus = cover > 0.97f ? -0.08f : 0.02f;   // 铺满整屏的多半不是棋盘
        return lineRatio * 0.45f + Math.max(0, Math.min(0.5f, contrast)) * 0.5f + Math.min(0.6f, pieceRatio) * 0.35f + sizeBonus;
    }

    /** 取投影上最好的若干个等距拟合（返回 {offset, spacing} 列表）。 */
    private static float[] topFits(float[] prof, int lines, int keep) {
        float bestScore[] = new float[keep];
        float bestOff[] = new float[keep];
        float bestSp[] = new float[keep];
        int mn = prof.length;
        for (float sp = mn / (float) (lines + 3.2f); sp <= mn / (float) (lines - 3.2f); sp += 0.5f) {
            int span = Math.round((lines - 1) * sp);
            if (span >= mn) continue;
            for (int off = 0; off <= mn - span - 1; off++) {
                float s = 0;
                for (int k = 0; k < lines; k++) {
                    int p = Math.round(off + k * sp);
                    float m = prof[p];
                    if (p > 0 && prof[p - 1] > m) m = prof[p - 1];
                    if (p + 1 < mn && prof[p + 1] > m) m = prof[p + 1];
                    s += m;
                }
                s /= lines;
                for (int q = 0; q < keep; q++) {
                    if (s > bestScore[q]) {
                        for (int z = keep - 1; z > q; z--) { bestScore[z] = bestScore[z - 1]; bestOff[z] = bestOff[z - 1]; bestSp[z] = bestSp[z - 1]; }
                        bestScore[q] = s; bestOff[q] = off; bestSp[q] = sp;
                        break;
                    }
                }
            }
        }
        if (bestScore[0] <= 0) return null;
        float[] out = new float[keep * 3];
        for (int q = 0; q < keep; q++) { out[q * 3] = bestOff[q]; out[q * 3 + 1] = bestSp[q]; out[q * 3 + 2] = bestScore[q]; }
        return out;
    }

    /** 最大亮块 / 暗块的外接框（用于收窄候选）。 */
    private static int[] componentBox(int[] lum, int sw, int sh, int thr, boolean bright) {
        byte[] mask = new byte[sw * sh];
        for (int i = 0; i < lum.length; i++) mask[i] = (byte) ((bright ? (lum[i] > 165) : (lum[i] < thr)) ? 1 : 0);
        int[] stack = new int[sw * sh];
        byte[] seen = new byte[sw * sh];
        int bx0 = 0, by0 = 0, bx1 = 0, by1 = 0, bestArea = 0;
        for (int sy = 0; sy < sh; sy++) {
            for (int sx = 0; sx < sw; sx++) {
                int idx = sy * sw + sx;
                if (mask[idx] == 0 || seen[idx] != 0) continue;
                int head = 0, tail = 0, area = 0, x0 = sx, x1 = sx, y0 = sy, y1 = sy;
                stack[tail++] = idx; seen[idx] = 1;
                while (head < tail) {
                    int cur = stack[head++]; area++;
                    int cx = cur % sw, cy = cur / sw;
                    if (cx < x0) x0 = cx; if (cx > x1) x1 = cx;
                    if (cy < y0) y0 = cy; if (cy > y1) y1 = cy;
                    for (int d = 0; d < 4; d++) {
                        int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = cy + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue;
                        int ni = ny * sw + nx;
                        if (mask[ni] != 0 && seen[ni] == 0) { seen[ni] = 1; stack[tail++] = ni; }
                    }
                }
                if (area > bestArea) { bestArea = area; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1; }
            }
        }
        if (bestArea < sw * sh * 0.02f) return null;
        return new int[] { bx0, by0, bx1, by1 };
    }

    /**
     * 按象棋规则上限裁剪：某棋种数量超限（如 3 个车）或总数超过 32 时，
     * 把置信度最低的那一格剔除，直到合法。离线实验：能把 33 子的非法结果变成 31 子的合法局面。
     */
    static int[] trimToLegal(int[] cells) {
        int before = count(cells);
        int[] out = cells.clone();
        int[] limit = { 1, 2, 2, 2, 2, 2, 5 };   // 帅仕相马车炮兵 各自上限
        for (int guard = 0; guard < 60; guard++) {
            int[] cnt = new int[16];
            int total = 0;
            for (int i = 0; i < 90; i++) {
                int p = out[i];
                if (p == 0) continue;
                total++;
                int t = Board.type(p);
                if (t < 1 || t > 7) { out[i] = 0; continue; }
                cnt[t + (Board.isRed(p) ? 0 : 8)]++;
            }
            int badKind = -1;
            for (int k = 0; k < 16; k++) {
                int t = k % 8;
                if (t < 1 || t > 7) continue;
                if (cnt[k] > limit[t - 1]) { badKind = k; break; }
            }
            if (badKind < 0 && total <= 32) break;
            // 找最低置信度的格子（优先从超限棋种里挑）
            int worst = -1;
            float worstConf = Float.MAX_VALUE;
            for (int i = 0; i < 90; i++) {
                int p = out[i];
                if (p == 0) continue;
                if (badKind >= 0) {
                    int k = Board.type(p) + (Board.isRed(p) ? 0 : 8);
                    if (k != badKind) continue;
                }
                float cf = OnnxOcr.lastCellConf[i];
                if (cf <= 0f) cf = 0.01f;
                if (cf < worstConf) { worstConf = cf; worst = i; }
            }
            if (worst < 0) break;
            out[worst] = 0;
        }
        if (before != count(out)) android.util.Log.i("xiangqi-ocr", "trimToLegal: " + before + " -> " + count(out));
        return out;
    }

    /** 对齐质量评分：棋子数 + 置信度 + 合法性（离线实验：比只看棋子数更准）。 */
    static float climbScore(int[] cells, float conf) {
        return count(cells) + conf * 20f + (plausible(cells) ? 3f : 0f);
    }

    /** 在投影上搜索 lines 条等距线的最优位置，返回 {起点偏移, 间距, 平均得分}。 */
    private static float[] fitGrid(float[] prof, int lines, float minSp, float maxSp) {
        float bestScore = -1, bestOff = 0, bestSp = 0;
        for (float sp = minSp; sp <= maxSp; sp += 0.5f) {
            int maxOff = prof.length - Math.round((lines - 1) * sp) - 1;
            for (int off = 0; off <= maxOff; off++) {
                float s = 0;
                for (int k = 0; k < lines; k++) {
                    int p = Math.round(off + k * sp);
                    float m = prof[p];
                    if (p > 0 && prof[p - 1] > m) m = prof[p - 1];
                    if (p + 1 < prof.length && prof[p + 1] > m) m = prof[p + 1];
                    s += m;
                }
                if (s > bestScore) { bestScore = s; bestOff = off; bestSp = sp; }
            }
        }
        if (bestScore <= 0) return null;
        return new float[] { bestOff, bestSp, bestScore / lines };
    }

    private static int lumAt(Bitmap bmp, int x, int y) {
        if (x < 0 || y < 0 || x >= bmp.getWidth() || y >= bmp.getHeight()) return 255;
        int c = bmp.getPixel(x, y);
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }

    /** 返回计数大于阈值的最长连续区间 [start, end]。 */
    private static int[] longestRun(int[] arr, int thr) {
        int bestS = -1, bestE = -1, cs = -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] >= thr) {
                if (cs < 0) cs = i;
                if (bestS < 0 || (i - cs) > (bestE - bestS)) { bestS = cs; bestE = i; }
            } else cs = -1;
        }
        if (bestS < 0) return null;
        return new int[] { bestS, bestE };
    }
}

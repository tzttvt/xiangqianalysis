package com.dsh.xiangqi;

import java.util.List;

/**
 * 悬浮窗扫描的"多帧融合"纯逻辑。抽成独立小类有两个原因：
 * 1) 这块最容易出"幽灵棋子"这种看起来像玄学的 bug，独立出来方便离线复现和上机自检；
 * 2) ShotService 原来把"上一帧的格面"同时用于遮挡回填和自动走子的稳定性判断，
 *    两个地方互相覆盖结果，必须拆开各存一份。
 *
 * 铁律：**只回填"几何上真的被悬浮面板压住"的格子**。
 * 曾经的做法是"新结果里数值为 0 的格子一律沿用上一帧"——那样被吃掉的子永远不会消失，
 * 因为每一帧都会把上一帧的值塞回去（幽灵棋子），而且投票会把错误锁死。
 */
public final class ScanFuse {

    private ScanFuse() { }

    /**
     * ★★ 2026-09-25：是否启用多帧投票（用户要求"试试直接出结果"时改成 false）。
     *
     * 保留投票是为了压掉单帧认错；但它有个副作用 —— **5 帧滑动窗口在票数接近时会来回翻**
     * （`[马,马,车,马,车]` → 挤掉最老的 → `[马,车,马,车,车]`，判决就从"马"翻成"车"），
     * 而每次翻转都被上层当成"局面变了"，把正在跑的引擎分析打断重开。
     * 关掉它，每帧的识别结果就是最终结论，不再有"窗口滑动导致的翻转"。
     * （单帧本身的识别抖动还在，那是识别层的事，跟这个开关无关。）
     */
    private static final boolean VOTE_ENABLED = true;

    /**
     * 面板遮挡回填：只对"中心点落在面板矩形内"的格子沿用上一帧的值。
     *
     * @param fresh       本帧检测结果
     * @param occ         上一帧（含遮挡回填）的格面，可为 null（首帧）
     * @param corners     本帧棋盘四角 TL,TR,BR,BL（图像坐标）
     * @param excludeRect {x0,y0,x1,y1} 面板矩形，可为 null（无面板）
     */
    public static int[] restoreOccluded(int[] fresh, int[] occ, float[] corners, int[] excludeRect) {
        if (fresh == null) return null;
        if (occ == null || corners == null || corners.length != 8 || excludeRect == null) return fresh;
        double[] hm = Ocr.homography(corners);
        if (hm == null) return fresh;
        int[] out = fresh.clone();
        for (int sq = 0; sq < 90; sq++) {
            if (out[sq] != 0) continue;          // 本帧认出来了就以本帧为准
            if (occ[sq] == 0) continue;
            double[] p = Ocr.apply(hm, Board.colOf(sq), Board.rowOf(sq));
            boolean inside = p[0] >= excludeRect[0] && p[0] <= excludeRect[2]
                    && p[1] >= excludeRect[1] && p[1] <= excludeRect[3];
            if (inside) out[sq] = occ[sq];       // 只有"确实被面板挡住"才沿用
        }
        return out;
    }

    /** 某个格子（按格面索引）的中心是否落在面板矩形内。 */
    public static boolean cellUnder(float[] corners, int sq, int[] excludeRect) {
        if (corners == null || corners.length != 8 || excludeRect == null) return false;
        double[] hm = Ocr.homography(corners);
        if (hm == null) return false;
        double[] p = Ocr.apply(hm, Board.colOf(sq), Board.rowOf(sq));
        return p[0] >= excludeRect[0] && p[0] <= excludeRect[2]
                && p[1] >= excludeRect[1] && p[1] <= excludeRect[3];
    }

    /**
     * 多帧多数投票，压掉偶发误判（同一局面连扫几帧，逐格取众数）。
     * 平票时取**最新**一帧：棋盘上的变化（走子/吃子）要尽快反映出来，
     * 不能因为历史帧占多数就把新结果顶掉——那是投票把局面锁死的根源。
     */
    public static int[] vote(List<int[]> history, int[] cur) {
        if (cur == null) return null;
        history.add(cur.clone());
        while (history.size() > 5) history.remove(0);
        // ★ 2026-09-25：开关关掉时**直接采用本帧结果**，不做跨帧投票
        //   （历史照常维护，方便随时打开开关回退到投票模式）
        if (!VOTE_ENABLED) return cur;
        int nf = history.size();
        if (nf < 2) return cur;
        int[] out = new int[90];
        for (int sq = 0; sq < 90; sq++) {
            int bestV = 0, bestC = -1;
            for (int f = nf - 1; f >= 0; f--) {          // 从最新往旧数，平票时最新那帧先被选中
                int v = history.get(f)[sq];
                int c = 0;
                for (int g = 0; g < nf; g++) if (history.get(g)[sq] == v) c++;
                if (c > bestC) { bestC = c; bestV = v; }
            }
            out[sq] = bestV;
        }
        return out;
    }
}

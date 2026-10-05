package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.provider.Settings;

/** 各界面/服务之间传递识别结果与权限检查。 */
public class Bridge {
    // ★ 2026-09-21：`autoPlay`（自动走子开关）已按用户要求删除 —— 悬浮窗不再走子，只分析给分。
    /** 扫图识谱得到的一盘棋（90 格，0 为空） */
    public static int[] pendingCells = null;
    /** 悬浮窗截屏待识别的位图 */
    public static Bitmap pendingShot = null;

    // ---------- 悬浮窗后台自动记的棋谱（2026-09-21 起） ----------
    // 悬浮窗只有 0.9 秒的实时搜索，棋力有限；但它可以边识别边把**整局**记下来，
    // 回到主界面就能对整盘做深度分析（棋谱 tab / 局势折线图 / 全盘分析）。
    /** 记谱起始局面的 FEN（用户打开悬浮窗时的局面）。 */
    public static volatile String gameFen = null;
    /** 按顺序的着法（`Board.makeMove(from,to)` 的编码）。 */
    public static volatile int[] gameMoves = null;
    /** 记录版本号：每记一手 +1，主界面用它判断"是不是新的一局/新的进展"。 */
    public static volatile int gameSeq = 0;

    public static boolean canDrawOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(c);
    }
}

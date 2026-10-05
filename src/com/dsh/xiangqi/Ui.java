package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 视觉规范：白底暖色调（参考 Pro象棋），Tab 选中浅蓝高亮。 */
public class Ui {

    public static final int BG = 0xFFFBF7F0;          // 页面底色（暖白）
    public static final int CARD = 0xFFFFFFFF;        // 卡片
    public static final int CARD2 = 0xFFF6EFE4;       // 次级底
    public static final int STROKE = 0xFFE8DCC8;      // 暖色描边
    public static final int TEXT = 0xFF3B2F26;        // 主文字（暖褐）
    public static final int SUB = 0xFF8C7A66;         // 次要文字
    public static final int ACCENT = 0xFFE07B39;      // 强调色（暖橙）
    public static final int ACCENT_DARK = 0xFFB85C1E;
    public static final int TAB_ACTIVE_BG = 0xFFE3F1FE;  // Tab 选中底（浅蓝）
    public static final int TAB_ACTIVE_TEXT = 0xFF1E6FD9; // Tab 选中字（蓝）
    public static final int TAB_TEXT = 0xFF8C7A66;
    public static final int LINE = 0xFFF0E6D6;        // 表格分割线

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /**
     * 圆角矩形底。★ 圆角半径统一走这里 —— 用户要求"圆角做小一点，减半"，
     * 所以下面所有半径都比原来小一半（12→6、10→5、8→4、6→3、4→2）。
     */
    public static GradientDrawable round(int fill, float radiusDp, Context c, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) d.setStroke(dp(c, 1f), strokeColor);
        return d;
    }

    /** 方角卡片（用户要求：不要圆框，省空间） */
    public static LinearLayout card(Context c, int paddingDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(CARD);
        int p = dp(c, Math.min(paddingDp, 6));
        l.setPadding(p, p, p, p);
        return l;
    }

    public static TextView chip(Context c, String text, int textColor, int bgColor) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setBackground(round(bgColor, 3f, c, 0));
        return t;
    }

    public static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(primary ? 14f : 13f);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL));
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        b.setBackground(round(primary ? ACCENT : CARD, 2f, c, primary ? 0 : STROKE));
        b.setPadding(0, dp(c, 9), 0, dp(c, 9));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setElevation(0f);
        return b;
    }

    /** Tab 按钮：选中时浅蓝底 + 蓝字。 */
    public static TextView tab(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 10), 0, dp(c, 10));
        setTabActive(t, false, c);
        return t;
    }

    public static void setTabActive(TextView t, boolean active, Context c) {
        t.setTextColor(active ? TAB_ACTIVE_TEXT : TAB_TEXT);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL));
        t.setBackground(round(active ? TAB_ACTIVE_BG : 0x00000000, 5f, c, active ? 0xFFBBDDFB : 0));
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight, Context c, float marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        int m = dp(c, marginDp);
        p.setMargins(m, m, m, m);
        return p;
    }
}

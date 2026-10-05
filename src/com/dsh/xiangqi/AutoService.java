package com.dsh.xiangqi;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：把引擎算出的最佳着法通过模拟点击落到目标象棋 App 的棋盘上。
 * 注意：本类里所有系统调用都包在 try/catch 内，避免绑定期抛异常导致系统提示"此服务出现故障"。
 */
public class AutoService extends AccessibilityService {

    /**
     * `AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES` 的真值 —— 从
     * `platform34\android.jar` 的 `AccessibilityServiceInfo.class` 里解出来的常量：
     * <pre>
     *   CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT            =   1
     *   CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION          =   2
     *   CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS          =   8
     *   CAPABILITY_CAN_CONTROL_MAGNIFICATION              =  16   ← ★ 别再拿它当手势位
     *   CAPABILITY_CAN_PERFORM_GESTURES                   =  32   ← 模拟点击就是这一位
     *   CAPABILITY_CAN_TAKE_SCREENSHOT                    = 128
     * </pre>
     * 之前代码里三处都写成 16，那一位其实是"放大手势"，跟模拟点击没关系：
     * 日志里的 `gestureBit` 一直报的是放大位，而"发现缺位就补 16"的自愈逻辑补的也是放大能力，
     * 等于**这个检查从来没有真的检查过手势位**。手势能用只是因为 XML 里声明了 canPerformGestures。
     */
    private static final int CAP_CAN_PERFORM_GESTURES = 32;

    /** 无障碍截屏能力位（API 30+）。没有这一位，`takeScreenshot()` 会返回 NO_ACCESSIBILITY_ACCESS(2)。 */
    private static final int CAP_CAN_TAKE_SCREENSHOT = 128;

    private static volatile AutoService instance;

    public static AutoService get() { return instance; }
    public static boolean isRunning() { return instance != null; }

    /** 无障碍截屏这条路现在能不能走：系统 >= 30 且无障碍服务已连接。 */
    public static boolean accShotReady() {
        return Build.VERSION.SDK_INT >= 30 && instance != null;
    }

    /**
     * 无障碍截屏：**替代 MediaProjection 的第二条抓屏路**。
     * 好处是不用投屏授权弹窗、没有"屏幕共享中"常驻提示；代价是系统会限流（见 {@link AccShot}）。
     *
     * ★ 版本判断必须在这里做、`AccShot` 只能在这个分支里被引用 —— minSdk 是 21，
     * 低版本加载 `AccShot` 会 NoClassDefFoundError。详见 {@link AccShotCb} 的注释。
     * @return true 表示已经把请求发出去了（结果由 cb 回调）
     */
    public static boolean takeShot(java.util.concurrent.Executor ex, AccShotCb cb) {
        if (instance == null) { cb.onShot(null, "无障碍服务没在运行"); return false; }
        if (Build.VERSION.SDK_INT < 30) { cb.onShot(null, "需要 Android 11 及以上"); return false; }
        AccShot.take(instance, ex, cb);
        return true;
    }

    @Override
    protected void onServiceConnected() {
        try {
            super.onServiceConnected();
            instance = this;
            Diag.log("AutoService", "connected, sdk=" + Build.VERSION.SDK_INT);
            // 先看 XML 声明的能力位（v2.7 起构建时会手写 AXML，确保 canPerformGestures 生效）
            try {
                AccessibilityServiceInfo info = getServiceInfo();
                if (info == null) {
                    Diag.log("AutoService", "getServiceInfo=null");
                } else {
                    java.lang.reflect.Method get = info.getClass().getMethod("getCapabilities");
                    int declared = (Integer) get.invoke(info);
                    Diag.log("AutoService", "declared capabilities=" + declared
                            + " gestureBit=" + (declared & CAP_CAN_PERFORM_GESTURES)
                            + " screenshotBit=" + (declared & CAP_CAN_TAKE_SCREENSHOT)
                            + " (=32 模拟点击 / =128 截屏)");
                    if ((declared & CAP_CAN_PERFORM_GESTURES) == 0) {
                        try {
                            info.getClass().getMethod("setCapabilities", int.class)
                                    .invoke(info, declared | CAP_CAN_PERFORM_GESTURES);
                            setServiceInfo(info);
                            int after = (Integer) get.invoke(getServiceInfo());
                            Diag.log("AutoService", "runtime set -> " + after
                                    + " gestureBit=" + (after & CAP_CAN_PERFORM_GESTURES));
                        } catch (Throwable t2) {
                            Diag.log("AutoService", "runtime set failed (ROM限制): " + t2);
                        }
                    }
                }
            } catch (Throwable t2) {
                Diag.log("AutoService", "capability check failed: " + t2);
            }
        } catch (Throwable t) {
            Diag.log("AutoService", "onServiceConnected failed: " + t);
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        try { instance = null; Diag.log("AutoService", "unbind"); } catch (Throwable ignored) { }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        try { instance = null; Diag.log("AutoService", "destroy"); } catch (Throwable ignored) { }
        try { super.onDestroy(); } catch (Throwable ignored) { }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    public boolean tap(float x, float y) {
        return tapTwice(x, y, x, y, 0);
    }

    /** 依次点两下（起点 → 终点）。 */
    public boolean tapTwice(float x1, float y1, float x2, float y2, long gapMs) {
        try {
            if (Build.VERSION.SDK_INT < 24) { Diag.log("AutoService", "sdk<24 no gesture"); return false; }
            GestureDescription.Builder b = new GestureDescription.Builder();
            Path p1 = new Path(); p1.moveTo(safe(x1), safe(y1));
            b.addStroke(new GestureDescription.StrokeDescription(p1, 0, 60));
            if (gapMs > 0 || Math.abs(x2 - x1) > 1 || Math.abs(y2 - y1) > 1) {
                Path p2 = new Path(); p2.moveTo(safe(x2), safe(y2));
                b.addStroke(new GestureDescription.StrokeDescription(p2, Math.max(80, gapMs), 60));
            }
            boolean ok = dispatchGesture(b.build(), null, null);
            Diag.log("AutoService", "tapTwice(" + (int) x1 + "," + (int) y1 + " -> " + (int) x2 + "," + (int) y2 + ") = " + ok);
            return ok;
        } catch (Throwable t) {
            Diag.log("AutoService", "tapTwice failed: " + t);
            return false;
        }
    }

    private static float safe(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 1f;
        if (v < 1f) return 1f;
        return v;
    }
}

package com.dsh.xiangqi;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.view.Display;

/**
 * 无障碍截屏（`AccessibilityService.takeScreenshot()`，**API 30+**）的隔离类。
 *
 * ★ 这个类**只能在 `Build.VERSION.SDK_INT >= 30` 的分支里被引用**。
 * 项目 minSdk 是 21（Android 5.0），而这个类的方法签名/代码体引用了 API 30 的
 * `TakeScreenshotCallback` / `ScreenshotResult`。低版本系统加载它就会 NoClassDefFoundError，
 * 所以调用方（`AutoService.takeShot`）必须先查版本号再走到这里。
 *
 * 相比投屏（MediaProjection）抓屏的好处：
 *   ① 不用每次弹"开始录制/共享屏幕"的授权窗（MediaProjection 每起一次会话都要用户点一次）；
 *   ② 状态栏不会常驻"屏幕共享中"提示。
 * 代价：系统会**限流**，调用过频会拿到 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT(3)，
 * 所以不能无脑高频轮询，必须把失败当成一次普通的"这一帧没拿到"，沿用上一帧结果。
 */
public final class AccShot {

    private AccShot() { }

    /**
     * 把无障碍截图结果转成**软件位图**。
     * `wrapHardwareBuffer` 出来的是 `Config.HARDWARE`（GPU 上的、不可改、取不到像素），
     * 而我们下面要 `getPixels` + Canvas 缩放，所以必须 copy 一份 ARGB_8888 出来。
     * HardwareBuffer 是调用方负责释放的，这里用完立刻 close。
     */
    private static Bitmap toSoftwareBitmap(AccessibilityService.ScreenshotResult r) {
        HardwareBuffer hb = null;
        try {
            hb = r.getHardwareBuffer();
            if (hb == null) return null;
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
            if (hw == null) return null;
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            return sw;
        } finally {
            if (hb != null) { try { hb.close(); } catch (Throwable ignored) { } }
        }
    }

    /** 截一张整屏。结果一定通过 cb 回调（成功或失败都回调，绝不静默）。 */
    public static void take(AccessibilityService svc, java.util.concurrent.Executor ex, final AccShotCb cb) {
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, ex,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            try {
                                Bitmap b = toSoftwareBitmap(result);
                                cb.onShot(b, b == null ? "截图转位图失败" : null);
                            } catch (Throwable t) {
                                cb.onShot(null, "截图解码异常: " + t);
                            }
                        }
                        @Override public void onFailure(int errorCode) {
                            cb.onShot(null, errName(errorCode));
                        }
                    });
        } catch (Throwable t) {
            cb.onShot(null, "takeScreenshot 调用失败: " + t);
        }
    }

    /**
     * 错误码 -> 人话。数值是从 `platform34\android.jar` 的 `AccessibilityService.class`
     * 常量池里解出来的（`jre17` 是 JRE、没有 javap，只能手工解析）：
     * 1=INTERNAL_ERROR 2=NO_ACCESSIBILITY_ACCESS 3=INTERVAL_TIME_SHORT
     * 4=INVALID_DISPLAY 5=INVALID_WINDOW 6=SECURE_WINDOW
     */
    public static String errName(int code) {
        switch (code) {
            case 1: return "系统内部错误";
            case 2: return "无障碍服务没有截屏权限（XML 里缺 canTakeScreenshot）";
            case 3: return "调用太频繁被限流";
            case 4: return "display 无效";
            case 5: return "窗口无效";
            case 6: return "画面属于安全窗口，禁止截屏";
            default: return "未知错误码 " + code;
        }
    }
}

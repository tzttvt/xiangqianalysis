package com.dsh.xiangqi;

import android.graphics.Bitmap;

/**
 * 无障碍截屏的回调。
 *
 * ★ 为什么单独拎成一个顶层接口，而不是塞进 {@link AccShot} 里当内部接口：
 * 项目 minSdk 是 21。如果把回调定义在 AccShot 内部，那 `AutoService` 只要在**方法签名**里
 * 提到 `AccShot.Cb`，类加载器就会去加载 `AccShot` 这个类；而 AccShot 的方法体和字段引用了
 * API 30 的 `AccessibilityService.TakeScreenshotCallback` / `ScreenshotResult`，
 * 在 Android 5~10 上加载它就会 NoClassDefFoundError，整个无障碍服务跟着挂掉。
 * 分成两个类之后，`AccShot` 只在 `SDK_INT >= 30` 的分支里被引用，低版本永远不加载。
 */
public interface AccShotCb {
    /**
     * @param bmp 成功时是整屏图的**软件位图副本**（ARGB_8888）；失败时为 null
     * @param err 失败原因（人话），成功时为 null
     */
    void onShot(Bitmap bmp, String err);
}

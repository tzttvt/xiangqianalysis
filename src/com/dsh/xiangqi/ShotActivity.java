package com.dsh.xiangqi;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** 悬浮窗识谱入口：先申请屏幕捕获权限，再启动悬浮服务。 */
public class ShotActivity extends Activity {
    private static final int REQ = 2001, REQ_NOTIF = 2002;
    /** 无障碍截屏模式：**不需要**录屏授权，直接起服务。 */
    private boolean accShot = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.Log.i("xiangqi-ocr", "ShotActivity onCreate");
        accShot = getIntent() != null && getIntent().getBooleanExtra(ShotService.EXTRA_ACC_SHOT, false);
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, REQ_NOTIF);
            }
        } catch (Throwable ignored) { }
        // 无障碍截屏这条路不用投屏授权：直接起服务，省掉那个"开始录制"的弹窗
        if (accShot) {
            android.util.Log.i("xiangqi-ocr", "ShotActivity accShot -> skip consent");
            try {
                Intent svc = new Intent(this, ShotService.class);
                svc.setAction(ShotService.ACTION_START);
                svc.putExtra(ShotService.EXTRA_ACC_SHOT, true);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                // 悬浮窗本身可见，无需重复弹启动提示。
            } catch (Throwable t) {
                android.util.Log.e("xiangqi-ocr", "start service(acc) failed: " + t);
                Diag.log("Shot", "启动悬浮服务失败：" + t);
            }
            finish();
            return;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                Diag.log("Shot", "该设备不支持屏幕捕获");
                finish();
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "consent failed: " + t);
            Diag.log("Shot", "录屏授权失败：" + t);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        try {
            if (req == REQ && res == RESULT_OK && data != null) {
                Intent svc = new Intent(this, ShotService.class);
                svc.setAction(ShotService.ACTION_START);
                svc.putExtra("code", res);
                svc.putExtra("data", data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                // 成功后直接显示小棋盘，不再提示旧版“自动走子”。
            } // 用户取消系统授权时直接返回，不追加提示。
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "start service failed: " + t);
            Diag.log("Shot", "启动悬浮服务失败：" + t);
        }
        finish();
    }
}

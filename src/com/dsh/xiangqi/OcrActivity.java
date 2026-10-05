package com.dsh.xiangqi;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * 扫图识谱：打开即进相册 → 选完立刻自动识别 → 成功就直接载入，
 * 失败才让你手动点四个角（自动识别的定位结果也会先画出来供微调）。
 */
public class OcrActivity extends Activity {

    private static final int REQ_PICK = 1001, REQ_PERM = 1002;

    private ImageViewEx view;
    private TextView hint;
    private Bitmap bitmap;
    private float[] corners;
    private boolean busy = false;
    private boolean autoRun = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());

        String path = getIntent().getStringExtra("imagePath");
        if (path != null) {
            Bitmap bm = BitmapFactory.decodeFile(path);
            if (bm != null) { afterPick(bm); return; }
        }
        // 直接进相册，不再让用户多点一次
        hint.setText("正在打开相册…");
        view.postDelayed(new Runnable() { public void run() { pick(); } }, 250);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 10);
        root.setPadding(pad, pad, pad, pad);

        hint = new TextView(this);
        hint.setTextColor(Ui.TEXT);
        hint.setTextSize(13f);
        hint.setText("识别中…");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(hint, hp);

        LinearLayout card = Ui.card(this, 6);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cp.topMargin = Ui.dp(this, 8);
        view = new ImageViewEx(this);
        view.setListener(new Runnable() {
            public void run() {
                corners = view.getCorners();
                hint.setText("已标定 " + (corners == null ? 0 : corners.length / 2) + "/4 个角（左上→右上→右下→左下）");
            }
        });
        card.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(card, cp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(this, 8);
        root.addView(row, rp);
        row.addView(btn("重选图片", new View.OnClickListener() { public void onClick(View v) { pick(); } }));
        row.addView(btn("清空标定", new View.OnClickListener() {
            public void onClick(View v) { view.clearCorners(); corners = null; hint.setText("已清除标定点，请依次点棋盘四个角"); }
        }));
        row.addView(btn("识别", new View.OnClickListener() { public void onClick(View v) { doRecognize(corners); } }));
        return root;
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = Ui.button(this, t, false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(this, 4);
        lp.setMargins(m, 0, m, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void pick() {
        String perm = Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { perm }, REQ_PERM);
            return;
        }
        openGallery();
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        i.setType("image/*");
        try { startActivityForResult(i, REQ_PICK); }
        catch (Throwable t) {
            Intent i2 = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i2.addCategory(Intent.CATEGORY_OPENABLE);
            i2.setType("image/*");
            startActivityForResult(i2, REQ_PICK);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(req, perms, granted);
        if (req == REQ_PERM) openGallery();   // 授权与否都继续，没权限也能用系统选择器
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { finish(); return; }
        try {
            Uri u = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream is = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(is, null, o);
            if (is != null) is.close();
            int scale = 1;
            while (o.outWidth / scale > 1800 || o.outHeight / scale > 1800) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            InputStream is2 = getContentResolver().openInputStream(u);
            Bitmap bm = BitmapFactory.decodeStream(is2, null, o2);
            if (is2 != null) is2.close();
            if (bm == null) { Diag.log("Ocr", "读不到这张图"); finish(); return; }
            afterPick(bm);
        } catch (Throwable t) {
            Diag.log("Ocr", "打开图片失败：" + t.getMessage());
            finish();
        }
    }

    /** 选完图后：自动定位 + 自动识别，一步到位。 */
    private void afterPick(final Bitmap bm) {
        bitmap = bm;
        corners = null;
        view.setBitmap(bm);
        view.setCorners(null);
        hint.setText("已选图 " + bm.getWidth() + "×" + bm.getHeight() + "，正在自动识别…");
        if (autoRun) return;
        autoRun = true;
        new Thread(new Runnable() {
            public void run() {
                DetOcr.init(getApplicationContext());    // YOLO26s 棋子检测器（主力）
                OnnxOcr.init(getApplicationContext());
                OnnxPose.init(getApplicationContext());
                OnnxRec.init(getApplicationContext());   // PP-OCR 读字模型
                // ★★★ 2026-09-29：这里是**静态单张图**识别，「扫图」页反复点「识别」时，
                //   不能让上一次的粘性框/旋转结论影响这一次 —— 否则同一张图时对时错。
                DetOcr.resetSticky();
                final float[] c = Ocr.detectBoardSmart(bm);
                if (c != null) {
                    final int[] cells = Ocr.recognize(bm, c);
                    final boolean okCells = cells != null;
                    runOnUiThread(new Runnable() { public void run() {
                        if (okCells) { corners = c; view.setCorners(c); hint.setText("已定位棋盘，识别中…"); }
                        else { corners = null; view.setCorners(null); hint.setText("自动定位不准，请点棋盘四个角（左上→右上→右下→左下）再点「识别」"); }
                    } });
                    runOnUiThread(new Runnable() { public void run() { autoRun = false; finishWith(cells, bm); } });
                } else {
                    final int[] cells = Ocr.recognize(bm);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            autoRun = false;
                            if (cells != null) { finishWith(cells, bm); return; }
                            hint.setText("自动定位失败：请贴着棋盘外框依次点选四个角（左上→右上→右下→左下），再点「识别」");
                        }
                    });
                }
            }
        }).start();
    }

    private void finishWith(int[] cells, Bitmap bm) {
        if (cells == null) {
            hint.setText("没认出棋子：请点选棋盘四角后点「识别」再试一次");
            return;
        }
        int n = 0;
        for (int i = 0; i < 90; i++) if (cells[i] > 0) n++;
        Bridge.pendingCells = cells;
        Bridge.pendingShot = bm;
        // 成功后会直接回主棋盘显示识别结果，不再弹重复提示。
        Intent back = new Intent(this, MainActivity.class);
        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(back);
        finish();
    }

    private void doRecognize(final float[] c) {
        if (bitmap == null) { hint.setText("请先选图片"); return; }
        if (busy) return;
        busy = true;
        hint.setText("识别中…（首次会加载识别模型，约几秒）");
        new Thread(new Runnable() {
            public void run() {
                DetOcr.init(getApplicationContext());
                OnnxOcr.init(getApplicationContext());
                OnnxRec.init(getApplicationContext());
                DetOcr.resetSticky();    // ★ 静态图：别受上一次识别残留影响（见 recognize 上方注释）
                final int[] cells = (c != null && c.length == 8) ? Ocr.recognize(bitmap, c) : Ocr.recognize(bitmap);
                runOnUiThread(new Runnable() {
                    public void run() { busy = false; finishWith(cells, bitmap); }
                });
            }
        }).start();
    }

    /** 图片显示 + 四角标定（也能显示自动定位的网格线）。 */
    static class ImageViewEx extends View {
        private Bitmap bmp;
        private final float[] pts = new float[8];
        private int count = 0;
        private Runnable listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ImageViewEx(android.content.Context c) { super(c); }

        void setListener(Runnable r) { listener = r; }
        void setBitmap(Bitmap b) { bmp = b; count = 0; invalidate(); }
        float[] getCorners() { return count == 4 ? pts.clone() : null; }
        void clearCorners() { count = 0; invalidate(); }
        void setCorners(float[] c) {
            if (c == null) { count = 0; invalidate(); return; }
            System.arraycopy(c, 0, pts, 0, 8);
            count = 4;
            invalidate();
        }

        private float scale() {
            if (bmp == null) return 1;
            return Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        }

        private float offsetX() { return (getWidth() - bmp.getWidth() * scale()) / 2f; }
        private float offsetY() { return (getHeight() - bmp.getHeight() * scale()) / 2f; }

        @Override
        protected void onDraw(Canvas cv) {
            if (bmp == null) return;
            float s = scale(), ox = offsetX(), oy = offsetY();
            cv.drawBitmap(bmp, null, new RectF(ox, oy, ox + bmp.getWidth() * s, oy + bmp.getHeight() * s), null);
            if (count == 4) {
                // 画出自动定位的网格，便于判断定位准不准
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                p.setColor(0xCC1E88E5);
                for (int r = 0; r <= 9; r++) {
                    float t = r / 9f;
                    float x0 = ox + (pts[0] + (pts[6] - pts[0]) * t) * s, y0 = oy + (pts[1] + (pts[7] - pts[1]) * t) * s;
                    float x1 = ox + (pts[2] + (pts[4] - pts[2]) * t) * s, y1 = oy + (pts[3] + (pts[5] - pts[3]) * t) * s;
                    cv.drawLine(x0, y0, x1, y1, p);
                }
                for (int c = 0; c <= 8; c++) {
                    float t = c / 8f;
                    float x0 = ox + (pts[0] + (pts[2] - pts[0]) * t) * s, y0 = oy + (pts[1] + (pts[3] - pts[1]) * t) * s;
                    float x1 = ox + (pts[6] + (pts[4] - pts[6]) * t) * s, y1 = oy + (pts[7] + (pts[5] - pts[7]) * t) * s;
                    cv.drawLine(x0, y0, x1, y1, p);
                }
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setColor(0xFFFF5252);
            for (int i = 0; i < count; i++) cv.drawCircle(ox + pts[i * 2] * s, oy + pts[i * 2 + 1] * s, 12f, p);
            // 放大镜：手指附近 3 倍放大，方便对准棋盘角
            if (magnifying && magX > 0) {
                float R = 120f, zoom = 3f;
                float cx = Math.max(R + 4, Math.min(getWidth() - R - 4, magX));
                float cy = Math.max(R + 4, Math.min(getHeight() - R - 4, magY - R - 30));
                cv.save();
                cv.clipPath(makeCircle(cx, cy, R));
                cv.drawBitmap(bmp, null, new RectF(cx - R * zoom, cy - R * zoom, cx + R * zoom, cy + R * zoom), null);
                cv.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(4f);
                p.setColor(0xFF1E88E5);
                cv.drawCircle(cx, cy, R, p);
                cv.drawLine(cx - 26, cy, cx + 26, cy, p);
                cv.drawLine(cx, cy - 26, cx, cy + 26, p);
            }
        }

        private android.graphics.Path makeCircle(float cx, float cy, float r) {
            android.graphics.Path path = new android.graphics.Path();
            path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            return path;
        }

        private float magX = -1, magY = -1;
        private boolean magnifying = false;

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (bmp == null) return true;
            if (e.getAction() == MotionEvent.ACTION_MOVE) { magX = e.getX(); magY = e.getY(); invalidate(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP) { magnifying = false; invalidate(); }
            if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
            magX = e.getX(); magY = e.getY(); magnifying = true;
            float s = scale(), ox = offsetX(), oy = offsetY();
            float ix = (e.getX() - ox) / s, iy = (e.getY() - oy) / s;
            // 长按 = 放大镜模式（便于精确点角），普通点按 = 落点
            if (e.getEventTime() - e.getDownTime() > 400) { invalidate(); return true; }
            if (count >= 4) count = 0;
            pts[count * 2] = ix;
            pts[count * 2 + 1] = iy;
            count++;
            magnifying = true;
            invalidate();
            if (listener != null) listener.run();
            return true;
        }
    }
}

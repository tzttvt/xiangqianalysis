package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * RTMPose 棋盘四角检测（开源项目 TheOne1006/chinese-chess-recognition 的 pose 模型，int8 量化 2.8MB）。
 * 对真实照片（含透视、反光、摩尔纹）也能稳定找出棋盘四个角，是自动识谱的第一道关。
 */
public class OnnxPose {

    private static final int IN = 256;
    private static final float PADDING = 1.25f;
    private static final float[] MEAN = { 123.675f, 116.28f, 103.53f };
    private static final float[] STD = { 58.395f, 57.12f, 57.375f };

    private static OrtEnvironment env;
    private static OrtSession session;
    private static boolean failed = false;
    /** 最近一次检测的平均置信度。 */
    public static float lastConfidence = 0f;

    public static boolean isReady() { return session != null; }

    public static boolean init(Context ctx) {
        if (session != null) return true;
        if (failed) return false;
        try {
            File f = new File(ctx.getFilesDir(), "pose.onnx");
            if (!f.exists() || f.length() < 500000) {
                InputStream is = ctx.getAssets().open("pose.onnx");
                FileOutputStream os = new FileOutputStream(f);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                is.close();
            }
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(2);
            session = env.createSession(f.getAbsolutePath(), o);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "OnnxPose.init failed: " + t);
            failed = true;
            return false;
        }
    }

    /** 返回棋盘四角，顺序为 左上, 右上, 右下, 左下；失败返回 null。 */
    public static float[] detect(Bitmap src) {
        return detect(src, 0, 0, src.getWidth(), src.getHeight());
    }

    /** 只在指定区域里找棋盘（竖长截图里棋盘只占一小块，整图喂进去模型会找不准）。 */
    public static float[] detect(Bitmap src, int bx0, int by0, int bx1, int by1) {
        if (session == null) return null;
        try {
            int W = bx1 - bx0, H = by1 - by0;
            if (W < 40 || H < 40) return null;
            float cx = bx0 + W / 2f, cy = by0 + H / 2f;
            float s = Math.max(W, H) * PADDING;
            // 目标三点：中心、中心+(-s/2,0)、第三点（旋转 90°）
            float[] srcPts = new float[] { cx, cy, cx - s / 2f, cy };
            float thirdSx = srcPts[2], thirdSy = srcPts[3];
            float dx = srcPts[0] - thirdSx, dy = srcPts[1] - thirdSy;
            srcPts = new float[] { srcPts[0], srcPts[1], srcPts[2], srcPts[3], thirdSx - dy, thirdSy + dx };
            float[] dstPts = new float[] { IN / 2f, IN / 2f, 0f, IN / 2f, IN / 2f, IN };
            Matrix m = affine3(srcPts, dstPts);
            Bitmap warped = Bitmap.createBitmap(IN, IN, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(warped);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(src, m, paint);

            int[] px = new int[IN * IN];
            warped.getPixels(px, 0, IN, 0, 0, IN, IN);
            warped.recycle();
            float[] data = new float[3 * IN * IN];
            int plane = IN * IN;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                data[i] = (Color.red(c) - MEAN[0]) / STD[0];
                data[plane + i] = (Color.green(c) - MEAN[1]) / STD[1];
                data[2 * plane + i] = (Color.blue(c) - MEAN[2]) / STD[2];
            }
            long[] shape = { 1, 3, IN, IN };
            OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap("input", input);
            OrtSession.Result out = session.run(inputs);
            float[][][] sx = (float[][][]) out.get(0).getValue();   // [1,4,512]
            float[][][] sy = (float[][][]) out.get(1).getValue();
            input.close();
            out.close();

            Matrix inv = new Matrix();
            if (!m.invert(inv)) return null;
            float[] pts = new float[8];
            float conf = 0;
            for (int k = 0; k < 4; k++) {
                int bx = 0, by = 0;
                float mvx = sx[0][k][0], mvy = sy[0][k][0];
                for (int j = 1; j < 512; j++) {
                    if (sx[0][k][j] > mvx) { mvx = sx[0][k][j]; bx = j; }
                    if (sy[0][k][j] > mvy) { mvy = sy[0][k][j]; by = j; }
                }
                pts[k * 2] = (bx + 0.5f) / 512f * IN;
                pts[k * 2 + 1] = (by + 0.5f) / 512f * IN;
                conf += mvx * mvy;
            }
            inv.mapPoints(pts);
            android.util.Log.i("xiangqi-ocr", "pose conf=" + String.format("%.2f", conf / 4f)
                    + " TL=" + (int) pts[0] + "," + (int) pts[1] + " TR=" + (int) pts[2] + "," + (int) pts[3]
                    + " BL=" + (int) pts[4] + "," + (int) pts[5] + " BR=" + (int) pts[6] + "," + (int) pts[7]);
            lastConfidence = conf / 4f;
            if (lastConfidence < 0.05f) return null;
            // 顺序：左上, 右上, 右下, 左下（与本项目其余代码一致）
            return new float[] { pts[0], pts[1], pts[2], pts[3], pts[6], pts[7], pts[4], pts[5] };
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "OnnxPose.detect failed: " + t);
            return null;
        }
    }

    /** 由 3 组对应点求仿射矩阵（Android Matrix，src->dst）。 */
    private static Matrix affine3(float[] s, float[] d) {
        double[][] P = { { s[0], s[2], s[4] }, { s[1], s[3], s[5] }, { 1, 1, 1 } };
        double[][] Q = { { d[0], d[2], d[4] }, { d[1], d[3], d[5] }, { 1, 1, 1 } };
        double det = P[0][0] * (P[1][1] * P[2][2] - P[1][2] * P[2][1])
                   - P[0][1] * (P[1][0] * P[2][2] - P[1][2] * P[2][0])
                   + P[0][2] * (P[1][0] * P[2][1] - P[1][1] * P[2][0]);
        if (Math.abs(det) < 1e-9) return new Matrix();
        double[][] Pi = new double[3][3];
        Pi[0][0] = (P[1][1] * P[2][2] - P[1][2] * P[2][1]) / det;
        Pi[0][1] = (P[0][2] * P[2][1] - P[0][1] * P[2][2]) / det;
        Pi[0][2] = (P[0][1] * P[1][2] - P[0][2] * P[1][1]) / det;
        Pi[1][0] = (P[1][2] * P[2][0] - P[1][0] * P[2][2]) / det;
        Pi[1][1] = (P[0][0] * P[2][2] - P[0][2] * P[2][0]) / det;
        Pi[1][2] = (P[0][2] * P[1][0] - P[0][0] * P[1][2]) / det;
        Pi[2][0] = (P[1][0] * P[2][1] - P[1][1] * P[2][0]) / det;
        Pi[2][1] = (P[0][1] * P[2][0] - P[0][0] * P[2][1]) / det;
        Pi[2][2] = (P[0][0] * P[1][1] - P[0][1] * P[1][0]) / det;
        double[][] A = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                double v = 0;
                for (int k = 0; k < 3; k++) v += Q[i][k] * Pi[k][j];
                A[i][j] = v;
            }
        Matrix m = new Matrix();
        m.setValues(new float[] {
            (float) A[0][0], (float) A[0][1], (float) A[0][2],
            (float) A[1][0], (float) A[1][1], (float) A[1][2],
            0f, 0f, 1f });
        return m;
    }
}

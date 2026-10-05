package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * PaddleOCR PP-OCR 识别模型（rec.onnx + keys.txt）：读一行文字。
 * 用来读"棋子上的字"，因此对棋子画风不挑（木纹棋盘、3D 棋子都能读）。
 *
 * 关键预处理（之前输出乱码就是这里没做对）：
 *   1) 裁剪图缩放到高 48（等比，宽度上限 320；rec.onnx 高宽都是动态维）
 *   2) 归一化 (v/255 - 0.5) / 0.5，CHW
 *   3) 输出 [1,T,6625]，贪心 CTC：index 0 = blank，index i → dict[i-1]
 */
public class OnnxRec {

    private static OrtEnvironment env;
    private static OrtSession session;
    private static boolean failed = false;
    private static String[] dict = new String[0];
    /** 诊断用：应用 context（PieceOcr 存调试图用）。 */
    public static android.content.Context appCtx;

    /** 最近一次读字的平均峰概率（0~1）。 */
    public static float lastScore = 0f;
    /** 最近一次读出的文字（诊断用）。 */
    public static String lastText = "";

    private static final int H = 48, MAXW = 320, BLANK = 0;

    public static boolean isReady() { return session != null; }

    public static boolean init(Context ctx) {
        if (session != null) return true;
        if (failed) return false;
        try {
            File f = new File(ctx.getFilesDir(), "rec.onnx");
            if (!f.exists() || f.length() < 1000000) {
                InputStream is = ctx.getAssets().open("rec.onnx");
                FileOutputStream os = new FileOutputStream(f);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                is.close();
            }
            InputStream ks = ctx.getAssets().open("keys.txt");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b2 = new byte[1 << 16];
            int m;
            while ((m = ks.read(b2)) > 0) bo.write(b2, 0, m);
            ks.close();
            String[] lines = new String(bo.toByteArray(), StandardCharsets.UTF_8).split("\n", -1);
            int len = lines.length;
            while (len > 0 && lines[len - 1].isEmpty()) len--;
            dict = new String[len];
            System.arraycopy(lines, 0, dict, 0, len);
            appCtx = ctx.getApplicationContext();
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(2);
            session = env.createSession(f.getAbsolutePath(), o);
            android.util.Log.i("xiangqi-ocr", "OnnxRec.init dict=" + dict.length);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "OnnxRec.init failed: " + t);
            failed = true;
            return false;
        }
    }

    /** 读图上的文字，返回解码串（可能为空串）；lastScore 为平均峰概率。 */
    public static String read(Bitmap patch) {
        lastScore = 0f;
        lastText = "";
        if (session == null || patch == null) return "";
        try {
            int w = patch.getWidth(), h = patch.getHeight();
            if (w < 3 || h < 3) return "";
            int nw = Math.min(MAXW, Math.max(8, Math.round(H * w / (float) h)));
            Bitmap r = Bitmap.createScaledBitmap(patch, nw, H, true);
            int[] px = new int[nw * H];
            r.getPixels(px, 0, nw, 0, 0, nw, H);
            r.recycle();
            float[] data = new float[3 * H * nw];
            int plane = H * nw;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                data[i] = (Color.red(c) / 255f - 0.5f) / 0.5f;
                data[plane + i] = (Color.green(c) / 255f - 0.5f) / 0.5f;
                data[2 * plane + i] = (Color.blue(c) / 255f - 0.5f) / 0.5f;
            }
            long[] shape = { 1, 3, H, nw };
            OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(session.getInputNames().iterator().next(), input);
            OrtSession.Result out = session.run(inputs);
            float[][][] y = (float[][][]) out.get(0).getValue();   // [1,T,C]
            input.close();
            out.close();
            StringBuilder sb = new StringBuilder();
            int T = y[0].length;
            int prev = -1;
            double sum = 0;
            int cnt = 0;
            for (int t = 0; t < T; t++) {
                float[] row = y[0][t];
                int bi = 0;
                float bv = row[0];
                for (int k = 1; k < row.length; k++) if (row[k] > bv) { bv = row[k]; bi = k; }
                if (bi != prev && bi != BLANK && bi - 1 < dict.length) {
                    sb.append(dict[bi - 1]);
                    sum += bv;
                    cnt++;
                }
                prev = bi;
            }
            lastScore = cnt > 0 ? (float) (sum / cnt) : 0f;
            lastText = sb.toString();
            return lastText;
        } catch (Throwable t) {
            return "";
        }
    }
}

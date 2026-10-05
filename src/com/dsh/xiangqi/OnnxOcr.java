package com.dsh.xiangqi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

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
 * 开源深度学习识别：TheOne1006/chinese-chess-recognition 的 10x9x16 棋局分类模型
 * （Swin-Transformer nano，ONNX Runtime 推理，int8 量化后 9.6MB）。
 *
 * 输入：把棋盘按"半格外扩"校正成 280x315（模型训练口径），ImageNet 均值方差归一化。
 * 输出：[90, 16] → 每格 argmax → 棋子。
 */
public class OnnxOcr {

    // 类别顺序（与训练一致）：空、其他、红帅仕相马车炮兵、黑将士象马车炮卒
    private static final int[] TYPE_OF = {
        0, 0,                       // point, other
        Board.KING, Board.ADVISOR, Board.ELEPHANT, Board.HORSE, Board.ROOK, Board.CANNON, Board.PAWN,   // 红
        Board.KING, Board.ADVISOR, Board.ELEPHANT, Board.HORSE, Board.ROOK, Board.CANNON, Board.PAWN,   // 黑
    };
    private static final boolean[] RED_OF = {
        false, false,
        true, true, true, true, true, true, true,
        false, false, false, false, false, false, false,
    };

    private static final int IW = 280, IH = 315;
    private static final float[] MEAN = { 123.675f, 116.28f, 103.53f };
    private static final float[] STD = { 58.395f, 57.12f, 57.375f };

    private static OrtEnvironment env;
    private static OrtSession session;
    private static boolean failed = false;
    /** 最近一次识别的平均置信度（0~1），供对齐搜索使用。 */
    public static float lastConfidence = 0f;
    /** 每格的最高类概率，用于"多认了"时按置信度裁剪。 */
    public static final float[] lastCellConf = new float[90];

    public static boolean isReady() { return session != null; }

    /** 初始化（较慢，请放后台线程）。 */
    public static boolean init(Context ctx) {
        if (session != null) return true;
        if (failed) return false;
        try {
            File f = new File(ctx.getFilesDir(), "cchess_reg.onnx");
            if (!f.exists() || f.length() < 1000000) {
                InputStream is = ctx.getAssets().open("cchess_reg.onnx");
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
            android.util.Log.e("xiangqi-ocr", "OnnxOcr.init failed: " + t);
            failed = true;
            return false;
        }
    }

    /** 用深度学习模型识别整盘棋；失败返回 null（调用方回退模板匹配）。 */
    public static int[] recognize(Bitmap src, float[] gridCorners) {
        if (session == null || gridCorners == null || gridCorners.length != 8) return null;
        try {
            double[] h = Ocr.homography(gridCorners);
            if (h == null) return null;
            int w = src.getWidth(), hh = src.getHeight();
            int[] px = new int[w * hh];
            src.getPixels(px, 0, w, 0, 0, w, hh);

            float[] data = new float[3 * IH * IW];
            int plane = IH * IW;
            for (int y = 0; y < IH; y++) {
                float gv = -0.5f + (y + 0.5f) * 10f / IH;      // 纵向 10 格，外扩半格
                for (int x = 0; x < IW; x++) {
                    float gu = -0.5f + (x + 0.5f) * 9f / IW;   // 横向 9 格
                    double[] xy = Ocr.apply(h, gu, gv);
                    int sx = (int) Math.round(xy[0]), sy = (int) Math.round(xy[1]);
                    int c = 0x00F5EFE6;
                    if (sx >= 0 && sy >= 0 && sx < w && sy < hh) c = px[sy * w + sx];
                    data[y * IW + x] = (Color.red(c) - MEAN[0]) / STD[0];
                    data[plane + y * IW + x] = (Color.green(c) - MEAN[1]) / STD[1];
                    data[2 * plane + y * IW + x] = (Color.blue(c) - MEAN[2]) / STD[2];
                }
            }
            long[] shape = { 1, 3, IH, IW };
            OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap("input", input);
            OrtSession.Result out = session.run(inputs);
            float[][][] y = (float[][][]) out.get(0).getValue();   // [1, 90, 16]
            input.close();
            out.close();
            int[] cells = new int[90];
            int found = 0;
            double confSum = 0, probSum = 0;
            for (int sq = 0; sq < 90; sq++) {
                float[] logits = y[0][sq];
                float maxL = logits[0];
                for (int k = 1; k < 16; k++) if (logits[k] > maxL) maxL = logits[k];
                double denom = 0;
                for (int k = 0; k < 16; k++) denom += Math.exp(logits[k] - maxL);
                int best = 0;
                float bestV = logits[0];
                for (int k = 1; k < 16; k++) if (logits[k] > bestV) { bestV = logits[k]; best = k; }
                double prob = 1.0 / denom;
                probSum += prob;
                lastCellConf[sq] = (float) prob;
                if (best >= 2) confSum += prob;
                int type = TYPE_OF[best];
                if (type == 0) continue;
                cells[sq] = type | (RED_OF[best] ? Board.RED : Board.BLACK);
                found++;
            }
            lastConfidence = (float) (probSum / 90.0);
            android.util.Log.i("xiangqi-ocr", "onnx found=" + found + " conf=" + String.format("%.3f", lastConfidence));
            return cells;
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "OnnxOcr.recognize failed: " + t);
            return null;
        }
    }
}

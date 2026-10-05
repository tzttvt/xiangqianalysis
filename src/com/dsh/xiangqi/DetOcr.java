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
import ai.onnxruntime.TensorInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import ai.onnxruntime.providers.NNAPIFlags;

/**
 * 两阶段棋子检测（**2026-09-20 起**，照 `D:\YOLO\chess\predict_two_stage.py` 的方案重写）。
 *
 * 为什么拆两阶段：过去是**一个模型同时干两件事**（整屏上找棋子 + 找棋盘「框」，类 14 塞在最后）。
 * 现在拆成两个各司其职的专用模型，各自在**自己擅长的尺度**上跑：
 * <pre>
 *   阶段1  assets/chess_frame.onnx   整屏上找「框」     nc=1   输入 images [1,3,640,640]  输出 [1,5,8400]
 *   阶段2  assets/chess_pieces.onnx  棋盘裁图上找棋子   nc=14  输入 images [1,3,640,640]  输出 [1,18,8400]
 * </pre>
 * 两个都是 YOLO26s、都在 **640** 上训的（输出都是**通道在前** `[1, 4+nc, N]`，没有内嵌 NMS，
 * 需要自己解码 + 跨类 NMS）。**IMGSZ 必须跟着各自的训练尺度**：
 *   · 阶段1 用 640：实测 960 会在「聊天/局势分析」面板上多吐一个误检框（conf 0.5~0.65）。
 *   · 阶段2 用 640：chess_pieces 就是 640 训的，改成别的尺度就错配。
 *
 * 类别顺序严格照训练时的 pieces_only.yaml（写错就会红黑/车马互换）：
 *   0红帅 1红仕 2红相 3红车 4红马 5红炮 6红兵
 *   7黑将 8黑士 9黑象 10黑车 11黑马 12黑炮 13黑卒
 *
 * 一趟的流程（**每帧固定 1~2 次推理，但每次只跑 640**，总像素反而比过去 960 那一趟还少）：
 *   1) **阶段1 找框**：整张图（先按 2 的幂预缩，见 preShrinkFactor）喂 chess_frame。
 *      ★ **按置信度最高挑，绝不能按面积挑** —— 实测偶尔会在下方「聊天/局势分析」面板上误检一个框，
 *        它 conf 只有 0.5~0.65、**面积却比棋盘还大**，按面积挑就精准挑中误检。
 *   2) **裁图**：框四边各外扩「一个棋子宽」= 框宽 ÷ 9（FRAME_PAD_RATIO），夹进图像边界；
 *      ★ **从原图裁，不是从模型输入的那张缩略图裁**。
 *      没框时（罕见）整屏直送 —— 这时棋子很小、结果只作参考（与参考脚本一致）。
 *   3) **阶段2 找棋子**：裁图喂 chess_pieces，坐标**加上裁图左上角偏移**映射回整屏。
 *   4) 框的合理性闸门：框的格距必须跟棋子中位宽对得上（framePlausible）。对不上就丢掉框，
 *      并把整屏重跑一趟（罕见路径）。**假框比没框更糟** —— 错的框会把真棋子裁掉。
 *   5) 之后**一行都没变**：裁一刀 -> 按尺寸分簇 -> 一维点阵 RANSAC + 迭代甩离群 -> 象棋语法锚定 9x10。
 *      「框」仍然做四件事：裁一刀（clipToFrame）、候选择优（findBoards 里的 rectIoU）、
 *      兜底建盘（buildFromFrame，格距 = (框宽/8 + 框高/9)/2，见 FRAME_COLS）、黏性沿用（FRAME_TTL）。
 *      ★ **框不在时四条边定位那条路（findBoards / buildBoard / refine / bestAnchor）仍然是主链路**。
 *   6) 丢掉落在自己悬浮窗里的框；执黑时整图转 180° 再检一趟（pickBoard 判优）。
 *   7) 输出 90 格数组 + 棋盘四角（顺序 左上,右上,右下,左下，供算点击坐标）。
 *
 * 换模型铁律：① 类别顺序照 yaml，写错就红黑/车马互换；② **输入边长变了要同步改 IMGSZ_FRAME/IMGSZ_PIECES**；
 *   ③ 缓存文件名带 `字节数_CRC32`（见 assetTag），换文件必然重拷，但**手机上跑的是哪个模型
 *   必须以 `adb logcat -s xiangqi-ocr` 里那行
 *   `DetOcr.init ok … frame=chess_frame_xxx.onnx pieces=chess_pieces_xxx.onnx` 为准**。
 *
 * 离线复现：`D:\YOLO\chess\predict_two_stage.py`（同一套方案的原型脚本，跑真机截图出对比图）。
 */
public class DetOcr {

    // ---------- 类别表（务必与 chess.yaml 一致）----------
    /** 模型类别 -> Board.java 的兵种码。注意 3=车 在 4=马 前面，抄错就是车马互换。 */
    private static final int[] CLS_TYPE = {
        Board.KING, Board.ADVISOR, Board.ELEPHANT, Board.ROOK, Board.HORSE, Board.CANNON, Board.PAWN,
        Board.KING, Board.ADVISOR, Board.ELEPHANT, Board.ROOK, Board.HORSE, Board.CANNON, Board.PAWN,
    };
    private static final int[] CLS_SIDE = {
        Board.RED, Board.RED, Board.RED, Board.RED, Board.RED, Board.RED, Board.RED,
        Board.BLACK, Board.BLACK, Board.BLACK, Board.BLACK, Board.BLACK, Board.BLACK, Board.BLACK,
    };
    /**
     * **棋子**类别数 = 14（chess_pieces 的输出是 `[1, 18, 8400]` = 4 坐标 + 14 类）。
     * 跟「框」现在完全分家了：框由 chess_frame 那个单类模型（nc=1）负责，
     * 所以这里**不会**再有"框被当成第 15 种棋子塞进棋盘"的问题。
     * 类别顺序必须跟 pieces_only.yaml 一致（0红帅 … 13黑卒）。
     */
    private static final int NCLS = 14;
    /** 每个兵种的数量上限（按 Board 码索引，索引 0 占位）。 */
    private static final int[] MAXN = { 0, 1, 2, 2, 2, 2, 2, 5 };

    // 合法位置（红方底线是 row 9，黑方底线是 row 0，与 Ocr.recognize 口径一致）
    private static final boolean[][] ADV_R = box(new int[][] { { 9, 3 }, { 9, 5 }, { 8, 4 }, { 7, 3 }, { 7, 5 } });
    private static final boolean[][] ADV_B = box(new int[][] { { 0, 3 }, { 0, 5 }, { 1, 4 }, { 2, 3 }, { 2, 5 } });
    private static final boolean[][] ELE_R = box(new int[][] { { 9, 2 }, { 9, 6 }, { 7, 0 }, { 7, 4 }, { 7, 8 }, { 5, 2 }, { 5, 6 } });
    private static final boolean[][] ELE_B = box(new int[][] { { 0, 2 }, { 0, 6 }, { 2, 0 }, { 2, 4 }, { 2, 8 }, { 4, 2 }, { 4, 6 } });

    private static boolean[][] box(int[][] pts) {
        boolean[][] m = new boolean[10][9];
        for (int[] p : pts) m[p[0]][p[1]] = true;
        return m;
    }

    // ---------- 常量 ----------
    /**
     * 阶段1（找「框」）的输入边长。★ 必须跟 chess_frame 训练时一致，**也别为了"更准"往上加**：
     * 实测 960 会在「聊天/局势分析」面板上多吐一个误检框（conf 0.5~0.65），640 干净得多。
     */
    private static final int IMGSZ_FRAME = 640;
    /** 阶段2（找棋子）的输入边长。★ 必须跟 chess_pieces 训练时一致，改别的就尺度错配。 */
    private static final int IMGSZ_PIECES = 640;
    private static final int PAD = 114;                 // YOLO letterbox 填充灰
    /**
     * 棋子置信度门槛。**别调高**：实测 0.25 会把真子砍掉（模型看得见、只是分低）——
     * 降到 0.15 后各样本盘面普遍多认 1~5 个子、违规扣分反而下降。
     */
    private static final float CONF = 0.15f;
    /**
     * 「框」的置信度门槛。参考脚本用 0.25；其实真机上棋盘框稳在 **0.94~0.95**（92 张真机截图 0 漏检），
     * 0.25 只是兜个底。**别往下调太多**：低分那几档都是噪声，一旦信了就会拿个错框去裁图、把真棋子削掉。
     */
    private static final float CONF_FRAME = 0.25f;
    private static final float IOU = 0.45f;
    private static final int MAX_BOARDS = 4;            // 一屏最多试几个尺寸簇
    private static final int MIN_BOX = 6;               // 至少几个框才试建盘
    /** 违规扣分在这以内就算"干净"，不必再试倒转那一趟。实测干净局面是 0~22。 */
    private static final int PEN_CLEAN = 24;
    /** 棋子这么少（残局）也值得多试一趟倒转：子少时违规扣分本来就小，光看扣分会漏判。 */
    private static final int SPARSE_N = 12;
    /** 倒转那趟的扣分必须在这以内才有资格顶掉第一趟。 */
    private static final int PEN_OK = 30;
    /**
     * ★★★ 2026-09-29 用户报「同一张图时对时错」的修法之一：**判优要留出足够大的"缓冲带"**。
     *
     * 原来判据是 `dn.pen < up.pen - 4`（差 4 分才换）。问题：两趟各有各的浮点/检测抖动
     * （ORT 多线程归约顺序、NNAPI→CPU 降级、检测框 1~2 像素的漂移），实测同一张图
     * 两趟分差就在 ±4 附近晃 ⇒ **判据落在临界点上，差 1 分结论就翻** ⇒ 同一张图时对时错。
     *
     * 对策：缓冲带从 4 加宽到 **8**（≈"两趟各自的抖动幅度之和"），
     * 只有倒转**明显更优**才换。差在 8 分以内一律维持第一趟 —— 趋于稳定。
     */
    private static final int PEN_SWITCH_MARGIN = 8;
    /** 同理，"多子"那条判据也从 +2 加宽到 +4，避免子数抖动（识别多/少 1 子很常见）就翻盘。 */
    private static final int N_SWITCH_MARGIN = 4;
    /**
     * ★★★ 第二处修法：**两趟分差在"难分伯仲"区间时，不要临时决策，沿用上一次的结论**。
     *
     * 即便缓冲带加宽了，仍会有一个"两趟确实差不多、怎么选都说得过去"的区间（比如差 8~16）。
     * 这个区间里如果每次跑都重新挑，结果还是会飘。所以：**落在这个区间时，用 `lastUsedRot`
     * 维持上一次的选择**（首次为false=正着）。这样同一张图连续跑，结论就锁定了。
     *
     * 注意"明显更优"（差 > PEN_STICKY_SPAN）时仍然照实切 —— 真有一趟明显干净还不换，那是错的。
     */
    private static final int PEN_STICKY_SPAN = 16;

    // ---------- 「框」 ----------
    /** 框可信时，棋子中心落在"框外这么多倍框边长"以外就丢掉（框外不可能是棋子）。留点余量防框偏紧。 */
    private static final float FRAME_MARGIN = 0.10f;
    /** 候选棋盘的九宫外框跟「框」的重叠率到多少才算"就是它"。 */
    private static final double FRAME_IOU = 0.50;
    /** 上一帧见过框、这一帧没见着，最多再沿用几帧（盘面不动，免得裁剪一会儿开一会儿关）。 */
    private static final int FRAME_TTL = 6;
    /**
     * 棋盘横 8 格、竖 9 格，且「框」是**贴着棋盘外沿**的（chess_frame 的标签实测：框占图面
     * 86%x88%，中心对着棋盘中心），所以格距 = (框宽/8 + 框高/9) / 2。
     */
    private static final double FRAME_COLS = 8.0;
    private static final double FRAME_ROWS = 9.0;
    /**
     * 阶段2 的裁图往外扩多少：**框宽 ÷ 9 = 一个棋子宽**（与参考脚本的 PAD_RATIO 一致）。
     * 为什么外扩：棋盘贴边的子框会压着框线，不留边距模型会把它切掉一半。
     * 为什么是 1 格而不是 0.5 格：0.5 格实测会夹掉棋盘边上的子。
     */
    private static final double FRAME_PAD_RATIO = 1.0 / 9.0;

    // ---------- 状态 ----------
    private static OrtEnvironment env;
    /** 阶段1：找「框」的会话（chess_frame，nc=1）。 */
    private static OrtSession frameSession;
    /** 阶段2：找棋子的会话（chess_pieces，nc=14）。 */
    private static OrtSession pieceSession;
    private static String frameInputName = "images";
    private static String pieceInputName = "images";
    private static boolean failed = false;
    private static volatile boolean nnapiFailed = false;

    /** TL,TR,BR,BL，与 Ocr.recognize 的 corners 顺序一致；供 clickAt 算坐标。 */
    public static float[] lastGrid = null;
    public static int lastCount = 0;
    /** 平均检测置信度（0~1）。 */
    public static float lastScore = 0f;
    /** 点阵平均残差（占格距的比例），越小越稳。 */
    public static float lastResid = 0f;
    public static String lastDebug = "";
    /**
     * ★★ 这一帧交出来的 `cells` 里，**红方是不是在下半盘**（"红在下"）。
     * 用户执黑时，天天象棋会把整个棋盘转 180° 显示 —— 此时识别结果里红方在上面，
     * 直接喂引擎就是**一个反过来的非法局面**，皮卡鱼一句 `info` 都不吐 →
     * 悬浮窗只能显示"分析没出结果"（2026-09-21 用户实测）。所以调用方要按这个标志把
     * cells 转 180° 再喂引擎（`ShotService` 里就是这么做的）。
     */
    public static volatile boolean lastRedBottom = true;
    /**
     * 上一帧是不是靠"把整图转 180°"才认出来的。
     * 执黑时天天象棋会把棋盘连同棋子上的字一起转 180°，每帧都得倒转才认得出来；
     * 记住它，下一帧就先试倒转，一趟推理搞定（否则每帧都要先白跑一趟正的，耗时翻倍）。
     */
    public static boolean lastUsedRot = false;
    /** 悬浮窗只在用户点击“重新识别”时执行整屏棋盘框检测。 */
    private static volatile boolean forceRelocate = true;

    public static void requestRelocate() {
        forceRelocate = true;
        android.util.Log.i("xiangqi-ocr", "DetOcr: 用户请求重新找棋盘框");
    }

    /**
     * 最近一次识别用到的「框」（**原图 src 坐标**）：{x0, y0, x1, y1, 置信度}，没有就是 null。
     * 给日志/调试浮层用 —— 真机上"框到底响没响"一眼就能看出来。
     */
    public static float[] lastFrameBox = null;
    /** 「框」的黏性：盘面在连续帧里不动，偶发漏检没必要把裁剪一会儿开一会儿关。 */
    private static Frame stickyFrame = null;
    /** 距上一次本帧检到框过了几帧。超过 FRAME_TTL 就把黏性框丢掉。 */
    private static int stickyAge = 0;
    /**
     * ★ 2026-09-25：上一次成功建盘时用了多少颗棋子。
     *   用来识别"框还在、但画面其实已经换了另一盘棋"这种黏性框失效 ——
     *   光靠几何（`frameSane`/`framePlausible`）是看不出来的（框本身长得还是很像个棋盘）。
     */
    private static int lastBoardPieces = 0;
    /**
     * ★★ 2026-09-25：存下黏性框时那张图的尺寸。
     *   实测踩到：屏幕尺寸一变（1200×1388 → 972×1076），旧框的坐标就整体失准，
     *   可它**几何上看还是像个正常棋盘**（`frameSane`/`framePlausible` 都拦不住），
     *   于是裁出一块错的位置、只认到 21 颗子，而且 `n` 还稳定地错下去。
     *   尺寸是最硬的判据：只要和存框时不一致，黏性框一律不可用。
     */
    private static int stickyW = 0, stickyH = 0;

    /** 棋盘「框」：原图 src 像素坐标下的矩形 + 置信度。 */
    private static class Frame {
        float x0, y0, x1, y1, score;
        float cx() { return (x0 + x1) / 2f; }
        float cy() { return (y0 + y1) / 2f; }
        /** 取短边当"边长"：框本该是方的，万一模型给成长方形，用短边更保守。 */
        float side() { return Math.min(x1 - x0, y1 - y0); }
    }

    /** 「框」折算成一个格距：(框宽/8 + 框高/9)/2（框贴着棋盘外沿，横 8 格竖 9 格）。 */
    private static double frameGrid(Frame fr) {
        return ((fr.x1 - fr.x0) / FRAME_COLS + (fr.y1 - fr.y0) / FRAME_ROWS) / 2.0;
    }

    public static boolean isReady() { return frameSession != null && pieceSession != null; }

    /**
     * 取 assets 里某个模型的真实字节数。<b>不能再写 `AssetManager.openFd().getLength()`</b>：
     * APK 里的 onnx 是**压缩存储**（打包脚本沿用 aapt2 的 method=8），压缩资源的 openFd 必然抛
     * FileNotFoundException —— 于是长度永远拿不到、那句"长度不同就重拷"等于从没执行过。
     * 2026-09-16 换 960 模型时正是死在这里：APK 里明明是 960（md5 逐字节一致），
     * 手机上却每帧报 `images index:2 Got:960 Expected:640`，跑的还是 filesDir 里的旧 640 缓存。
     *
     * 现在改成直接读 APK 自己的 zip 目录：**不解压**就能拿到原始大小，压缩与否都准。
     *
     * @return "字节数_CRC32"；真拿不到时返回 null（此时退回"存在即用"）。
     */
    private static String assetTag(Context ctx, String assetName) {
        java.util.zip.ZipFile zf = null;
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            if (apk == null) return null;
            zf = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry ze = zf.getEntry("assets/" + assetName);
            if (ze == null) return null;
            // size + crc32：光比 size 挡不住"换了模型但字节数正好一样"这种情况（CRC 变了就一定变）
            return ze.getSize() + "_" + Long.toHexString(ze.getCrc());
        } catch (Throwable ignore) {
            return null;
        } finally {
            try { if (zf != null) zf.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 把一个 assets 里的模型拷到 filesDir（缓存名带"字节数_CRC32"指纹），返回可用的 File。
     * 换了模型 -> 指纹变了 -> 缓存名就变了 -> **必定是新拷的**；再顺手删掉同前缀的旧缓存。
     * 拷不出来 / 拷不完整就返回 null（调用方按"模型没准备好"处理，不许拿半截文件去开会话）。
     */
    private static File ensureModel(Context ctx, String assetName, String cachePrefix) {
        try {
            String tag = assetTag(ctx, assetName);
            long alen = -1;
            if (tag != null) {
                try { alen = Long.parseLong(tag.substring(0, tag.indexOf('_'))); } catch (Throwable ignore) { }
            }
            String name = (tag != null) ? (cachePrefix + "_" + tag + ".onnx") : (cachePrefix + ".onnx");
            File dir = ctx.getFilesDir();
            File f = new File(dir, name);
            if (!f.exists() || (alen > 0 && f.length() != alen)) {
                InputStream is = ctx.getAssets().open(assetName);
                FileOutputStream os = new FileOutputStream(f);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                is.close();
                android.util.Log.i("xiangqi-ocr", "DetOcr: 重拷模型 assets=" + assetName
                        + " bytes=" + alen + " -> " + name + " len=" + f.length());
                // 拷完核对一遍，半截文件不许拿去开会话（否则报一个莫名其妙的解析错）
                if (alen > 0 && f.length() != alen) {
                    throw new java.io.IOException("模型拷贝不完整 " + f.length() + " != " + alen);
                }
                // 顺手清掉同前缀的历史缓存：每个 38MB，别一代代叠着占地
                File[] olds = dir.listFiles();
                if (olds != null) for (File o : olds) {
                    String on = o.getName();
                    if (on.startsWith(cachePrefix) && on.endsWith(".onnx") && !on.equals(name)) {
                        if (o.delete()) android.util.Log.i("xiangqi-ocr", "DetOcr: 删旧缓存 " + on);
                    }
                }
            }
            return f;
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "DetOcr: 准备模型失败 " + assetName + " : " + t);
            return null;
        }
    }

    /**
     * 开一个会话，并**自检它声明的输入边长是不是等于我们打算喂的 imgsz**。
     * 不一致时 ONNX Runtime 会**每帧**抛 ORT_INVALID_ARGUMENT 刷屏，噪声里根本看不出原因；
     * 这里提前问一次模型"你要多大"，启动阶段一句话说清楚。
     */
    private static OrtSession openSession(File f, int imgsz, String who) {
        try {
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(4);
            OrtSession s;
            String provider = "NNAPI_FP16";
            try {
                // 参考 base.apk 的 ncnn/Vulkan 路线试 NNAPI；模型有动态 Slice，NNAPI 编译
                // 可能在真机失败。失败后本进程记住 CPU fallback，后续模型和下一次启动不再反复试。
                if (nnapiFailed) throw new IllegalStateException("NNAPI disabled after earlier failure");
                o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16));
                s = env.createSession(f.getAbsolutePath(), o);
            } catch (Throwable nnapiError) {
                provider = "CPU(fallback)";
                nnapiFailed = true;
                android.util.Log.w("xiangqi-ocr", "DetOcr.init " + who
                        + " NNAPI unavailable, use CPU: " + nnapiError);
                try { o.close(); } catch (Throwable ignored) { }
                o = new OrtSession.SessionOptions();
                o.setIntraOpNumThreads(4);
                s = env.createSession(f.getAbsolutePath(), o);
            }
            try { o.close(); } catch (Throwable ignored) { }
            String in = s.getInputNames().iterator().next();
            String want = "?";
            Object vi = s.getInputInfo().get(in).getInfo();
            if (vi instanceof TensorInfo) {
                long[] sh = ((TensorInfo) vi).getShape();
                if (sh != null && sh.length == 4) {
                    want = sh[2] + "x" + sh[3];
                    if (sh[2] > 0 && sh[3] > 0 && (sh[2] != imgsz || sh[3] != imgsz)) {
                        android.util.Log.e("xiangqi-ocr", "DetOcr.init " + who + " 输入尺寸不符：模型要 "
                                + sh[2] + "x" + sh[3] + "，代码喂 " + imgsz + "x" + imgsz
                                + "；本模型文件=" + f.getName() + "（多半是缓存没换掉）");
                        s.close();
                        return null;
                    }
                }
            }
            android.util.Log.i("xiangqi-ocr", "DetOcr.init " + who + " ok in=" + in
                    + " shape=" + want + " feed=" + imgsz + " provider=" + provider
                    + " model=" + f.getName());
            if (WHO_FRAME.equals(who)) frameInputName = in; else pieceInputName = in;
            return s;
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "DetOcr.init " + who + " failed: " + t);
            return null;
        }
    }

    private static final String WHO_FRAME = "frame";
    private static final String WHO_PIECES = "pieces";

    /** 初始化：读**两个**模型（各 38MB，较慢，请放后台线程）。两个都成功才算 ready。 */
    public static boolean init(Context ctx) {
        if (isReady()) return true;
        if (failed) return false;
        try {
            long t0 = System.currentTimeMillis();
            env = OrtEnvironment.getEnvironment();
            File ff = ensureModel(ctx, "chess_frame.onnx", "chess_frame");
            File fp = ensureModel(ctx, "chess_pieces.onnx", "chess_pieces");
            if (ff == null || fp == null) { failed = true; return false; }
            frameSession = openSession(ff, IMGSZ_FRAME, WHO_FRAME);
            pieceSession = openSession(fp, IMGSZ_PIECES, WHO_PIECES);
            if (frameSession == null || pieceSession == null) {
                if (frameSession != null) { frameSession.close(); frameSession = null; }
                if (pieceSession != null) { pieceSession.close(); pieceSession = null; }
                failed = true;
                return false;
            }
            android.util.Log.i("xiangqi-ocr", "DetOcr.init ok in " + (System.currentTimeMillis() - t0) + "ms"
                    + " frame=" + ff.getName() + " feed=" + IMGSZ_FRAME
                    + " pieces=" + fp.getName() + " feed=" + IMGSZ_PIECES);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "DetOcr.init failed: " + t);
            failed = true;
            return false;
        }
    }

    // ==================================================================
    // 1. 检测（两阶段）
    // ==================================================================

    /**
     * **阶段1**：整张 src 上找棋盘「框」，返回置信度最高的一个（没有就是 null）。
     *
     * ★ 必须按**置信度最高**挑，不能按面积挑：实测偶尔会在下方「聊天/局势分析」面板上误检一个框，
     *   它 conf 只有 0.5~0.65、**面积却比棋盘还大** —— 按面积挑就精准挑中误检（参考脚本踩过）。
     * ★ 输入 640（不是 960）：理由见 IMGSZ_FRAME 的注释。
     */
    private static Frame detectFrame(Bitmap src, int[] excludeRect) {
        float[][] raw = detectGeneric(src, IMGSZ_FRAME, 1, frameSession, frameInputName, CONF_FRAME);
        Frame best = null;
        for (float[] b : raw) {
            if (b[4] < 40 || b[5] < 40) continue;                 // 太小必然是噪声
            Frame f = new Frame();
            f.x0 = b[0]; f.y0 = b[1]; f.x1 = b[2]; f.y1 = b[3]; f.score = b[6];
            if (!frameSane(f, src.getWidth(), src.getHeight())) continue;
            if (excludeRect != null && f.cx() >= excludeRect[0] && f.cx() <= excludeRect[2]
                    && f.cy() >= excludeRect[1] && f.cy() <= excludeRect[3]) continue;
            if (best == null || f.score > best.score) best = f;
        }
        return best;
    }

    /**
     * **通用的"单模型一趟检测"**：预缩 -> letterbox(imgsz) 填灰 114 -> ONNX 前向 -> 解码 -> 跨类 NMS
     * -> 去 letterbox，返回**整张 src 像素坐标**下的 `{x0, y0, x1, y1, w, h, conf, cls}`。
     *
     * 阶段1 用 ncls=1（只有「框」）、阶段2 用 ncls=14（棋子）—— 同一个函数，只是喂的模型/尺度不同。
     *
     * 预处理严格对齐训练：等比缩放 + 居中 + 四周填灰 114（ultralytics 的 letterbox）。
     * 缩放比 = min(imgsz/w, imgsz/h)，被**较长边**决定。
     */
    private static float[][] detectGeneric(Bitmap src, int imgsz, int ncls,
                                           OrtSession sess, String inName, float conf) {
        if (sess == null || src == null) return new float[0][];
        try {
            final int w = src.getWidth(), h = src.getHeight();
            // 先按 2 的幂"预缩"：让悬浮窗（1200x2670 原图）也走扫图那条 600x1335 的管道。
            // 详细理由见 preShrinkFactor 的注释。扫图进来就是 600x1335，f=1，行为完全不变。
            long tAll = System.currentTimeMillis();
            long tPrep0 = tAll;
            int f = preShrinkFactor(w, h, imgsz);
            Bitmap in = (f > 1) ? shrinkTo(src, w, h, f) : src;
            if (in == null) { in = src; f = 1; }
            final int sw = in.getWidth(), sh = in.getHeight();
            // rs = 预缩图 -> 模型输入 的缩放；r = 每输出像素对应**原图**多少个像素（已含预缩的 f）
            float rs = Math.min(imgsz / (float) sw, imgsz / (float) sh);
            float r = rs / f;
            int nw = Math.max(1, Math.round(sw * rs)), nh = Math.max(1, Math.round(sh * rs));
            Bitmap canvas = Bitmap.createBitmap(imgsz, imgsz, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(canvas);
            cv.drawColor(Color.rgb(PAD, PAD, PAD));
            int dw = (imgsz - nw) / 2, dh = (imgsz - nh) / 2;
            cv.drawBitmap(in, new Rect(0, 0, sw, sh), new Rect(dw, dh, dw + nw, dh + nh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] px = new int[imgsz * imgsz];
            canvas.getPixels(px, 0, imgsz, 0, 0, imgsz, imgsz);
            canvas.recycle();

            int plane = imgsz * imgsz;
            float[] data = new float[3 * plane];
            for (int i = 0; i < plane; i++) {
                int c = px[i];
                data[i] = Color.red(c) / 255f;
                data[plane + i] = Color.green(c) / 255f;
                data[2 * plane + i] = Color.blue(c) / 255f;
            }
            long[] shape = { 1, 3, imgsz, imgsz };
            OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(inName, input);
            long tPrep = System.currentTimeMillis() - tPrep0;
            long t1 = System.currentTimeMillis();
            OrtSession.Result out = sess.run(inputs);
            long t2 = System.currentTimeMillis();
            float[][][] y = (float[][][]) out.get(0).getValue();   // [1, 4+ncls, N]（通道在前）
            input.close();
            out.close();
            float[][] y0 = y[0];
            int nch = y0.length;
            int ncell = y0[0].length;
            if (nch < 4 + ncls) {                                  // 模型不是我们以为的那个
                android.util.Log.e("xiangqi-ocr", "DetOcr 模型通道数不对 nch=" + nch
                        + " 需要 >= " + (4 + ncls));
                return new float[0][];
            }

            // 解码：按类别取最高分（ncls=1 时那个 k 循环不执行、直接读通道 4）-> 置信度筛。
            // cap 必须留够：实测整屏 CONF=0.15 时 raw 有 158~220 个框，实测够用；
            // 顶到 cap 会**按光栅顺序静默截断**（保留左上角那批），表现为"右下的子全没了"。
            int cap = Math.max(1024, ncell / 8);
            float[] bx = new float[cap], by = new float[cap], bw = new float[cap], bh = new float[cap];
            float[] bs = new float[cap];
            int[] bc = new int[cap];
            int m = 0;
            for (int i = 0; i < ncell; i++) {
                int best = 0;
                float bv = y0[4][i];
                for (int k = 5; k < 4 + ncls; k++) if (y0[k][i] > bv) { bv = y0[k][i]; best = k - 4; }
                if (bv < conf) continue;
                if (m >= cap) break;
                bx[m] = y0[0][i]; by[m] = y0[1][i]; bw[m] = y0[2][i]; bh[m] = y0[3][i];
                bs[m] = bv; bc[m] = best; m++;
            }
            // 贪心 NMS：**不分类别**，同一个子上只留置信度最高的那个框。
            // 为什么必须跨类别压：模型在一个子身上经常同时给出两个不同类别的框
            // （实测「黑将 0.54 + 黑士 0.17」「红帅 0.86 + 红仕 0.18」「红车 0.38 + 红兵 0.27」
            // 都在同一个坐标上）。只按同类别压的话这两个都活下来，进棋盘时抢同一个格子，
            // 要么被当成"重复占格"丢掉、要么把类别填错 —— 这就是"必然会漏几个/错几个"的主因。
            int[] ord = argsortDesc(bs, m);
            boolean[] dead = new boolean[m];
            ArrayList<float[]> keep = new ArrayList<float[]>();
            for (int oi = 0; oi < m; oi++) {
                int i = ord[oi];
                if (dead[i]) continue;
                for (int oj = oi + 1; oj < m; oj++) {
                    int j = ord[oj];
                    if (dead[j]) continue;
                    if (iou(bx[i], by[i], bw[i], bh[i], bx[j], by[j], bw[j], bh[j]) > IOU) dead[j] = true;
                }
                // 去 letterbox：先减掉居中偏置，再除以缩放比 —— 得到整张 src 的像素坐标
                float x0 = (bx[i] - bw[i] / 2 - dw) / r, yy0 = (by[i] - bh[i] / 2 - dh) / r;
                float x1 = (bx[i] + bw[i] / 2 - dw) / r, yy1 = (by[i] + bh[i] / 2 - dh) / r;
                x0 = clamp(x0, 0, w - 1); x1 = clamp(x1, 0, w - 1);
                yy0 = clamp(yy0, 0, h - 1); yy1 = clamp(yy1, 0, h - 1);
                if (x1 - x0 < 2 || yy1 - yy0 < 2) continue;
                keep.add(new float[] { x0, yy0, x1, yy1, x1 - x0, yy1 - yy0, bs[i], bc[i] });
            }
            long tPost = System.currentTimeMillis() - t2;
            android.util.Log.i("xiangqi-ocr", "DetOcr.detect " + w + "x" + h
                    + " pre=" + f + "x(->" + sw + "x" + sh + ") img=" + imgsz
                    + " raw=" + m + " nms=" + keep.size()
                    + " prep=" + tPrep + "ms infer=" + (t2 - t1) + "ms post=" + tPost
                    + "ms total=" + (System.currentTimeMillis() - tAll) + "ms");
            return keep.toArray(new float[keep.size()][]);
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "DetOcr.detectGeneric failed: " + t);
            return new float[0][];
        }
    }

    /** 预缩用的复用缓冲：悬浮窗 0.5 秒一帧，别每帧新分配一张 3MB 位图喂 GC。 */
    private static Bitmap shrinkBuf;

    /**
     * 只在**开发自检**里用：强制关掉预缩，好拿同一版 APK、同一张图做 A/B。
     * 生产路径没人设它，恒为 false。见 MainActivity 的 `--ez noshrink true`。
     *
     * ★★ 2026-09-29：**这个开关是全进程共享的**（static），设一次就污染所有后续识别 ——
     *   自检跑完必须自己复位，否则用户接着用扫图/悬浮窗，全程都在关预缩的状态下工作。
     *   复位点见 `MainActivity` 的 shotImage 自检分支。
     */
    public static volatile boolean NO_SHRINK = false;

    /**
     * 预缩倍数。为了**让悬浮窗和「扫图」走同一个像素管道** —— 扫图准，悬浮窗就该一样准。
     *
     * 证据是这两条链路本来就不一样，而且差在**进模型前的降采样**：
     * <pre>
     *   扫图（MainActivity.ocrImage，实测 100% 对）
     *       BitmapFactory inSampleSize=2   → 1200x2670 直接解成 600x1335 再喂
     *   扫图（OcrActivity 选图，同上）
     *       BitmapFactory inSampleSize=2   → 1200x2670 直接解成 600x1335 再喂
     *   悬浮窗（ShotService 实时抓屏）
     *       抓什么喂什么 → 1200x2670 原图进 letterbox → 一步缩好几倍
     * </pre>
     * `inSampleSize` 是**解码器级**的整数倍抽样，JPEG 走的是 DCT 域平均，质量最好；
     * 而悬浮窗喂的是实时 RGBA 帧（没有 JPEG 可解、用不上 inSampleSize），
     * 只能靠 letterbox 那一步的 `Canvas.drawBitmap`（Skia 的 FILTER_BITMAP），
     * 一步缩太多倍时等效核太窄、不做面积平均 → **丢小棋子**。
     *
     * 所以先在进 letterbox 前按 2 的幂预缩一次，把 letterbox 的倍率压进 (0.5, 1]。
     * **关键是这个倍数跟原图无关、只跟 imgsz 和长边有关**：
     *   1200x2670 与 600x1335 预缩完**都落在同一个小尺寸**上，
     *   于是两条链路后面每一个像素完全一致（这正是"悬浮窗该和扫图一样准"的实现方式）。
     */
    private static int preShrinkFactor(int w, int h, int imgsz) {
        if (NO_SHRINK) return 1;      // 开发自检：A/B 对照组
        int f = 1, m = Math.max(w, h);
        while (m / (f * 2) >= imgsz) f *= 2;
        return f;
    }

    /** 按 1/f 缩一次（复用缓冲 + 双线性）。失败返回 null，调用方退回原图。 */
    private static Bitmap shrinkTo(Bitmap src, int w, int h, int f) {
        try {
            int nw = Math.max(1, w / f), nh = Math.max(1, h / f);
            if (shrinkBuf == null || shrinkBuf.isRecycled()
                    || shrinkBuf.getWidth() != nw || shrinkBuf.getHeight() != nh) {
                if (shrinkBuf != null) shrinkBuf.recycle();
                shrinkBuf = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
            }
            Canvas c = new Canvas(shrinkBuf);
            c.drawBitmap(src, new Rect(0, 0, w, h), new Rect(0, 0, nw, nh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            return shrinkBuf;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 框的合理性闸门：`框边长 ÷ 9` 就是一个格距，而**一个格距 ≈ 一颗棋子的宽度**，
     * 两者的比值必须落在一个很窄的范围里；对不上就是这个框是假的。
     *
     * 为什么要这道闸门（实测 `_framefit.py`）：`sd_2xxx.jpg` 框分 0.64 看着挺高，
     * 但 `边长/9 = 19.2` 而那里的棋子只有 2.7 宽 —— 差 7 倍，一眼假。更糟的是这种假框
     * 落在真棋盘之外，`clipToFrame` 会把真棋子当"框外的噪声"削掉。
     * **分数低顶多是没用上，分数高但框错是会掉棋子的** —— 所以对不上就宁可不信。
     */
    private static boolean framePlausible(Frame fr, float[][] pieces) {
        if (pieces.length < MIN_BOX) return true;      // 没棋子可比，先信着（后面也只拿它兜底）
        double[] ws = new double[pieces.length];
        for (int i = 0; i < pieces.length; i++) ws[i] = pieces[i][2];
        double mw = median(ws);
        if (mw <= 1) return true;
        double cell = frameGrid(fr);
        return cell >= 0.60 * mw && cell <= 1.80 * mw;
    }

    private static int[] argsortDesc(float[] v, int n) {
        int[] ix = new int[n];
        for (int i = 0; i < n; i++) ix[i] = i;
        for (int i = 1; i < n; i++) {           // n 很小，插入排序
            int k = ix[i], j = i - 1;
            while (j >= 0 && v[ix[j]] < v[k]) { ix[j + 1] = ix[j]; j--; }
            ix[j + 1] = k;
        }
        return ix;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static float iou(float ax, float ay, float aw, float ah,
                             float bx, float by, float bw, float bh) {
        float ax0 = ax - aw / 2, ay0 = ay - ah / 2, ax1 = ax + aw / 2, ay1 = ay + ah / 2;
        float bx0 = bx - bw / 2, by0 = by - bh / 2, bx1 = bx + bw / 2, by1 = by + bh / 2;
        float ix = Math.min(ax1, bx1) - Math.max(ax0, bx0);
        float iy = Math.min(ay1, by1) - Math.max(ay0, by0);
        if (ix <= 0 || iy <= 0) return 0f;
        float inter = ix * iy;
        return inter / (aw * ah + bw * bh - inter + 1e-6f);
    }

    // ==================================================================
    // 2. 对外的识别入口
    // ==================================================================

    public static int[] recognize(Bitmap src) { return recognize(src, null); }

    /**
     * ★★★ 2026-09-29：清掉"跨帧粘性状态"，让这次识别**不受上一次影响**。
     *
     * 粘性（`stickyFrame` 沿用上一帧的框、`lastUsedRot` 决定先跑哪趟）是给**连续视频帧**
     * （悬浮窗、扫图连拍）设计的 —— 盘面不动，偶发漏检不该把裁剪一会儿开一会儿关。
     *
     * 但**静态单张图**（`--es shotImage` / 导入图片 / 扫谱）没有"连续帧"这回事，
     * 粘性只会带来麻烦：同一张图连着跑两次，第二次会受第一次残留影响，结果时对时错。
     * ⇒ 静态图入口在识别前调用本方法，就能保证**同一张图跑多少次结果都一样**。
     */
    public static synchronized void resetSticky() {
        stickyFrame = null;
        stickyAge = 0;
        stickyW = 0;
        stickyH = 0;
        lastUsedRot = false;
    }

    /**
     * ★★★ 2026-09-29：**识别必须串行**。
     *
     * `DetOcr` 里几乎全是 `static` 状态（`stickyFrame` / `lastUsedRot` / `forceRelocate` /
     * `lastBoardPieces` / `NO_SHRINK` / `lastGrid` / `lastDebug` …），而 `recognize` 会被
     * **至少三条链路**并发调用：
     * <pre>
     *   ① 扫图识谱（OcrActivity 选图后自动识别，`autoRun` 标记只挡本 Activity 自己的重入）
     *   ② 扫图识谱的「识别」按钮（doRecognize）
     *   ③ 悬浮窗（ShotService 每 0.5 秒一帧，永不停止）
     *   ④ 开发自检（MainActivity --es shotImage）
     * </pre>
     * `resetSticky()` 又是"改全局状态"——它没法只对某一个调用生效。于是两条链路交错时：
     * A 刚 reset 完、B 又 reset、A 跑到一半 B 又改了 `stickyFrame`/`lastUsedRot`
     * ⇒ **`pickBoard` 的两趟判优拿到的输入根本不属于同一帧**，结果时对时错、
     * 甚至出现"上一秒 n=22、下一秒 n=11"这种同一张图的剧烈抖动（真机实测）。
     *
     * `static synchronized` 只序列化识别本身（几百毫秒），不影响 UI 线程，
     * 也不会让悬浮窗卡住（本来一帧就要跑几百毫秒，两帧重叠时本来就该排队）。
     */
    public static synchronized int[] recognize(Bitmap src, int[] excludeRect) {
        long tRecognize = System.currentTimeMillis();
        lastGrid = null; lastCount = 0; lastScore = 0f; lastResid = 0f; lastDebug = "";
        if (!isReady() || src == null) return null;
        int W = src.getWidth(), H = src.getHeight();

        // 为什么要"倒转再检一趟"：执黑时天天象棋会把棋盘连同棋子上的字一起转 180°，
        // 而这个模型只认正着的字，直接检会把"车"读成"马"之类。
        // 顺序上默认先试"正着"；但上一帧就是靠倒转认出来的话，这一帧先试倒转 ——
        // 一趟干净就不再跑第二趟。悬浮窗每 0.5 秒一帧，省下的那趟就是实打实的流畅度。
        Cands best = pickBoard(src, excludeRect, W, H);
        if (best != null) lastUsedRot = best.rot;
        if (best == null || best.n < MIN_BOX) {
            lastDebug = "建盘失败";
            android.util.Log.i("xiangqi-ocr", "DetOcr: " + lastDebug);
            return null;
        }
        boolean rot = best.rot;
        int[] cells = best.cells;
        float[] corners = best.corners;
        if (rot) {
            cells = rotCells(cells);
            corners = rotCorners(corners, W, H);
        }
        lastGrid = corners;
        lastCount = best.n;
        lastResid = (float) best.resid;
        float sum = 0;
        for (int i : best.inl) sum += best.all[i][4];
        lastScore = best.inl.length > 0 ? sum / best.inl.length : 0f;
        lastDebug = String.format("n=%d 残差%.2f%% 格距%.0f/%.0f 扣分%d 红在下=%s%s 丢%d",
                best.n, best.resid * 100, best.sx, best.sy, best.pen, !(best.flip ^ rot),
                rot ? " 用倒转" : "", best.dropped) + frameNote(best);
        lastRedBottom = !(best.flip ^ rot);
        android.util.Log.i("xiangqi-ocr", "DetOcr -> " + lastDebug
                + " identifyTotal=" + (System.currentTimeMillis() - tRecognize) + "ms");
        return cells;
    }

    /**
     * 建盘：先按 lastUsedRot 决定先试正着还是倒转，再按"明显更干净才换"判优。
     * 判优要保守：倒转那趟必须**明显更干净**才换，避免正常局面白跑一趟还被换掉。
     * 先按违规扣分比，分相同才看棋子数。
     * 一趟是否"干净"由 clean() 判（扣分低于 PEN_CLEAN 且子不太少）—— 干净就不跑另一趟，
     * 正常局面**总共只花 1 次推理**。
     *
     * ★★★ 2026-09-29：判优改为**带缓冲带的"明显更优才换" + 难分伯仲时沿用上次**。
     *   起因：用户报「同一张图时对时错」。根因是原判据 `dn.pen < up.pen - 4` 的
     *   4 分缓冲带**窄于两趟各自的抖动幅度** ⇒ 判据落在临界点，差 1 分结论就翻。
     *   详见 `PEN_SWITCH_MARGIN` / `PEN_STICKY_SPAN` 常量上的注释。
     */
    private static Cands pickBoard(Bitmap src, int[] excludeRect, int W, int H) {
        // 「框」的黏性：本帧先把上一帧的框当提示递进去（盘面在连续帧里不动，偶发漏检不该把
        // 裁剪一会儿开一会儿关）。倒转那趟是在"转过 180° 的图"上跑的，所以提示也得跟着转。
        Frame hintUp = (stickyW == W && stickyH == H) ? stickyFrame : null;
        Frame hintDn = rotFrame(hintUp, W, H);
        Cands up = null, dn = null;
        if (lastUsedRot) {
            dn = runRot(src, excludeRect, W, H, hintDn);
            if (!clean(dn)) up = runOnce(src, excludeRect, hintUp);
        } else {
            up = runOnce(src, excludeRect, hintUp);
            if (!clean(up)) dn = runRot(src, excludeRect, W, H, hintDn);
        }
        Cands best = up;
        boolean rot = false;
        if (up == null || up.n < MIN_BOX) {
            // 第一趟没建出盘 —— 只要倒转那趟达标，直接用它（没什么好犹豫的）
            if (dn != null && dn.n >= MIN_BOX && dn.pen <= PEN_OK) { best = dn; rot = true; }
        } else if (dn != null && dn.n >= MIN_BOX && dn.pen <= PEN_OK) {
            // ★ 两趟都建出盘了。这里要判"换不换"。判据分两档，目的是**同一张图结果稳定**：
            //   ① 倒转明显更优（扣分低 > PEN_STICKY_SPAN，或子数多 > N_SWITCH_MARGIN 且扣分也更低）
            //      → **一定换**。真有一趟明显干净还不换，那是真错。
            //   ② 倒转小幅更优（扣分低 PEN_SWITCH_MARGIN ~ PEN_STICKY_SPAN 之间）
            //      → **难分伯仲，沿用上一次的选择**（`lastUsedRot`），别每次重新掷骰子。
            //   ③ 倒转没明显优势（低不到 PEN_SWITCH_MARGIN）→ 维持第一趟。
            int penGap = up.pen - dn.pen;          // 正数 = 倒转扣分更低（更干净）
            boolean clearlyBetter = penGap > PEN_STICKY_SPAN
                    || (penGap > 0 && dn.n > up.n + N_SWITCH_MARGIN);
            boolean slightlyBetter = penGap > PEN_SWITCH_MARGIN;
            if (clearlyBetter) {                       // ① 明显更优 → 换
                best = dn;
                rot = true;
            } else if (slightlyBetter && lastUsedRot) { // ② 难分伯仲 → 沿用上次（上次是倒转就继续倒转）
                best = dn;
                rot = true;
            }
            // ③ 其余 → 维持 best=up / rot=false
        }
        // ---- 黏性框维护。**必须放在 return 之前**：这一帧没建出盘也不该把框白丢了 ----
        Frame own = null;
        boolean ownRot = false;
        if (best != null && best.ownFrame != null) { own = best.ownFrame; ownRot = rot; }
        if (own != null) {
            stickyFrame = ownRot ? rotFrame(own, W, H) : own;   // 存**原图 src 坐标**
            stickyW = W; stickyH = H;                           // ★ 记下这张图的尺寸（换尺寸就作废）
            stickyAge = 0;
        } else if (stickyFrame != null && ++stickyAge > FRAME_TTL) {
            stickyFrame = null;
        }
        lastFrameBox = (stickyFrame == null) ? null
                : new float[] { stickyFrame.x0, stickyFrame.y0, stickyFrame.x1, stickyFrame.y1, stickyFrame.score };
        if (best == null) return null;
        if (rot && up != null) {
            android.util.Log.i("xiangqi-ocr", "DetOcr 倒转更优 pen/n=" + best.pen + "/" + best.n
                    + " vs " + up.pen + "/" + up.n);
        }
        // 记下本帧棋子数，供漏检框时识别裁图异常。
        lastBoardPieces = best.n;
        best.rot = rot;
        return best;
    }

    /** 日志里那句「框」：自己检到的和沿用上一帧的分开标，免得看着像"这一帧又检到了"。 */
    private static String frameNote(Cands c) {
        if (c == null) return "";
        String crop = c.staged ? " 裁" : " 未裁";
        if (c.frameScore <= 0f) return " 框-" + crop;
        return String.format(java.util.Locale.US, " 框%.2f%s%s", c.frameScore,
                c.ownFrame != null ? "" : "(承)", c.byFrame ? "(兜底)" : "") + crop;
    }

    /** 一趟是否"干净"：建出盘了、违反象棋规则的扣分低、棋子也不太少。不干净才值得再试倒转。 */
    private static boolean clean(Cands c) {
        return c != null && c.pen <= PEN_CLEAN && c.n >= SPARSE_N;
    }

    /** 把整图转 180° 再检一趟（临时位图用完立刻回收，别让悬浮窗循环里堆内存）。 */
    private static Cands runRot(Bitmap src, int[] excludeRect, int W, int H, Frame hint) {
        Bitmap r = rotate180(src);
        if (r == null) return null;
        Cands c = runOnce(r, rotRect(excludeRect, W, H), hint);
        r.recycle();
        return c;
    }

    /** 把整图转 180°（用白色/透明边角都行，模型只关心中间）。 */
    private static Bitmap rotate180(Bitmap src) {
        try {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(180);
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "DetOcr.rotate180 failed: " + t);
            return null;
        }
    }

    /** 排除矩形跟着转 180°。 */
    private static int[] rotRect(int[] ex, int W, int H) {
        if (ex == null) return null;
        return new int[] { W - 1 - ex[2], H - 1 - ex[3], W - 1 - ex[0], H - 1 - ex[1] };
    }

    /** 90 格跟着转 180°（也供 ShotService 把"红在上"的结果翻成"红在下"再喂引擎）。 */
    public static int[] rotCells(int[] c) {
        int[] o = new int[90];
        for (int r = 0; r < 10; r++)
            for (int cc = 0; cc < 9; cc++) o[r * 9 + cc] = c[(9 - r) * 9 + (8 - cc)];
        return o;
    }

    /** 四角跟着转 180°：转后图的 BR 就是原图的 TL。 */
    private static float[] rotCorners(float[] c, int W, int H) {
        return new float[] {
            W - 1 - c[4], H - 1 - c[5],
            W - 1 - c[6], H - 1 - c[7],
            W - 1 - c[0], H - 1 - c[1],
            W - 1 - c[2], H - 1 - c[3],
        };
    }

    /**
     * 单趟（**两阶段**）：阶段1 找「框」-> 按框裁图 -> 阶段2 在裁图上找棋子 -> 排除悬浮窗
     * -> 用框裁一刀 -> 分簇建盘（建不出来就用框兜底）。
     *
     * hint = 上一帧留下的框（坐标已对齐这一趟的输入图），本帧自己检到框时以本帧为准。
     *
     * 与参考脚本 `predict_two_stage.py` 一一对应：
     *   ① 框按**置信度最高**挑（不是面积）；② 裁图四边各外扩一个棋子宽；③ **从原图裁**；
     *   ④ 坐标加裁图左上角偏移映射回整屏；⑤ 没框时整屏直送（罕见，这时棋子很小、结果只作参考）。
     */
    private static Cands runOnce(Bitmap src, int[] excludeRect, Frame hint) {
        try {
            int W = src.getWidth(), H = src.getHeight();

            // ---- 阶段1：每帧重新定位棋盘；同尺寸旧框只在本帧漏检时兜底。----
            // 仅靠几何/少量棋子无法判断棋盘是否已经移动，持续跳过找框会锁死错误裁图。
            Frame own = null;
            if (hint != null && (stickyW != W || stickyH != H)) {
                android.util.Log.i("xiangqi-ocr", "DetOcr 图尺寸变了 "
                        + stickyW + "x" + stickyH + " -> " + W + "x" + H + "，弃用黏性框");
                hint = null;
                stickyFrame = null;
                stickyAge = 0;
                lastBoardPieces = 0;
            }
            if (forceRelocate || stickyFrame == null || stickyW != W || stickyH != H) {
                own = detectFrame(src, excludeRect);
                if (own != null) forceRelocate = false;
            } else {
                // 正常扫描只复用用户上次确认的框，不再每帧跑 chess_frame。
                own = null;
            }
            if (own != null && !frameSane(own, W, H)) {
                android.util.Log.i("xiangqi-ocr", String.format(java.util.Locale.US,
                        "DetOcr 框几何不可信丢弃 score=%.2f %.0fx%.0f",
                        own.score, own.x1 - own.x0, own.y1 - own.y0));
                own = null;
            }
            Frame fr = (own != null) ? own : hint;          // 本帧漏检时才沿用同尺寸旧框
            if (fr != null && !frameSane(fr, W, H)) fr = null;
            if (fr != null && excludeRect != null && fr.cx() >= excludeRect[0]
                    && fr.cx() <= excludeRect[2] && fr.cy() >= excludeRect[1]
                    && fr.cy() <= excludeRect[3]) { fr = null; own = null; }

            // ---- 裁图：框四边各外扩一个棋子宽；没框就整屏直送 ----
            int x0 = 0, y0 = 0, x1 = W, y1 = H;
            boolean staged = false;
            if (fr != null) {
                double pad = (fr.x1 - fr.x0) * FRAME_PAD_RATIO;
                x0 = (int) Math.max(0, Math.floor(fr.x0 - pad));
                y0 = (int) Math.max(0, Math.floor(fr.y0 - pad));
                x1 = (int) Math.min(W, Math.ceil(fr.x1 + pad));
                y1 = (int) Math.min(H, Math.ceil(fr.y1 + pad));
                staged = (x1 - x0) >= 64 && (y1 - y0) >= 64 && (x1 - x0 < W || y1 - y0 < H);
                if (!staged) { x0 = 0; y0 = 0; x1 = W; y1 = H; }   // 裁了等于没裁，不如不裁
            }

            // ---- 阶段2：在裁图上找棋子，坐标加偏移映射回整屏 ----
            float[][] dets = detectStaged(src, x0, y0, x1, y1);
            if (dets == null) dets = new float[0][];

            // 本帧没找到框，旧框裁图的棋子数骤降时，改用整屏检测结果。
            if (own == null && fr != null && lastBoardPieces >= MIN_BOX * 2
                    && dets.length < lastBoardPieces / 2) {
                // 本帧找不到框，且旧框裁出来的棋子骤降：不能用残缺裁图冒充完整局面。
                fr = null;
                staged = false;
                dets = detectStaged(src, 0, 0, W, H);
                if (dets == null) dets = new float[0][];
            }

            // ---- 框的合理性闸门（这次有棋子可比了）----
            // 分数高不等于框对。**假框比没框更糟**：错的框会让上面"裁图"那一步把真棋子削掉。
            // 对不上就丢掉框；如果刚才真的裁了图，整屏再补一趟（罕见路径，宁可慢也不能掉子）。
            if (fr != null && !framePlausible(fr, dets)) {
                android.util.Log.i("xiangqi-ocr", String.format(java.util.Locale.US,
                        "DetOcr 框不可信（格距%.1f 与棋子中位宽对不上）丢弃 score=%.2f %s",
                        frameGrid(fr), fr.score, staged ? "整屏重跑" : "未裁"));
                fr = null;
                own = null;                                 // 别让这个假框进黏性
                if (staged) {
                    staged = false;
                    float[][] full = detectStaged(src, 0, 0, W, H);
                    if (full != null && full.length > dets.length) dets = full;
                }
            }

            if (dets.length < MIN_BOX) {
                // 棋子少到没法建盘 —— 但只要有框，就还能靠框把网格摆出来
                Cands c0 = stampFrame(buildFromFrame(dets, fr), own, fr);
                if (c0 != null) c0.staged = staged;
                return c0;
            }
            ArrayList<float[]> list = new ArrayList<float[]>();
            int dropped = 0;
            for (float[] dd : dets) {
                if (excludeRect != null && dd[0] >= excludeRect[0] && dd[0] <= excludeRect[2]
                        && dd[1] >= excludeRect[1] && dd[1] <= excludeRect[3]) { dropped++; continue; }
                list.add(dd);
            }
            float[][] pool = list.toArray(new float[list.size()][]);
            if (pool.length < MIN_BOX) {
                Cands c0 = stampFrame(buildFromFrame(pool, fr), own, fr);
                if (c0 != null) c0.staged = staged;
                return c0;
            }
            // ⓐ 裁一刀：框外不可能是棋子（悬浮窗假子/桌面图标在这一步就被削掉）
            float[][] cut = clipToFrame(pool, fr);
            Cands c = findBoards(cut, fr);
            if (c == null || c.n < MIN_BOX) {
                // ⓒ 兜底：主链路没建出来，试试用框摆网格（摆完仍然用棋子把精度拉回来）
                Cands fb = buildFromFrame(cut, fr);
                if (fb != null) { fb.dropped = dropped; fb.staged = staged; return stampFrame(fb, own, fr); }
                return null;
            }
            c.dropped = dropped;
            c.staged = staged;
            return stampFrame(c, own, fr);
        } catch (Throwable t) {
            android.util.Log.e("xiangqi-ocr", "DetOcr.runOnce failed: " + t);
            return null;
        }
    }

    /**
     * **阶段2 的那一趟检测**：把 src 的 [x0,x1) x [y0,y1) 这块喂 chess_pieces，
     * 再把坐标**加上裁图左上角偏移**映射回整屏（用中心点 + 宽高，跟下游的约定一致）。
     * 这一块正好等于整张图时就不真的裁（省一张位图、也省一次拷贝）。
     */
    private static float[][] detectStaged(Bitmap src, int x0, int y0, int x1, int y1) {
        int W = src.getWidth(), H = src.getHeight();
        boolean full = (x0 <= 0 && y0 <= 0 && x1 >= W && y1 >= H);
        Bitmap crop = null;
        try {
            Bitmap in = src;
            if (!full) {
                crop = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
                in = crop;
            }
            float[][] raw = detectGeneric(in, IMGSZ_PIECES, NCLS, pieceSession, pieceInputName, CONF);
            float[][] out = new float[raw.length][];
            for (int i = 0; i < raw.length; i++) {
                float[] b = raw[i];
                out[i] = new float[] {
                    (b[0] + b[2]) / 2 + x0, (b[1] + b[3]) / 2 + y0,
                    b[4], b[5], b[6], b[7]
                };
            }
            return out;
        } catch (Throwable t) {
            android.util.Log.w("xiangqi-ocr", "DetOcr 裁图检测失败: " + t);
            return null;
        } finally {
            if (crop != null) crop.recycle();
        }
    }

    /**
     * 框的几何闸门（**不用棋子就能判**）：一个棋盘框至少得像那么回事。
     * 面积要落在整图的 4%~92%、长短边比 0.6~1.5、边长 ≥ 40px。
     * 这道闸门是白送的 —— 它在**裁图之前**跑，能挡住"错的框把真棋子裁没"这种事。
     */
    private static boolean frameSane(Frame fr, int W, int H) {
        if (fr == null || fr.x0 < 0 || fr.y0 < 0 || fr.x1 > W || fr.y1 > H) return false;
        float fw = fr.x1 - fr.x0, fh = fr.y1 - fr.y0;
        if (fw < 40 || fh < 40) return false;
        double area = (double) fw * fh / ((double) W * H);
        if (area < 0.04 || area > 0.92) return false;
        double ar = fw / fh;
        return ar > 0.60 && ar < 1.50;
    }

    /** 给候选贴上"本帧自己检到的框(own)"和"实际用到的框(eff)"，黏性维护和日志都靠这两个。 */
    private static Cands stampFrame(Cands c, Frame own, Frame eff) {
        if (c == null) return null;
        c.ownFrame = own;
        c.frameScore = (eff != null) ? eff.score : 0f;
        return c;
    }

    /**
     * ⓐ 用「框」裁一刀：框外不可能是棋子。
     * 留 FRAME_MARGIN 的余量是因为框可能比棋盘外沿略紧；
     * **裁完不足 MIN_BOX 就整批退回** —— 宁可多留点噪声，也不能因为框偏了就把棋盘裁没。
     */
    private static float[][] clipToFrame(float[][] in, Frame fr) {
        if (fr == null || in.length == 0) return in;
        float mx = (fr.x1 - fr.x0) * FRAME_MARGIN, my = (fr.y1 - fr.y0) * FRAME_MARGIN;
        ArrayList<float[]> out = new ArrayList<float[]>();
        for (float[] d : in)
            if (d[0] >= fr.x0 - mx && d[0] <= fr.x1 + mx
                    && d[1] >= fr.y0 - my && d[1] <= fr.y1 + my) out.add(d);
        if (out.size() < MIN_BOX) return in;
        if (out.size() != in.length) {
            android.util.Log.i("xiangqi-ocr", "DetOcr 框裁剪 " + in.length + " -> " + out.size());
        }
        return out.toArray(new float[out.size()][]);
    }

    /** 「框」跟着整图转 180°（180° 旋转是自逆的，转过去和转回来是同一个式子）。 */
    private static Frame rotFrame(Frame f, int W, int H) {
        if (f == null) return null;
        Frame r = new Frame();
        r.x0 = W - 1 - f.x1; r.x1 = W - 1 - f.x0;
        r.y0 = H - 1 - f.y1; r.y1 = H - 1 - f.y0;
        r.score = f.score;
        return r;
    }

    // ==================================================================
    // 3. 框清单 -> 棋盘
    // ==================================================================

    /** 一个建好盘的候选。 */
    private static class Cands {
        int[] cells = new int[90];
        float[] corners = new float[8];
        float[][] all;             // 参与本候选的框
        int[] inl;                 // 局内框下标
        double sx, sy, resid;
        int n, pen, dx, dy, dropped;
        boolean flip;
        double cell, score;
        /** 这一趟是不是靠"把整图转 180°"才认出来的（pickBoard 判优时定）。 */
        boolean rot = false;
        /** 实际用到的「框」的置信度（0 = 全程没有框）。日志里报的就是它。 */
        float frameScore;
        /** 本帧**自己检到**的框（不是沿用上一帧的），只用来维护黏性。 */
        Frame ownFrame;
        /** 这个盘是不是靠「框」兜底摆出来的（主链路没建出来时的退路，日志里标一下）。 */
        boolean byFrame;
        /** 这次用的棋子是不是来自"按框裁出来的那一小块"（而不是整屏直送）。日志里标出来，真机上好确认。 */
        boolean staged;
    }

    /**
     * 按棋子尺寸分簇，每簇试建盘。
     * 有「框」时（ⓑ 候选择优）：先挑"九宫外框跟框重叠得最好"的那个候选，
     * 一屏上有目标盘 + 复盘小盘 + 皮肤预览时，这一步比打分可靠得多；没有合格的重叠才退回打分。
     */
    private static Cands findBoards(float[][] dets, Frame fr) {
        ArrayList<float[]> pool = new ArrayList<float[]>();
        for (float[] d : dets) pool.add(d);
        Cands bestByScore = null, bestByFrame = null;
        double bestScoreQ = 0, bestFrameQ = 0;
        for (int it = 0; it < MAX_BOARDS && pool.size() >= MIN_BOX; it++) {
            // 取面积最大的一颗当种子，收拢 ±32% 尺寸的框
            int seed = 0;
            for (int i = 1; i < pool.size(); i++) {
                float[] a = pool.get(i), b = pool.get(seed);
                if (a[2] * a[3] > b[2] * b[3]) seed = i;
            }
            float sw = pool.get(seed)[2], sh = pool.get(seed)[3];
            ArrayList<float[]> grp = new ArrayList<float[]>(), rest = new ArrayList<float[]>();
            for (float[] d : pool) {
                if (d[2] > 0.70 * sw && d[2] < 1.38 * sw && d[3] > 0.70 * sh && d[3] < 1.38 * sh) grp.add(d);
                else rest.add(d);
            }
            if (grp.size() >= MIN_BOX) {
                Cands c = buildBoard(grp.toArray(new float[grp.size()][]), fr);
                if (c != null && c.n >= MIN_BOX && c.inl.length >= MIN_BOX) {
                    if (bestByScore == null || c.score > bestScoreQ) { bestByScore = c; bestScoreQ = c.score; }
                    if (fr != null) {
                        double io = rectIoU(c.corners, fr);
                        if (io >= FRAME_IOU && io > bestFrameQ) { bestByFrame = c; bestFrameQ = io; }
                    }
                }
            }
            pool = rest;
        }
        return (bestByFrame != null) ? bestByFrame : bestByScore;
    }

    /** 候选盘的四角外框（corners 的 TL 与 BR 两点）跟「框」的重叠率。 */
    private static double rectIoU(float[] corners, Frame f) {
        float ax0 = Math.min(corners[0], corners[4]), ax1 = Math.max(corners[0], corners[4]);
        float ay0 = Math.min(corners[1], corners[5]), ay1 = Math.max(corners[1], corners[5]);
        float ix = Math.min(ax1, f.x1) - Math.max(ax0, f.x0);
        float iy = Math.min(ay1, f.y1) - Math.max(ay0, f.y0);
        if (ix <= 0 || iy <= 0) return 0;
        double inter = (double) ix * iy;
        double ua = (double) (ax1 - ax0) * (ay1 - ay0);
        double uf = (double) (f.x1 - f.x0) * (f.y1 - f.y0);
        return inter / (ua + uf - inter + 1e-6);
    }

    /** 单簇建盘：尺寸过滤 -> 点阵 RANSAC + 甩离群 -> 语法锚定。 */
    private static Cands buildBoard(float[][] dets, Frame fr) {
        int n = dets.length;
        if (n < 4) return null;
        // 尺寸过滤：棋子大小一致，离群的都是误检
        double[] ws = new double[n], hs = new double[n];
        for (int i = 0; i < n; i++) { ws[i] = dets[i][2]; hs[i] = dets[i][3]; }
        double mw = median(ws), mh = median(hs);
        ArrayList<Integer> keepL = new ArrayList<Integer>();
        for (int i = 0; i < n; i++)
            if (dets[i][2] > 0.70 * mw && dets[i][2] < 1.45 * mw
                    && dets[i][3] > 0.70 * mh && dets[i][3] < 1.45 * mh) keepL.add(i);
        if (keepL.size() < 4) return null;
        int[] keep = new int[keepL.size()];
        for (int i = 0; i < keep.length; i++) keep[i] = keepL.get(i);

        Refine rf = refine(dets, keep, 0.28, 8);
        if (rf == null) return null;

        Cands c = new Cands();
        c.all = dets;
        c.inl = rf.idx;
        return finish(c, rf.sx, rf.sy, rf.ox, rf.oy, rf.resid,
                latticePts(dets, rf.idx, rf.ox, rf.oy, rf.sx, rf.sy));
    }

    /** 局内点 -> 点阵整数索引 {kx, ky, 兵种码, 红=1}。 */
    private static int[][] latticePts(float[][] dets, int[] idx,
                                      double ox, double oy, double sx, double sy) {
        int[][] pts = new int[idx.length][4];
        for (int i = 0; i < idx.length; i++) {
            int di = idx[i];
            pts[i][0] = (int) Math.round((dets[di][0] - ox) / sx);
            pts[i][1] = (int) Math.round((dets[di][1] - oy) / sy);
            int cls = (int) dets[di][5];
            pts[i][2] = CLS_TYPE[cls];
            pts[i][3] = CLS_SIDE[cls] == Board.RED ? 1 : 0;
        }
        return pts;
    }

    /**
     * 收尾：拿"点阵整数坐标 + 兵种"去锚定 9x10，再算出 90 格数组和棋盘四角。
     * `buildBoard`（棋子拟合出的点阵）和 `buildFromFrame`（框摆出的点阵）共用这一段。
     */
    private static Cands finish(Cands c, double sx, double sy, double ox, double oy,
                                double resid, int[][] pts) {
        Anchor an = bestAnchor(pts);
        if (an == null) return null;
        c.sx = sx; c.sy = sy; c.resid = resid;
        c.dx = an.dx; c.dy = an.dy; c.flip = an.flip; c.pen = an.pen;
        // 输出必须与全 App 的约定一致：row 0 = 截图里靠上的那一行，col 0 = 靠左那一列。
        // （整个 App 只认这一个约定：Board 不带"己方"概念，BoardView 的"换边"也只改显示方向。）
        // 锚定时用 legality 判断"红方底线在图像上方还是下方"（flip），但输出要旋回图像方向；
        // 否则黑方用户的截图会被整盘转 180°，表现为"己方莫名其妙变成红方"。
        c.n = 0;
        for (int r = 0; r < 10; r++)
            for (int cc = 0; cc < 9; cc++) {
                int v = an.grid[r * 9 + cc];
                if (v == 0) continue;
                int sr = an.flip ? 9 - r : r;     // 旋回图像坐标（flip 只动行）
                c.cells[sr * 9 + cc] = v;
                c.n++;
            }
        // 棋盘四角（格点中心连线）。y 直接跟点阵索引走，与 flip 无关，保证 y0 < y1（图像上方在前）
        double x0 = ox + an.dx * sx;
        double x1 = ox + (8 + an.dx) * sx;
        double y0 = oy + an.dy * sy;
        double y1 = oy + (9 + an.dy) * sy;
        c.corners = new float[] {
            (float) x0, (float) y0, (float) x1, (float) y0,
            (float) x1, (float) y1, (float) x0, (float) y1
        };
        c.cell = (sx + sy) / 2.0;
        c.score = c.n * 4.0 - resid * 40.0 - an.pen * 0.4 + Math.min(c.cell / 50.0, 4.0);
        return c;
    }

    /**
     * ⓒ 兜底建盘：主链路（棋子拟合）没建出来时，用「框」先把网格摆出来，再让棋子把精度补回来。
     *
     * 摆法：框边长 ≈ 9 个格距（实测 568/63.5 = 8.94），且框的横竖中线与棋盘中线重合，所以
     *   `格距 = 边长 / 9`、`原点 = 框中心 - (4 格, 4.5 格)`
     * （横向 9 列只跨 8 个格距，左右各余半格；纵向 10 行跨 9 个格距，正好填满）。
     * 摆完把落在格子附近的棋子贴到整数格上，再**最小二乘重估**格距和原点、并重贴一遍。
     *
     * 为什么非要重估：框自己直接算格距的误差有 0.1~0.15 格（实测 `_framegeom.py`），
     * 比棋子拟合的 0.013~0.017 格差一个量级 —— 光靠框必然错格。
     * 一句话：**框给拓扑（棋盘在哪、朝哪），棋子给精度。**
     */
    private static Cands buildFromFrame(float[][] dets, Frame fr) {
        if (fr == null || dets.length == 0) return null;
        double span = fr.side();
        if (span < 24) return null;
        // 横竖分开算：框是贴棋盘外沿的，横向 8 格、竖向 9 格（见 FRAME_COLS 的说明）
        double sx = (fr.x1 - fr.x0) / FRAME_COLS, sy = (fr.y1 - fr.y0) / FRAME_ROWS;
        if (sx < 8 || sy < 8) return null;
        double ox = fr.cx() - 4 * sx, oy = fr.cy() - 4.5 * sy;
        int[] inl = null;
        int[][] pts = null;
        double resid = 0;
        float[] conf = new float[dets.length];
        for (int i = 0; i < dets.length; i++) conf[i] = dets[i][4];
        int[] ord = argsortDesc(conf, dets.length);   // 置信度高的先占格
        for (int rd = 0; rd < 3; rd++) {
            // 贴格：同一个格子被多个框抢时，留置信度最高的那个
            int[] pick = new int[90];
            java.util.Arrays.fill(pick, -1);
            for (int oi = 0; oi < ord.length; oi++) {
                int i = ord[oi];
                int kx = (int) Math.round((dets[i][0] - ox) / sx);
                int ky = (int) Math.round((dets[i][1] - oy) / sy);
                if (kx < 0 || kx > 8 || ky < 0 || ky > 9) continue;
                // 离最近格点超过 0.45 格就认为不是这一格的子（0.5 是边界，留点余量）
                if (Math.max(Math.abs((dets[i][0] - ox) / sx - kx),
                             Math.abs((dets[i][1] - oy) / sy - ky)) > 0.45) continue;
                int sq = ky * 9 + kx;
                if (pick[sq] < 0) pick[sq] = i;
            }
            int m = 0;
            for (int q = 0; q < 90; q++) if (pick[q] >= 0) m++;
            if (m < MIN_BOX) return null;
            inl = new int[m];
            pts = new int[m][4];
            int j = 0;
            for (int q = 0; q < 90; q++) {
                if (pick[q] < 0) continue;
                int i = pick[q];
                int cls = (int) dets[i][5];
                inl[j] = i;
                pts[j][0] = q % 9;
                pts[j][1] = q / 9;
                pts[j][2] = CLS_TYPE[cls];
                pts[j][3] = CLS_SIDE[cls] == Board.RED ? 1 : 0;
                j++;
            }
            // 最小二乘重估：x = ox + kx*sx，对 kx 做一元回归（y 同理）
            double mkx = 0, mky = 0, mx = 0, my = 0;
            for (int q = 0; q < m; q++) {
                mkx += pts[q][0]; mky += pts[q][1];
                mx += dets[inl[q]][0]; my += dets[inl[q]][1];
            }
            mkx /= m; mky /= m; mx /= m; my /= m;
            double nX = 0, dX = 0, nY = 0, dY = 0;
            for (int q = 0; q < m; q++) {
                double a = pts[q][0] - mkx, b = dets[inl[q]][0] - mx;
                double e = pts[q][1] - mky, g = dets[inl[q]][1] - my;
                nX += a * b; dX += a * a;
                nY += e * g; dY += e * e;
            }
            if (dX < 1e-6 || dY < 1e-6) break;      // 子全挤在一列/一行，回归没意义
            double nsx = nX / dX, nsy = nY / dY;
            if (nsx <= 1 || nsy <= 1) break;
            sx = nsx; sy = nsy;
            ox = mx - sx * mkx;
            oy = my - sy * mky;
            double rs = 0;
            for (int q = 0; q < m; q++) {
                rs += Math.max(Math.abs(dets[inl[q]][0] - (ox + pts[q][0] * sx)) / sx,
                               Math.abs(dets[inl[q]][1] - (oy + pts[q][1] * sy)) / sy);
            }
            resid = rs / m;
        }
        if (pts == null || inl == null || inl.length < MIN_BOX) return null;
        Cands c = new Cands();
        c.all = dets;
        c.inl = inl;
        c.byFrame = true;
        Cands r = finish(c, sx, sy, ox, oy, resid, pts);
        if (r != null) {
            android.util.Log.i("xiangqi-ocr", String.format(java.util.Locale.US,
                    "DetOcr 框兜底建盘 n=%d 格距%.0f/%.0f 残差%.2f%%", r.n, r.sx, r.sy, r.resid * 100));
        }
        return r;
    }

    private static class Refine {
        double sx, sy, ox, oy, resid;
        int[] idx;
    }

    /** 点阵拟合 + 迭代甩离群点（别的悬浮窗的假棋子在这一步被踢掉）。 */
    private static Refine refine(float[][] dets, int[] keep, double tol, int rounds) {
        int[] cur = keep.clone();
        Refine out = null;
        for (int rd = 0; rd < rounds; rd++) {
            if (cur.length < 4) break;
            double[] xs = new double[cur.length], ys = new double[cur.length];
            double[] ww = new double[cur.length], hh = new double[cur.length];
            for (int i = 0; i < cur.length; i++) {
                xs[i] = dets[cur[i]][0]; ys[i] = dets[cur[i]][1];
                ww[i] = dets[cur[i]][2]; hh[i] = dets[cur[i]][3];
            }
            double mw = median(ww), mh = median(hh);
            // ★★★ 2026-09-29：**格距的强先验 = 棋子宽度**。
            //
            //   棋盘的一格 ≈ 一颗棋子占的地方，所以格距必然落在"棋子宽"附近
            //   （实测：格距 55~61，棋子中位宽 53~58，比值稳定在 0.94~1.10）。
            //
            //   旧代码把搜索范围开到 `0.80 ~ 1.35 倍棋子宽`（≈±30%），对**子多且铺满整盘**
            //   的局面没问题（点集在每个方向都有密集的峰，一维 RANSAC 一锁就准）；
            //   但**子少又只占局部**（稀疏残局、拍屏只拍到半边）时，点集太稀，
            //   RANSAC 会锁到"格距的分数或倍数"上，而且 X/Y 两维还各锁各的：
            //   实测 `D:\YOLO\chess\ping` 那张 18 子的图拟出 **sx=69.3 / sy=45.3（比值 1.53）** ——
            //   棋盘格子必须是方的，1.53 荒谬。后果是列跨度只有 7、行跨度涨到 12（超出 9），
            //   `bestAnchor` 把这些错索引硬塞进 9x10、塞不下的就丢 ⇒ **18 个子只剩 11 格**
            //   （真机实测 `n=11 扣分36`）。
            //
            //   这正是"桌面脚本全中、App 认不出"的根因：脚本 `predict_two_stage.py` 不做
            //   点阵拟合，直接按框摆格子，所以压根不会踩这个坑。
            //
            //   把范围收到 **±12%**（0.88~1.14）后，RANSAC 在物理上就没有锁错的空间了：
            //   同一张图重拟得 **sx=55.3 / sy=58.8（比值 0.94）**，列跨度 9、行跨度 9，全对。
            //   ±12% 的余量足够容纳透视变形（拍屏轻微斜着拍，横竖格距本来就会差几个点）。
            double kx0 = mw * 0.88, kx1 = mw * 1.14;
            double ky0 = mh * 0.88, ky1 = mh * 1.14;
            Lat fx = fitLattice(xs, kx0, kx1, tol);
            Lat fy = fitLattice(ys, ky0, ky1, tol);
            if (fx == null || fy == null) break;
            double sx = fx.sp, ox = fx.o, sy = fy.sp, oy = fy.o;
            double[] res = new double[cur.length];
            int[] kxs = new int[cur.length], kys = new int[cur.length];
            double sum = 0;
            for (int i = 0; i < cur.length; i++) {
                double[] d = { dets[cur[i]][0], dets[cur[i]][1] };
                int kx = (int) Math.round((d[0] - ox) / sx);
                int ky = (int) Math.round((d[1] - oy) / sy);
                double rx = Math.abs(d[0] - (ox + kx * sx)) / sx;
                double ry = Math.abs(d[1] - (oy + ky * sy)) / sy;
                res[i] = Math.max(rx, ry);
                kxs[i] = kx; kys[i] = ky;
                sum += res[i];
            }
            Refine nr = new Refine();
            nr.sx = sx; nr.sy = sy; nr.ox = ox; nr.oy = oy;
            nr.resid = sum / cur.length;
            nr.idx = cur.clone();
            out = nr;
            int badN = 0;
            for (int i = 0; i < cur.length; i++) if (res[i] > tol) badN++;
            int kxMin = kxs[0], kxMax = kxs[0], kyMin = kys[0], kyMax = kys[0];
            for (int i = 1; i < cur.length; i++) {
                if (kxs[i] < kxMin) kxMin = kxs[i];
                if (kxs[i] > kxMax) kxMax = kxs[i];
                if (kys[i] < kyMin) kyMin = kys[i];
                if (kys[i] > kyMax) kyMax = kys[i];
            }
            if (badN == 0 && (kyMax - kyMin) <= 9 && (kxMax - kxMin) <= 8) break;
            // 踢掉残差最大的那个；残差都合格就踢"最能撑大跨度"的那个
            int worst = -1;
            double worstV = -1;
            for (int i = 0; i < cur.length; i++) {
                boolean take;
                if (badN > 0) take = res[i] > tol;
                else take = (kxs[i] == kxMin || kxs[i] == kxMax || kys[i] == kyMin || kys[i] == kyMax);
                if (take && res[i] > worstV) { worstV = res[i]; worst = i; }
            }
            if (worst < 0) break;
            int[] nx = new int[cur.length - 1];
            for (int i = 0, j = 0; i < cur.length; i++) if (i != worst) nx[j++] = cur[i];
            cur = nx;
        }
        return out;
    }

    private static class Lat {
        double sp, o, mr;
        int n;
    }

    /** 一维点阵 RANSAC：返回 {间距, 原点, 局内点占比}。 */
    private static Lat fitLattice(double[] vals, double smin, double smax, double tol) {
        if (vals.length == 0) return null;
        double vmin = vals[0];
        for (double v : vals) if (v < vmin) vmin = v;
        Lat best = null;
        double bestKeyN = -1, bestKeyR = 0;
        for (double sp = smin; sp <= smax; sp += 0.5) {
            double step = sp / 40.0;
            for (double o = vmin - sp; o < vmin; o += step) {
                int cnt = 0;
                double s = 0;
                for (double v : vals) {
                    double m = (v - o + sp / 2) % sp;
                    if (m < 0) m += sp;
                    double r = Math.abs(m - sp / 2);
                    if (r < tol * sp) { cnt++; s += r; }
                }
                if (cnt == 0) continue;
                double mr = (s / cnt) / sp;
                // 局内点多优先，同分看残差小
                if (best == null || cnt > bestKeyN || (cnt == bestKeyN && -mr > bestKeyR)) {
                    best = new Lat();
                    best.sp = sp; best.o = o; best.mr = mr; best.n = cnt;
                    bestKeyN = cnt; bestKeyR = -mr;
                }
            }
        }
        return best;
    }

    private static class Anchor {
        int pen, dx, dy;
        boolean flip;
        int[] grid;
    }

    /** pts: {kx, ky, Board兵种码, 红=1}。试 (dx,dy,flipY) 让所有点落进 9x10 且最合法。 */
    private static Anchor bestAnchor(int[][] pts) {
        if (pts.length == 0) return null;
        int kxMin = pts[0][0], kxMax = pts[0][0], kyMin = pts[0][1], kyMax = pts[0][1];
        for (int[] p : pts) {
            if (p[0] < kxMin) kxMin = p[0];
            if (p[0] > kxMax) kxMax = p[0];
            if (p[1] < kyMin) kyMin = p[1];
            if (p[1] > kyMax) kyMax = p[1];
        }
        Anchor best = null;
        int spanc = kxMax - kxMin;
        for (int dx = -spanc; dx < 9; dx++) {
            for (int dy = -(kyMax - kyMin); dy < 10; dy++) {
                for (int f = 0; f < 2; f++) {
                    boolean flip = f == 1;
                    int pen = 0;
                    int[] grid = new int[90];
                    boolean ok = true;
                    for (int[] p : pts) {
                        int c = p[0] - dx;
                        int r = p[1] - dy;
                        if (flip) r = 9 - r;
                        if (c < 0 || c > 8 || r < 0 || r > 9) { ok = false; break; }
                        int sq = r * 9 + c;
                        if (grid[sq] != 0) { pen += 20; continue; }
                        grid[sq] = p[2] | (p[3] == 1 ? Board.RED : Board.BLACK);
                    }
                    if (!ok) continue;
                    pen += legality(grid);
                    if (spanc < 8) pen += (8 - spanc) * 3;   // 偏好"整盘 9 列铺满"
                    if (best == null || pen < best.pen) {
                        best = new Anchor();
                        best.pen = pen; best.dx = dx; best.dy = dy; best.flip = flip; best.grid = grid;
                    }
                }
            }
        }
        return best;
    }

    /** 象棋规则违规扣分（越小越好）。用来定网格原点和上下朝向。 */
    private static int legality(int[] grid) {
        int pen = 0;
        int nkR = 0, nkB = 0, total = 0;
        int[] cnt = new int[8 * 2];       // 下标 = code*2 + (红?0:1)
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                int v = grid[r * 9 + c];
                if (v == 0) continue;
                int code = v & 7;
                boolean red = (v & Board.RED) != 0;
                total++;
                cnt[code * 2 + (red ? 0 : 1)]++;
                if (code == Board.KING) {
                    if (red) { nkR++; if (!(r >= 7 && r <= 9 && c >= 3 && c <= 5)) pen += 12; }
                    else { nkB++; if (!(r >= 0 && r <= 2 && c >= 3 && c <= 5)) pen += 12; }
                }
                if (code == Board.ADVISOR && !(red ? ADV_R[r][c] : ADV_B[r][c])) pen += 4;
                if (code == Board.ELEPHANT && !(red ? ELE_R[r][c] : ELE_B[r][c])) pen += 6;
                if (code == Board.PAWN) {
                    if (red && !(r >= 0 && r <= 6)) pen += 6;
                    if (!red && !(r >= 3 && r <= 9)) pen += 6;
                }
            }
        }
        for (int code = 1; code <= 7; code++) {
            for (int s = 0; s < 2; s++) {
                int k = cnt[code * 2 + s];
                if (k > MAXN[code]) pen += (k - MAXN[code]) * 5;
            }
        }
        pen += Math.abs(nkR - 1) * 10 + Math.abs(nkB - 1) * 10;
        if (total > 32) pen += (total - 32) * 5;
        return pen;
    }

    private static double median(double[] v) {
        double[] a = v.clone();
        java.util.Arrays.sort(a);
        return a[a.length / 2];
    }
}

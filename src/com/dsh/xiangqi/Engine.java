package com.dsh.xiangqi;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 皮卡鱼（Pikafish）UCI 引擎对接。
 * 引擎以 libpikafish.so 形式打包进 APK 的 jniLibs，安装后位于应用私有 native 库目录（可执行）。
 * NNUE 权重放在 assets，首次使用时释放到 filesDir，通过 EvalFile 选项传给引擎。
 */
public class Engine {

    private final Context ctx;
    private Process proc;
    private BufferedWriter out;
    private BufferedReader in;
    private boolean started = false;
    private String name = "Pikafish";
    private int score = 0;      // 走子方视角的 centipawn
    private int depth = 0;
    private volatile String error = null;

    public Engine(Context c) { this.ctx = c; }

    public String getName() { return name; }
    public int getScore() { return score; }
    public int getDepth() { return depth; }
    public String getError() { return error; }
    public boolean isStarted() { return started; }

    public String binaryPath() {
        return ctx.getApplicationInfo().nativeLibraryDir + "/libpikafish.so";
    }

    private void send(String cmd) throws IOException {
        out.write(cmd);
        out.write("\n");
        out.flush();
    }

    // ★★★ 2026-09-29 诊断用：引擎进程还活着吗 / 退出码是多少。
    //   Process.isAlive() 是 API 26+ 的方法，本工程 minSdk=21，所以用 try/catch 兜住低版本。
    private String procAlive() {
        if (proc == null) return "proc=null";
        try { return String.valueOf(proc.isAlive()); }
        catch (Throwable t) { return "?" + t.getClass().getSimpleName(); }
    }

    private String exitValueSafe() {
        if (proc == null) return "-";
        try { return String.valueOf(proc.exitValue()); }
        catch (IllegalThreadStateException e) { return "notExited"; }
        catch (Throwable t) { return "?" + t.getClass().getSimpleName(); }
    }

    private boolean waitFor(String token, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String line;
        while (System.currentTimeMillis() < deadline) {
            line = in.readLine();
            if (line == null) return false;
            if (line.startsWith("id name")) name = line.substring(8).trim();
            if (line.contains(token)) return true;
        }
        return false;
    }

    /** 启动引擎并完成 UCI 握手；失败返回 false（调用方回退到内置 AI）。 */
    public synchronized boolean start() {
        if (started) return true;
        long tAll = System.currentTimeMillis();
        try {
            File bin = new File(binaryPath());
            if (!bin.exists()) { error = "找不到引擎文件: " + bin.getAbsolutePath(); return false; }
            long t0 = System.currentTimeMillis();
            File nnue = ensureNnue();
            long tNnue = System.currentTimeMillis();
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.directory(ctx.getFilesDir());
            proc = pb.start();
            out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
            in = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            send("uci");
            if (!waitFor("uciok", 20000)) { error = "引擎无响应 (uciok 超时)"; stop(); return false; }
            long tUci = System.currentTimeMillis();
            if (nnue != null) {
                send("setoption name EvalFile value " + nnue.getAbsolutePath());
            }
            // ★ 2026-09-23：线程数改成常量，方便对比实验。
            //   实测（真机 ec09de34 / 天玑 9300+ 8核）：NPS 加速比 1→2→4→8 线程 = 1 / 2.14 / 4.15 / 7.84×，
            //   但"达到同一深度"的有效加速只有 1 / 1.13 / 1.73 / 1.47×（Lazy SMP 节点数也涨）。
            //   所以 4 线程是收益拐点；8 线程反而退步（任务被摊到小核上）。
            send("setoption name Threads value " + engineThreads);
            send("setoption name Hash value " + (pendingHash != null ? pendingHash : "64"));
            send("isready");
            if (!waitFor("readyok", 60000)) { error = "引擎加载权重超时"; stop(); return false; }
            long tReady = System.currentTimeMillis();
            started = true;
            error = null;
            // ★ 2026-09-22：分段计时，搞清楚"约 10 秒"到底花在哪一段。
            //   准备权重(可能要从 assets 拷 50MB) / fork 进程 / uci 握手 / 加载 nnue。
            Diag.log("Engine", String.format(
                    "start 耗时: 准备权重 %dms + 起进程&uci握手 %dms + 加载nnue %dms = 总计 %dms"
                            + "（nnue=%s %d字节）",
                    tNnue - t0, tUci - tNnue, tReady - tUci, tReady - tAll,
                    nnue == null ? "null" : nnue.getName(),
                    nnue == null ? 0 : nnue.length()));
            return true;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            stop();
            return false;
        }
    }

    private File ensureNnue() throws IOException {
        File f = new File(ctx.getFilesDir(), "pikafish.nnue");
        // ★ 2026-09-22 修：以前判据是 `length() > 1000000L`，只要超过 1MB 就认为"拷好了"。
        //   万一上次拷到一半被强杀（50MB 要拷几秒，用户中途退出很常见），
        //   就会留下一个半截文件，之后永远读它 → 引擎加载慢/失败，且怎么看文件都在。
        //   改成**拿 assets 里的原始长度精确比对**，长度一致才算完整。
        long want = -1;
        try {
            android.content.res.AssetFileDescriptor afd = ctx.getAssets().openFd("pikafish.nnue");
            if (afd != null) { want = afd.getLength(); afd.close(); }
        } catch (Throwable ignored) { }
        boolean exist = f.exists();
        long have = exist ? f.length() : -1;
        // ★ 诊断（2026-09-22）：把"有没有 / 多大 / 期望多大 / 走没走拷贝"全打出来。
        //   如果这里每次都打"要拷"，说明 exists 判断失效了 —— 那就是"每次启动慢十秒"的元凶。
        Diag.log("Engine", "ensureNnue: 存在=" + exist + " 现有=" + have
                + " 期望=" + want + " 路径=" + f.getAbsolutePath()
                + " → " + ((exist && (want <= 0 ? have > 1000000L : have == want)) ? "直接复用" : "需要拷贝"));
        if (exist && (want <= 0 ? have > 1000000L : have == want)) return f;
        long tc0 = System.currentTimeMillis();
        InputStream is = null;
        FileOutputStream os = null;
        try {
            is = ctx.getAssets().open("pikafish.nnue");
            os = new FileOutputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = is.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
            os.flush();
            os.close(); os = null;
            long ms = System.currentTimeMillis() - tc0;
            Diag.log("Engine", "ensureNnue 拷贝完成: " + total + " 字节 用时 " + ms + "ms（"
                    + (ms > 0 ? (total / 1024 / 1024 * 1000L / ms) : 0) + " MB/s）");
        } finally {
            if (os != null) try { os.close(); } catch (IOException ignored) { }
            if (is != null) try { is.close(); } catch (IOException ignored) { }
        }
        return f;
    }

    /** 流式分析回调：depth 递增过程中不断回传最新结果。 */
    public interface StreamListener {
        void onUpdate(java.util.List<Line> lines, int depth);
    }

    private static java.util.List<Line> sorted(java.util.HashMap<Integer, Line> map) {
        java.util.List<Line> out = new java.util.ArrayList<Line>(map.values());
        java.util.Collections.sort(out, new java.util.Comparator<Line>() {
            public int compare(Line a, Line b) { return b.score - a.score; }
        });
        return out;
    }

    /**
     * 逐层加深的实时分析（go infinite）：从 1 层开始不断加深，
     * 每次深度变化就回调一次，直到 maxMs 用尽或被 stopAnalysis() 打断。
     */
    public synchronized void analyzeStream(Board board, long maxMs, int multiPv, StreamListener listener) {
        analyzeStream(board, maxMs, multiPv, 0, listener);
    }

    /**
     * 逐层加深的实时分析（可指定**最大思考深度**）。
     *
     * ★ 2026-09-22 加 `maxDepth`：皮卡鱼精简版**没有 Skill Level 这种"调棋力"的选项**，
     *   所以要让它下得"像人/不像神"，唯一的手段就是**限深** —— 到层数就自己收手出招。
     *   走子引擎（AI 对弈）用 12 层，分析引擎用 0（不限深、跑满 maxMs 追求准确）。
     *   以前想靠"限时 300ms"来限强，实测毫无作用：300ms 也够搜到二十几层，
     *   结果就是"AI 强得不像话、用户下不过"（用户反馈"AI 走子太强了"）。
     */
    public synchronized void analyzeStream(Board board, long maxMs, int multiPv, int maxDepth, StreamListener listener) {
        error = null;
        if (!started && !start()) { listener.onUpdate(new java.util.ArrayList<Line>(), 0); return; }
        try {
            long t0 = System.currentTimeMillis();
            send("setoption name MultiPV value " + multiPv);
            // ★★★ 2026-09-29：局面非法 → 不喂引擎（喂了它会 exit(1)，然后无限重启）。
            //   直接回调"0 路 + 原因"，让界面显示一句人话，别再对着「等待引擎…」干等。
            String illegal = positionAndSync(board);
            if (illegal != null) {
                error = illegal;
                listener.onUpdate(new java.util.ArrayList<Line>(), 0);
                return;
            }
            long tSync = System.currentTimeMillis();
            // 组合设置交给 UCI 引擎原生处理：限层与限时可以同时生效；
            // 只限时用 movetime，避免 readLine 阻塞时 Java 侧截止时间无法触发 stop。
            if (maxDepth > 0 && maxMs > 0) send("go depth " + maxDepth + " movetime " + maxMs);
            else if (maxDepth > 0) send("go depth " + maxDepth);
            else if (maxMs > 0) send("go movetime " + maxMs);
            else send("go infinite");
            java.util.HashMap<Integer, Line> best = new java.util.HashMap<Integer, Line>();
            // ★ 2026-09-23：maxMs <= 0 表示**不限时**（一直加深，直到被 stopAnalysis() 打断）。
            //   悬浮窗要的就是这个：局面不变就一直算，变了才打断重来。
            // maxMs>0 是悬浮窗的硬时限；到时发送 stop，收到 bestmove 后正常收尾。
            long deadline = maxMs > 0 ? System.currentTimeMillis() + maxMs : Long.MAX_VALUE;
            int lastDepth = -1;
            boolean sentStop = false;
            boolean pipeClosed = false;
            // ★★★ 2026-09-25 分段计时（查"为什么无产出"）：把"同步用了多久 / go 之后多久收到第一条 info /
            //   一共几条 / 为什么退出"全部打出来。之前只看得见最终层数，卡在哪一段完全瞎猜。
            long tGo = System.currentTimeMillis();
            int infoCount = 0;
            boolean loggedFirst = false;
            while (true) {
                if (!sentStop && System.currentTimeMillis() > deadline) { send("stop"); sentStop = true; }
                String line = in.readLine();
                if (line == null) {
                    error = "引擎输出管道已关闭";
                    pipeClosed = true;
                    Diag.log("Engine", "analyzeStream: 读管道得到 null（引擎进程已死），已收 "
                            + infoCount + " 条 info，go 之后过了 "
                            + (System.currentTimeMillis() - tGo) + "ms");
                    break;
                }
                if (line.startsWith("info ") && line.contains(" pv ")) {
                    infoCount++;
                    // ★★★ 2026-09-25 诊断：把前几条 info 的 depth 直接打出来，并验证 parseLine 是否解析成功。
                    //   实测症状是"层数永远停在 d1"，而引擎明明 14ms 就出第一条 info ——
                    //   到底是"引擎只吐 d1"还是"解析/回调出了问题"，不打出来就是瞎猜。
                    if (infoCount <= 6 || infoCount % 100 == 0) {
                        Line dbg = parseLine(line);
                        Diag.log("Engine", "info#" + infoCount + " depth="
                                + (dbg == null ? "★解析失败" : String.valueOf(dbg.depth))
                                + " multipv=" + (dbg == null ? "-" : String.valueOf(dbg.multipv)));
                    }
                    if (!loggedFirst) {
                        loggedFirst = true;
                        Diag.log("Engine", "analyzeStream: 首条 info 到达。positionAndSync 用了 "
                                + (tSync - t0) + "ms，go 之后等了 "
                                + (System.currentTimeMillis() - tGo) + "ms");
                    }
                    Line l = parseLine(line);
                    if (l != null) {
                        Line prev = best.get(l.multipv);
                        if (prev == null || l.depth >= prev.depth) best.put(l.multipv, l);
                        // 每个新深度都必须上报：之前的 110ms 节流会永久跳过
                        // 搜索很快到达的最后一层（没有下一层来触发回调）。
                        if (l.depth > lastDepth) {
                            lastDepth = l.depth;
                            listener.onUpdate(sorted(best), l.depth);
                        }
                    }
                }
                if (line.startsWith("bestmove")) {
                    Diag.log("Engine", "analyzeStream: 收到 bestmove 退出，共 " + infoCount
                            + " 条 info，总用时 " + (System.currentTimeMillis() - t0) + "ms"
                            + (infoCount == 0 ? " bestmove=" + line.trim() : ""));
                    break;
                }
            }
            if (infoCount == 0) {
                Diag.log("Engine", "analyzeStream: 一趟下来 0 条 info（无产出！）总用时 "
                        + (System.currentTimeMillis() - t0) + "ms");
            }
            // search ended normally (e.g. a terminal position) without any PV is distinct
            // from a broken engine pipe: report completion rather than recycling the engine.
            listener.onUpdate(sorted(best), lastDepth < 0 ? 0 : lastDepth);
            if (pipeClosed) {
                // Prevent repeated IOException: Stream closed on every rescan. The next
                // request will start a fresh process instead of reusing closed streams.
                stop();
            }
        } catch (Throwable t) {
            Diag.log("Engine", "analyzeStream 抛异常: " + t);
            error = String.valueOf(t.getMessage());
            try { listener.onUpdate(new java.util.ArrayList<Line>(), 0); } catch (Throwable ignored) { }
            stop();
        }
    }

    /**
     * 限时＋限深的搜索（给「深度分析」逐步回算用）。
     * ★ 跟 `searchDepth` 的区别：这里**同时**限深和限时，而且**不用 `go depth` 的硬限深**——
     *   皮卡鱼到层就停，但我们要的是"边搜边看中途的结论"，所以用 `go infinite` + 自己看
     *   `info depth` 到了没，到了就 stop。
     * ★ 结果直接回调，不经过 UI 线程；调用方负责 post 回主线程。
     * 返回 false 表示引擎没起来/中途断了（调用方应当中止整趟扫描）。
     */
    public synchronized boolean searchDepthTimed(Board board, int maxDepth, long maxMs, DepthListener listener) {
        if (!started && !start()) return false;
        try {
            send("setoption name MultiPV value 1");
            // ★★★ 2026-09-29：非法局面不喂引擎（喂了会 exit(1)，调用方还会重试 → 死循环）。
            if (positionAndSync(board) != null) return false;
            // ★ 2026-09-23：三种组合都要照顾到（侧边栏可把限层/限时都设成 0 = 不启用）：
            //   · 限层>0            → go infinite，我们自己盯 depth 到点喊停
            //   · 限层=0 且 限时>0  → go movetime（引擎自己计时，最准）
            //   · 两个都是 0        → go infinite（彻底不限，靠调用方的兜底超时收手）
            if (maxDepth <= 0 && maxMs > 0) send("go movetime " + maxMs);
            else send("go infinite");
            long deadline = System.currentTimeMillis() + maxMs;
            int bestScore = 0, bestDepth = 0;
            lastTimedMove = -1;
            boolean sentStop = false;
            while (true) {
                // ★ 2026-09-23：限深模式（回算档用）照旧"到点就喊停"；
                //   限时模式引擎自己会停，这里只留一个远期兜底（+3s）防止它卡住不返回。
                long grace = (maxDepth > 0) ? 0 : 3000;
                if (!sentStop && System.currentTimeMillis() > deadline + grace) { send("stop"); sentStop = true; }
                String line = in.readLine();
                if (line == null) return false;
                if (line.startsWith("info ")) {
                    int d = depthOf(line);
                    int sc = scoreOf(line);
                    if (d > bestDepth && sc != 0) {
                        bestDepth = d;
                        bestScore = sc;
                        listener.onDepth(bestScore, bestDepth);
                    }
                    // ★ 2026-09-23：留一份关键字段，供搜索结束时打日志（排查"下到后面变慢"）。
                    if (line.contains(" depth ")) lastInfoBrief = briefOf(line);
                    // ★ 2026-09-23：maxDepth <= 0 表示**不限深**（纯限时）。
                    //   这时不要在这里 stop，交给上面那个 deadline 判定。
                    //   用户实测反馈：限深会让残局"8ms 就搜完 12 层"，棋子闪太快看不清；
                    //   改成限时后每步耗时稳定。
                    if (maxDepth > 0 && d >= maxDepth && !sentStop) { send("stop"); sentStop = true; }
                }
                if (line.startsWith("bestmove")) {
                    lastTimedMove = parseBestMove(line);
                    if (lastTimedMove > 0) listener.onDepth(bestScore, bestDepth);
                    // ★ 2026-09-23：每步一行日志。看趋势能分辨"是节点涨了（Hash 满）"
                    //   还是"nps 掉了（热降频）"。
                    Diag.log("Engine", "走子搜索完: " + lastInfoBrief + " | bestmove=" + line.trim());
                    return lastTimedMove > 0;
                }
            }
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            stop();
            return false;
        }
    }

    /** searchDepthTimed 跑完后，从 bestmove 解析出来的着法（失败 -1）。 */
    private int lastTimedMove = -1;

    /** 最近一条 info 行的关键字段摘要（depth/seldepth/nodes/nps/time/hashfull），排查"越下越慢"用。 */
    private String lastInfoBrief = "";

    /** 从 info 行里抠出性能相关字段，拼成一行短摘要。 */
    private static String briefOf(String line) {
        StringBuilder sb = new StringBuilder();
        String[] p = line.split(" ");
        for (int i = 0; i + 1 < p.length; i++) {
            String k = p[i];
            if ("depth".equals(k) || "seldepth".equals(k) || "nodes".equals(k)
                    || "nps".equals(k) || "time".equals(k) || "hashfull".equals(k)) {
                sb.append(k).append('=').append(p[i + 1]).append(' ');
            }
        }
        return sb.toString().trim();
    }

    public int getTimedMove() { return lastTimedMove; }

    private static int depthOf(String line) {
        try {
            String[] p = line.split(" ");
            for (int i = 0; i < p.length; i++) if ("depth".equals(p[i]) && i + 1 < p.length) return Integer.parseInt(p[i + 1]);
        } catch (Exception ignored) { }
        return 0;
    }

    private static int scoreOf(String line) {
        try {
            String[] p = line.split(" ");
            for (int i = 0; i < p.length; i++) {
                if ("cp".equals(p[i]) && i + 1 < p.length) return Integer.parseInt(p[i + 1]);
                if ("mate".equals(p[i]) && i + 1 < p.length) {
                    int m = Integer.parseInt(p[i + 1]);
                    return m > 0 ? 100000 : -100000;
                }
            }
        } catch (Exception ignored) { }
        return 0;
    }

    /** 逐步回算的回调：每搜到一个新层数就报一次分数（走子方视角 centipawn）。 */
    public interface DepthListener {
        void onDepth(int score, int depth);
    }

    /**
     * 打断当前分析（可与 analyzeStream 并发调用）。
     *
     * ★ 注意契约：这里只负责"发 stop"，**不读**引擎回吐的那条 `bestmove`。
     *   所以调用之后管道里会留一条孤儿 `bestmove`——如果下一次分析直接 `go`，
     *   就会立刻读到它并当成"搜索结束"，表现为"分析没出结果"（2026-09-22 实测）。
     *   回收责任在 `positionAndSync()`：每次 analyze/analyzeStream 开头都会
     *   用 `isready`/`readyok` 把管道同步干净。改这里时别把这个前提破坏掉。
     */
    public void stopAnalysis() {
        try {
            BufferedWriter w = out;
            if (w != null) { w.write("stop\n"); w.flush(); }
        } catch (Throwable ignored) { }
    }

    /** 设置 UCI 选项。 */
    public synchronized void setOption(String optName, String value) {
        if (!started) return;
        try { send("setoption name " + optName + " value " + value); } catch (Throwable ignored) { }
    }

    /** 分析结果的一路变化。 */
    public static class Line {
        public int multipv;
        public int depth;
        public int score;      // 走子方视角 centipawn
        public int mate;       // 非 0 表示有杀棋
        public String pv = ""; // UCI 着法串
    }

    /**
     * 把当前局面同步给引擎：先排干净上一轮残留的输出，再报局面。
     *
     * ★★★ 2026-09-29 修「识图后点分析永远等引擎」：
     *   识图会把棋盘换成一份**可能非法**的局面（实测：朝向判反 → 红仕/红帅跑到黑方底线）。
     *   皮卡鱼收到这种局面会 `info string CRITICAL ERROR ... Unsupported position` 然后
     *   **自己 exit(1)** —— 进程真死。应用侧只看到"管道断了"，`analyzeStream` 里
     *   `if (pipeClosed) stop()` 把引擎回收掉，主循环又 `ensureEngineReady()` 起一个新的，
     *   新的又收到同一份非法局面 → 又自杀 → **无限重启，界面永远停在「等待引擎…」**。
     *   对策：**喂之前先查合法性**，非法就一个字节都不发、进程也不动，把原因返回给调用方。
     *
     * ★★ 返回 `null` = 合法，已把 `position fen` 发出去；返回非 null = 非法原因，**什么都没发**。
     *   ⚠️ 故意用"返回值"而不是"抛异常"：抛出去会被 `analyzeStream`/`searchDepthTimed` 的
     *   `catch (Throwable)` 接到，那里调 `stop()` 把**好端端的引擎杀掉**，日志照样打「引擎中断」，
     *   等于白修。非法局面要"安静地不干活"。
     */
    private String positionAndSync(Board board) throws IOException {
        // ★ 先查局面合法性，非法就压根不碰管道（不 send、不 stop、不读）
        String illegal = Board.illegalReason(board.cells);
        if (illegal != null) {
            Diag.log("Engine", "positionAndSync: 结局面非法，已拦下不发 → " + illegal);
            return illegal;
        }
        // ★★ UCI 标准同步手法（2026-09-22 修"分析没出结果"）：
        //   上一轮搜索可能因为超时/换局面被提前打断，引擎还在往 stdout 里吐
        //   `info ...` 和收尾的 `bestmove`。这些残留行会留在管道里，下一轮一读就
        //   先读到旧的 `bestmove` → 立刻 break → 拿到 0 路结果 → 界面"分析没出结果"。
        //   实测症状：analyze 只花 12~15ms 就返回、收到 2 行（info + bestmove）。
        //   对策：发 `stop`（打断可能还在跑的搜索）+ `isready`，
        //   然后一直读到 `readyok` 为止 —— 此时管道里绝不会有上一轮的残留。
        send("stop");
        send("isready");
        long deadline = System.currentTimeMillis() + 3000;
        String line;
        int read = 0;
        boolean gotReadyok = false;
        while (System.currentTimeMillis() < deadline) {
            line = in.readLine();
            if (line == null) {
                // ★★★ 这里一旦 return，下面的 `position fen` 就不会发 —— 引擎将停在**上一个局面**上
                //   （或者干脆没有局面），后面那个 `go infinite` 自然一条 info 都不吐。
                //   以前这条路径完全静默，是"无产出"的头号嫌疑，所以必须留痕。
                //   ★ 进程真死（非法局面/被系统回收）时就走这条 —— 必须把 alive/exitValue 记下来，
                //     否则分不清"引擎自杀"和"管道被关闭"。
                Diag.log("Engine", "positionAndSync: 读管道得到 null（引擎进程已死）"
                        + " 已读 " + read + " 行，用时 " + (System.currentTimeMillis() - (deadline - 3000)) + "ms"
                        + " alive=" + procAlive() + " exited=" + exitValueSafe());
                throw new IOException("引擎输出已关闭，不能发送新局面");
            }
            read++;
            if (line.contains("readyok")) { gotReadyok = true; break; }
        }
        if (System.currentTimeMillis() >= deadline) {
            Diag.log("Engine", "positionAndSync: 等 readyok 超时 3 秒（读了 " + read + " 行）→ 仍继续发 position");
        }
        send("position fen " + fenOf(board));
        return null;
    }

    /**
     * 多路分析：返回 MultiPV 路最佳变化（按评分从高到低）。
     * 会在当前局面自由思考 movetimeMs 毫秒，不改变棋局状态。
     */
    public synchronized java.util.List<Line> analyze(Board board, long movetimeMs, int multiPv) {
        java.util.List<Line> result = new java.util.ArrayList<Line>();
        if (!started && !start()) return result;
        long t0 = System.currentTimeMillis();
        try {
            String fen = fenOf(board);
            android.util.Log.i("xiangqi-ocr", "Engine.analyze 起: " + fen + " movetime=" + movetimeMs);
            send("setoption name MultiPV value " + multiPv);
            // ★★★ 2026-09-29：非法局面不喂引擎（喂了会 exit(1)）。
            if (positionAndSync(board) != null) return result;
            send("go movetime " + movetimeMs);
            java.util.HashMap<Integer, Line> best = new java.util.HashMap<Integer, Line>();
            long deadline = System.currentTimeMillis() + movetimeMs + 30000;
            String line;
            int gotLines = 0;
            while (System.currentTimeMillis() < deadline) {
                line = in.readLine();
                if (line == null) {
                    // 实测（2026-09-22）：这里 procAlive 一直是 true、started 也是 true，
                    // 说明不是引擎死了，而是被上一轮 `stop` 打断后管道状态错乱。
                    // 交给下一轮的 positionAndSync 重置，不要在这里把 started 置 false
                    // （那会让下一帧重新 start() 一个引擎，白等 10 秒握手）。
                    android.util.Log.i("xiangqi-ocr", "Engine.analyze 读到 null: procAlive="
                            + (proc != null && proc.isAlive()) + " started=" + started
                            + " 收到行数=" + gotLines + " error=" + error);
                    break;
                }
                gotLines++;
                if (line.startsWith("info ") && line.contains(" pv ")) {
                    Line l = parseLine(line);
                    if (l != null) {
                        Line prev = best.get(l.multipv);
                        if (prev == null || l.depth >= prev.depth) best.put(l.multipv, l);
                    }
                }
                if (line.startsWith("bestmove")) break;
            }
            // 万一是 deadline 到点跳出来的（引擎还在搜），主动 stop 一下，
            // 免得它继续思考、之后吐出的 bestmove 变成下一轮的残留。
            if (System.currentTimeMillis() >= deadline) send("stop");
            result.addAll(best.values());
            java.util.Collections.sort(result, new java.util.Comparator<Line>() {
                public int compare(Line a, Line b) { return b.score - a.score; }
            });
            android.util.Log.i("xiangqi-ocr", "Engine.analyze 完: 用时 " + (System.currentTimeMillis() - t0)
                    + "ms 拿到 " + result.size() + " 路 共 " + gotLines + " 行");
            return result;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            stop();
            return result;
        }
    }

    private Line parseLine(String line) {
        try {
            Line l = new Line();
            l.multipv = 1;
            String[] p = line.split(" ");
            for (int i = 0; i < p.length; i++) {
                if ("depth".equals(p[i]) && i + 1 < p.length) l.depth = Integer.parseInt(p[i + 1]);
                if ("multipv".equals(p[i]) && i + 1 < p.length) l.multipv = Integer.parseInt(p[i + 1]);
                if ("cp".equals(p[i]) && i + 1 < p.length) l.score = Integer.parseInt(p[i + 1]);
                if ("mate".equals(p[i]) && i + 1 < p.length) {
                    l.mate = Integer.parseInt(p[i + 1]);
                    l.score = l.mate > 0 ? 100000 - l.depth : -100000 + l.depth;
                }
                if ("pv".equals(p[i])) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = i + 1; k < p.length; k++) { if (sb.length() > 0) sb.append(' '); sb.append(p[k]); }
                    l.pv = sb.toString();
                    break;
                }
            }
            return l.depth > 0 ? l : null;
        } catch (Exception e) { return null; }
    }

    /** 把单个 UCI 着法串（如 "h2e2"）转成内部着法编码；解析不了返回 -1。 */
    public static int moveOf(String mv) {
        if (mv == null || mv.length() < 4) return -1;
        int from = uciSquare(mv.substring(0, 2));
        int to = uciSquare(mv.substring(2, 4));
        if (from < 0 || to < 0) return -1;
        return Board.makeMove(from, to);
    }

    /** 把 UCI 着法串的第一手转成内部编码。 */
    public static int firstMoveOf(String pv) {
        if (pv == null || pv.length() < 4) return -1;
        return moveOf(pv.trim().split("\\s+")[0]);
    }

    private static int uciSquare(String s) {
        int col = s.charAt(0) - 'a';
        int rank = s.charAt(1) - '0';
        if (col < 0 || col > 8 || rank < 0 || rank > 9) return -1;
        return Board.idx(9 - rank, col);
    }

    /**
     * 限深度搜索：到达 depth 层立即返回，通常几十毫秒，体感"秒出招"。
     * 弱档用浅深度天然限强（皮卡鱼精简版没有 Skill Level 选项）。
     */
    public synchronized int searchDepth(Board board, int depth) {
        if (!started && !start()) return -1;
        try {
            send("setoption name MultiPV value 1");
            // ★★★ 2026-09-29：收口 —— 原来这里自己 `send("position fen ...")`，绕过闸门。
            //   现在统一走 positionAndSync（它内部也会先 `stop`+`isready` 清管道，更稳）。
            if (positionAndSync(board) != null) { error = "局面不合法"; return -1; }
            send("go depth " + depth);
            long deadline = System.currentTimeMillis() + 60000;
            String line;
            while (System.currentTimeMillis() < deadline) {
                line = in.readLine();
                if (line == null) { error = "引擎进程已退出"; stop(); return -1; }
                if (line.startsWith("info ")) parseInfo(line);
                if (line.startsWith("bestmove")) return parseBestMove(line);
            }
            error = "引擎思考超时";
            return -1;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            stop();
            return -1;
        }
    }

    /** 背景预热：提前启动引擎并加载权重，避免第一步卡顿。 */
    public void warmUp() {
        new Thread(new Runnable() { public void run() { try { start(); } catch (Throwable ignored) { } } }).start();
    }

    /**
     * 设置 UCI 选项——**在引擎没起来时也要生效**。
     * ★ 2026-09-22：合并走子/分析两台引擎后，Hash 要按模式切（走子 32MB、分析 64MB）。
     *   如果 setOption 因为 `!started` 直接 return，那么"启动之后再切模式"就永远
     *   改不回 Hash —— 所以这里改成：没起来就只记下（下次 start 完补发），起来了才真发。
     */
    private String pendingHash = null;

    /**
     * 搜索线程数。
     * ★ 2026-09-23：从 2 改成 4（实测有效加速 1.73×，代价是更耗电更烫）。
     *   8 线程反而退步（1.47×）—— 任务被摊到 4 个小核上，拖后腿。
     * ★ 2026-09-23 晚：改成**实例变量 + setThreads() 可配**（侧边栏「核心设置」要能改）。
     */
    // 悬浮窗与主界面共用进程资源；手机上并发 OCR 时用 2 线程，避免 native 子进程被系统回收。
    private int engineThreads = 2;

    /** 设置搜索线程数：已启动就立即下发，没启动就记着等 start() 用。 */
    public void setThreads(int n) {
        if (n < 1) n = 1;
        if (n > 16) n = 16;
        engineThreads = n;
        if (started) setOption("Threads", String.valueOf(n));
    }

    public void setHash(int mb) {
        String v = String.valueOf(mb);
        if (started) setOption("Hash", v);
        else pendingHash = v;
    }

    /** 让引擎对当前局面思考，返回内部着法编码（from<<8|to），失败返回 -1。 */
    public synchronized int search(Board board, long movetimeMs) {
        if (!started && !start()) return -1;
        try {
            // ★★★ 2026-09-29：收口 —— 同 searchDepth，也走 positionAndSync 过一遍闸门。
            if (positionAndSync(board) != null) { error = "局面不合法"; return -1; }
            send("go movetime " + movetimeMs);
            long deadline = System.currentTimeMillis() + movetimeMs + 20000;
            String line;
            while (System.currentTimeMillis() < deadline) {
                line = in.readLine();
                if (line == null) { error = "引擎进程已退出"; stop(); return -1; }
                if (line.startsWith("info ")) parseInfo(line);
                if (line.startsWith("bestmove")) return parseBestMove(line);
            }
            error = "引擎思考超时";
            return -1;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            stop();
            return -1;
        }
    }

    private void parseInfo(String line) {
        try {
            String[] parts = line.split(" ");
            for (int i = 0; i < parts.length; i++) {
                if ("depth".equals(parts[i]) && i + 1 < parts.length) depth = Integer.parseInt(parts[i + 1]);
                if ("cp".equals(parts[i]) && i + 1 < parts.length) score = Integer.parseInt(parts[i + 1]);
                if ("mate".equals(parts[i]) && i + 1 < parts.length) score = Integer.parseInt(parts[i + 1]) > 0 ? 30000 : -30000;
            }
        } catch (Exception ignored) { }
    }

    /** bestmove 形如 "h2e2"（a-i 为纵线，0-9 为横线，0 是红方底线）。 */
    private int parseBestMove(String line) {
        String mv = line.split(" ")[1];
        if (mv == null || mv.length() < 4 || "(none)".equals(mv)) return -1;
        int from = uciSquare(mv.substring(0, 2));
        int to = uciSquare(mv.substring(2, 4));
        if (from < 0 || to < 0) return -1;
        return Board.makeMove(from, to);
    }



    static String fenOf(Board b) {
        return b.toFen() + " - - 0 1";
    }

    public synchronized void stop() {
        started = false;
        try { if (out != null) { out.write("quit\n"); out.flush(); } } catch (Throwable ignored) { }
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) { }
        proc = null; out = null; in = null;
    }

    /**
     * ★★★ 2026-09-25：**紧急杀引擎，故意不加 synchronized**。
     *
     * 场景：某趟 `analyzeStream` 卡在 `in.readLine()` 上（引擎进程僵死 / 管道堵住），
     * 而 `analyzeStream` 是 synchronized —— 那把锁被它攥着，**后续所有分析都在排队等死**，
     * 界面表现就是"一直显示分析中、层数永远 d0"。
     * 这时候再去调 `stop()` 没用：它也要抢同一把锁，同样卡住。
     *
     * 所以这里直接拿进程引用 `destroy()`，**不等锁** ——
     * 进程一死，那个阻塞的 `readLine()` 立刻返回 null，卡住的线程自己就退出来了、锁也就放了。
     */
    public void killNow() {
        started = false;
        Process p = proc;
        proc = null; out = null; in = null;
        if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
    }
}

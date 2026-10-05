package com.dsh.xiangqi;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 轻量诊断日志：同时写 logcat 和 App 私有目录，界面里可直接查看（真机排查用）。 */
public class Diag {

    private static File file;
    private static final List<String> MEM = new ArrayList<String>();
    private static final int MEM_MAX = 300;
    /**
     * 落盘日志的大小上限。★ 2026-09-25（配合修 Bug4）：
     * 原来这个文件**只增不减**，悬浮窗每 500ms 扫一帧、每帧好几条日志，跑几小时能涨到几十 MB。
     * 而 log() 是 synchronized、每条还要 open/close 一次文件 —— 文件越大越慢，
     * 调用它的扫描线程全被拖住，表现出来同样是"跑久了识图不行了"。现在超过就重开一个。
     */
    private static final long FILE_MAX = 512 * 1024;

    public static void init(Context c) {
        try { file = new File(c.getFilesDir(), "diag.log"); } catch (Throwable ignored) { }
    }

    public static synchronized void log(String tag, String msg) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " [" + tag + "] " + msg;
        android.util.Log.i("xiangqi-ocr", line);
        MEM.add(line);
        while (MEM.size() > MEM_MAX) MEM.remove(0);
        if (file != null) {
            try {
                if (file.length() > FILE_MAX) file.delete();   // ★ 超上限就重开，别让它无限涨
                FileOutputStream os = new FileOutputStream(file, true);
                os.write((line + "\n").getBytes("UTF-8"));
                os.close();
            } catch (Throwable ignored) { }
        }
    }

    public static synchronized String tail(int n) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, MEM.size() - n);
        for (int i = from; i < MEM.size(); i++) sb.append(MEM.get(i)).append('\n');
        if (sb.length() == 0) {
            // 进程重启后内存日志为空，尝试读文件
            if (file != null && file.exists()) {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
                    List<String> all = new ArrayList<String>();
                    String l;
                    while ((l = r.readLine()) != null) all.add(l);
                    r.close();
                    for (int i = Math.max(0, all.size() - n); i < all.size(); i++) sb.append(all.get(i)).append('\n');
                } catch (Throwable ignored) { }
            }
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        MEM.clear();
        try { if (file != null) file.delete(); } catch (Throwable ignored) { }
    }
}

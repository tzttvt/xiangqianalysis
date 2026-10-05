package com.dsh.xiangqi;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/** 无障碍自检：告诉用户（和我）系统到底认没认到这个服务。 */
public class AccCheck {

    public static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("服务类已连接: ").append(AutoService.isRunning() ? "是" : "否").append('\n');
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            sb.append("无障碍总开关: ").append(am != null && am.isEnabled() ? "开" : "关").append('\n');
            if (am != null) {
                List<AccessibilityServiceInfo> on = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                List<AccessibilityServiceInfo> all = am.getInstalledAccessibilityServiceList();
                sb.append("系统已安装的服务数: ").append(all == null ? 0 : all.size()).append('\n');
                boolean found = false;
                if (on != null) {
                    for (AccessibilityServiceInfo i : on) {
                        String id = i.getId();
                        if (id != null && id.startsWith(c.getPackageName())) { found = true; break; }
                    }
                }
                sb.append("本应用的可见服务数: ").append(countMine(all, c.getPackageName())).append('\n');
                sb.append("系统认为本应用已启用: ").append(found ? "是" : "否").append('\n');
                if (all != null) {
                    for (AccessibilityServiceInfo i : all) {
                        String id = i.getId();
                        if (id != null && id.startsWith(c.getPackageName())) sb.append("  · ").append(id).append('\n');
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("自检异常: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static int countMine(List<AccessibilityServiceInfo> list, String pkg) {
        if (list == null) return 0;
        int n = 0;
        for (AccessibilityServiceInfo i : list) {
            String id = i.getId();
            if (id != null && id.startsWith(pkg)) n++;
        }
        return n;
    }
}

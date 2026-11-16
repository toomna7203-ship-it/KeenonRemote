package com.example.keenonremote;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class RobotAccessibilityService extends AccessibilityService {
    static volatile RobotAccessibilityService instance;
    static String industryPkg;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    /** หาแอพที่ชื่อมีคำว่า "Industry" จากไอคอนบนหน้าจอ */
    static Intent launchIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
        for (ResolveInfo r : l) {
            String label = String.valueOf(r.loadLabel(pm));
            if (label.toLowerCase().contains("industry")) {
                industryPkg = r.activityInfo.packageName;
                Intent i = pm.getLaunchIntentForPackage(industryPkg);
                if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return i;
            }
        }
        return null;
    }

    String go(Context ctx, String point, String floor) {
        if (point == null || point.length() == 0) return "no point";
        long end = System.currentTimeMillis() + 30000, lastLaunch = 0;
        boolean pointClicked = false;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inApp = root != null && industryPkg != null && industryPkg.contentEquals(root.getPackageName());
            if (!inApp) {
                if (System.currentTimeMillis() - lastLaunch > 4000) {
                    Intent i = launchIntent(this);
                    if (i == null) return "Industry app not found";
                    startActivity(i);
                    lastLaunch = System.currentTimeMillis();
                }
                sleep(500); continue;
            }
            if (!pointClicked) {
                AccessibilityNodeInfo p = find(root, point);
                if (p != null) {                       // อยู่หน้าเลือกจุดแล้ว
                    if (floor != null && floor.length() > 0) {
                        AccessibilityNodeInfo f = find(root, floor);
                        if (f != null) { click(f); sleep(500); root = getRootInActiveWindow(); p = find(root, point); }
                    }
                    if (p != null) { click(p); pointClicked = true; sleep(700); }
                    continue;
                }
                AccessibilityNodeInfo s = find(root, "ตรงไป");   // หน้าแรก -> เข้าโหมดตรงไป
                if (s != null) { click(s); sleep(1200); continue; }
            } else {
                AccessibilityNodeInfo b = find(root, "เริ่มทำงาน");
                if (b != null && b.isEnabled()) { click(b); return "sent to " + point; }
            }
            sleep(300);
        }
        return "timeout (ลอง /dump ดูว่าปุ่มอ่านได้ไหม)";
    }

    AccessibilityNodeInfo find(AccessibilityNodeInfo n, String t) {
        if (n == null) return null;
        CharSequence a = n.getText(), d = n.getContentDescription();
        if ((a != null && a.toString().trim().equals(t)) || (d != null && d.toString().trim().equals(t))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(n.getChild(i), t);
            if (r != null) return r;
        }
        return null;
    }
    boolean click(AccessibilityNodeInfo n) {
        while (n != null && !n.isClickable()) n = n.getParent();
        return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    String dump() {
        StringBuilder sb = new StringBuilder();
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return "no active window";
        sb.append("package: ").append(r.getPackageName()).append("\n");
        walk(r, 0, sb);
        return sb.toString();
    }
    void walk(AccessibilityNodeInfo n, int d, StringBuilder sb) {
        if (n == null) return;
        for (int i = 0; i < d; i++) sb.append("  ");
        sb.append(n.getClassName()).append(" | text=").append(n.getText()).append(" | desc=")
          .append(n.getContentDescription()).append(" | id=").append(n.getViewIdResourceName())
          .append(" | clickable=").append(n.isClickable()).append("\n");
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), d + 1, sb);
    }
}

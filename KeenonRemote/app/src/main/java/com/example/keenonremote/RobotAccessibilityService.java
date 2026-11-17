package com.example.keenonremote;
import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class RobotAccessibilityService extends AccessibilityService {
    static volatile RobotAccessibilityService instance;
    static volatile String appPkg;
    static volatile String mode = "";          // "diner" = T9 (KEENON DINERBOT), "industry" = แอพ Industry
    static final ReentrantLock LOCK = new ReentrantLock();

    @Override protected void onServiceConnected() { instance = this; launchIntent(this); }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    static int maxPoints() { return "diner".equals(mode) ? 4 : 1; }
    static final String[] OK_KEYS = { "OK", "ตกลง" };
    static String[] startKeys() {
        return "diner".equals(mode)
            ? new String[] { "Start", "เริ่ม", "เริ่มต้น", "การออกตัวแบบด่วน" }
            : new String[] { "เริ่มทำงาน" };
    }

    /** หาแอพจากชื่อไอคอน: DINERBOT (T9) ก่อน ถ้าไม่มีค่อยหา Industry */
    static Intent launchIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
        ResolveInfo diner = null, industry = null;
        for (ResolveInfo r : l) {
            String label = String.valueOf(r.loadLabel(pm)).toLowerCase();
            if (label.contains("dinerbot") && diner == null) diner = r;
            else if (label.contains("industry") && industry == null) industry = r;
        }
        ResolveInfo pick = diner != null ? diner : industry;
        if (pick == null) return null;
        mode = diner != null ? "diner" : "industry";
        appPkg = pick.activityInfo.packageName;
        Intent i = pm.getLaunchIntentForPackage(appPkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    boolean inApp(AccessibilityNodeInfo root) {
        return root != null && appPkg != null && appPkg.contentEquals(root.getPackageName());
    }

    /** คืนเวลาที่เปิดแอพล่าสุด หรือ -1 ถ้าหาแอพไม่เจอ */
    long relaunch(long lastLaunch) {
        if (System.currentTimeMillis() - lastLaunch < 4000) return lastLaunch;
        Intent i = launchIntent(this);
        if (i == null) return -1;
        startActivity(i);
        return System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- สั่งงาน
    String go(Context ctx, String pointsCsv, String floor) {
        if (pointsCsv == null || pointsCsv.trim().length() == 0) return "no point";
        if (!LOCK.tryLock()) return "busy: robot is running another command";
        try {
            if (appPkg == null && launchIntent(this) == null) return "robot app not found";
            String res = runOnce(pointsCsv, floor, 20000);
            if (res.startsWith("timeout")) {            // ค้าง: ออกจากแอพแล้วเข้าใหม่ แล้วลองอีกครั้ง
                restartApp();
                res = runOnce(pointsCsv, floor, 35000);
                if (res.startsWith("sent")) res += " (รีสตาร์ทแอพหุ่นอัตโนมัติ 1 ครั้ง)";
            }
            return res;
        } finally { LOCK.unlock(); }
    }

    String runOnce(String pointsCsv, String floor, long ms) {
        if ("diner".equals(mode)) return goDiner(pointsCsv, ms);
        return goIndustry(pointsCsv.split(",")[0].trim(), floor, ms);
    }

    /** ออกจากแอพหุ่น ปิดโปรเซส แล้วเปิดใหม่ เหมือนที่คนกดออกแล้วเข้าใหม่ */
    void restartApp() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        sleep(900);
        try {
            ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(appPkg);
        } catch (Exception ignored) { }
        sleep(900);
        Intent i = launchIntent(this);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        }
        sleep(3000);
    }

    String restart() {
        if (!LOCK.tryLock()) return "busy: robot is running another command";
        try {
            if (appPkg == null && launchIntent(this) == null) return "robot app not found";
            restartApp();
            return "app restarted";
        } finally { LOCK.unlock(); }
    }

    /** หน้าต่างของแอพหุ่น (หาจากทุกหน้าต่าง ไม่พึ่งแค่หน้าต่างที่ active) */
    AccessibilityNodeInfo appRoot() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null && appPkg != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && appPkg.contentEquals(r.getPackageName())) return r;
                }
            }
        } catch (Exception ignored) { }
        return getRootInActiveWindow();
    }

    /** T9: เลือกจุด 1-4 จุดตามลำดับ แล้วกด Start */
    String goDiner(String csv, long ms) {
        ArrayList<String> pts = new ArrayList<String>();
        for (String s : csv.split(",")) {
            s = s.trim();
            if (s.length() > 0 && pts.size() < 4 && !pts.contains(s)) pts.add(s);
        }
        long end = System.currentTimeMillis() + ms, lastLaunch = 0;
        int next = 0;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = appRoot();
            if (!inApp(root)) {
                lastLaunch = relaunch(lastLaunch);
                if (lastLaunch < 0) return "robot app not found";
                sleep(500); continue;
            }
            if (next < pts.size()) {
                AccessibilityNodeInfo n = find(root, pts.get(next));
                if (n != null && click(n)) { next++; sleep(600); continue; }
            } else {
                AccessibilityNodeInfo b = findAny(root, startKeys());
                if (b != null && b.isEnabled()) { click(b); return "sent to " + csv; }
            }
            sleep(300);
        }
        return "timeout (เปิด /dump ดูว่าหน้าจออยู่หน้าเลือกจุดไหม)";
    }

    /** Industry: ตรงไป -> จุด -> เริ่มทำงาน */
    String goIndustry(String point, String floor, long ms) {
        long end = System.currentTimeMillis() + ms, lastLaunch = 0;
        boolean pointClicked = false;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = appRoot();
            if (!inApp(root)) {
                lastLaunch = relaunch(lastLaunch);
                if (lastLaunch < 0) return "robot app not found";
                sleep(500); continue;
            }
            if (!pointClicked) {
                AccessibilityNodeInfo p = find(root, point);
                if (p != null) {
                    if (floor != null && floor.length() > 0) {
                        AccessibilityNodeInfo f = find(root, floor);
                        if (f != null) { click(f); sleep(500); root = appRoot(); p = find(root, point); }
                    }
                    if (p != null) { click(p); pointClicked = true; sleep(700); }
                    continue;
                }
                AccessibilityNodeInfo s = find(root, "ตรงไป");
                if (s != null) { click(s); sleep(1200); continue; }
            } else {
                AccessibilityNodeInfo b = find(root, "เริ่มทำงาน");
                if (b != null && b.isEnabled()) { click(b); return "sent to " + point; }
            }
            sleep(300);
        }
        return "timeout (ลอง /dump ดูว่าปุ่มอ่านได้ไหม)";
    }

    // ---------------------------------------------------------------- อ่านรายชื่อจุด
    String points(Context ctx) {
        if (!LOCK.tryLock()) return "{\"ok\":false,\"msg\":\"busy\"}";
        try {
            if (appPkg == null && launchIntent(this) == null) return "{\"ok\":false,\"msg\":\"robot app not found\"}";
            long end = System.currentTimeMillis() + 25000, lastLaunch = 0;
            while (System.currentTimeMillis() < end) {
                AccessibilityNodeInfo root = appRoot();
                if (!inApp(root)) {
                    lastLaunch = relaunch(lastLaunch);
                    if (lastLaunch < 0) return "{\"ok\":false,\"msg\":\"robot app not found\"}";
                    sleep(500); continue;
                }
                if (findAny(root, startKeys()) != null) {          // อยู่หน้าเลือกจุดแล้ว
                    ArrayList<String> out = new ArrayList<String>();
                    collect(root, out);
                    StringBuilder sb = new StringBuilder("{\"ok\":true,\"points\":[");
                    for (int k = 0; k < out.size(); k++) {
                        if (k > 0) sb.append(",");
                        sb.append("\"").append(out.get(k).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                    }
                    return sb.append("]}").toString();
                }
                if (!"diner".equals(mode)) {
                    AccessibilityNodeInfo s = find(root, "ตรงไป");
                    if (s != null) { click(s); sleep(1200); continue; }
                }
                sleep(300);
            }
            return "{\"ok\":false,\"msg\":\"timeout\"}";
        } finally { LOCK.unlock(); }
    }

    void collect(AccessibilityNodeInfo n, ArrayList<String> out) {
        if (n == null) return;
        CharSequence cs = n.getText();
        if (cs != null) {
            AccessibilityNodeInfo par = n.getParent();
            boolean btn = n.isClickable() || (par != null && par.isClickable());
            String t = cs.toString().replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
            if (btn && t.length() > 0 && t.length() <= 20 && !skip(t) && !out.contains(t)) out.add(t);
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
    }
    boolean skip(String t) {
        String n = norm(t);
        for (String k : startKeys()) if (norm(k).equals(n)) return true;
        for (String k : OK_KEYS) if (norm(k).equals(n)) return true;
        return n.equals("ตรงไป") || n.equals("beverage") || n.equals("return") || n.equals("กลับ")
            || n.equals("idle") || n.equals("operating") || n.equals("ว่าง")
            || n.equals("การใช้งานล่าสุด") || n.equals("ตำแหน่งทุกจุด")
            || n.matches("l\\d+|\\d+l") || ("diner".equals(mode) && n.matches("\\d+"))
            || n.startsWith("ชั้น") || n.contains("%") || n.matches("\\d+\\s*s") || n.matches(".*\\d{1,2}:\\d{2}.*");
    }

    // ---------------------------------------------------------------- สถานะ / กด OK (T9 ถึงจุดแล้ว)
    String status() {
        AccessibilityNodeInfo root = appRoot();
        if (!inApp(root)) return "{\"ok\":true,\"state\":\"other\",\"idle\":false,\"msg\":\"\"}";
        ArrayList<String> t = new ArrayList<String>();
        texts(root, t);
        boolean idle = false;                                   // แถบสถานะมุมขวาบน: Idle / ว่าง
        for (String x : t) { String n = norm(x); if (n.equals("idle") || n.equals("ว่าง")) idle = true; }
        if (findAny(root, OK_KEYS) != null) {                  // มีหน้าต่าง "Please take Lx" + ปุ่ม OK
            String no = "", take = "";
            for (int i = 0; i < t.size(); i++) {
                String x = t.get(i);
                if (no.length() == 0 && norm(x).matches("no\\.? ?\\d+")) no = x;
                if (take.length() == 0 && norm(x).contains("take")) {
                    take = x;
                    if (!norm(x).matches(".*l\\d+.*") && i + 1 < t.size() && norm(t.get(i + 1)).matches("l\\d+")) take = x + " " + t.get(i + 1);
                }
            }
            String msg = (no + " " + take).trim();
            return "{\"ok\":true,\"state\":\"arrived\",\"idle\":false,\"msg\":\"" + RobotService.esc(msg) + "\"}";
        }
        if (findAny(root, startKeys()) != null) return "{\"ok\":true,\"state\":\"ready\",\"idle\":" + idle + ",\"msg\":\"\"}";
        return "{\"ok\":true,\"state\":\"busy\",\"idle\":" + idle + ",\"msg\":\"\"}";
    }
    String pressOk() {
        AccessibilityNodeInfo root = appRoot();
        if (!inApp(root)) return "app not in front";
        AccessibilityNodeInfo b = findAny(root, OK_KEYS);
        return (b != null && click(b)) ? "ok pressed" : "no OK button";
    }
    void texts(AccessibilityNodeInfo n, ArrayList<String> out) {
        if (n == null) return;
        CharSequence cs = n.getText();
        if (cs != null) {
            String t = cs.toString().replace('\u00a0', ' ').trim();
            if (t.length() > 0) out.add(t);
        }
        for (int i = 0; i < n.getChildCount(); i++) texts(n.getChild(i), out);
    }

    // ---------------------------------------------------------------- เครื่องมือ
    static String norm(CharSequence c) {
        return c == null ? "" : c.toString().replace('\u00a0', ' ').trim().replaceAll("\\s+", " ").toLowerCase();
    }
    AccessibilityNodeInfo find(AccessibilityNodeInfo n, String t) { return findN(n, norm(t)); }
    AccessibilityNodeInfo findAny(AccessibilityNodeInfo n, String[] keys) {
        for (String k : keys) { AccessibilityNodeInfo r = find(n, k); if (r != null) return r; }
        return null;
    }
    AccessibilityNodeInfo findN(AccessibilityNodeInfo n, String key) {
        if (n == null) return null;
        if (norm(n.getText()).equals(key) || norm(n.getContentDescription()).equals(key)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findN(n.getChild(i), key);
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
        sb.append("mode: ").append(mode).append("  appPkg: ").append(appPkg).append("\n");
        boolean any = false;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                sb.append("=== window type=").append(w.getType()).append(" layer=").append(w.getLayer())
                  .append(" active=").append(w.isActive()).append(" pkg=").append(r == null ? "null" : r.getPackageName()).append("\n");
                if (r != null) { walk(r, 0, sb); any = true; }
            }
        } catch (Exception e) { sb.append("windows error: ").append(e).append("\n"); }
        if (!any) {
            AccessibilityNodeInfo r = appRoot();
            if (r == null) return sb.append("no window").toString();
            walk(r, 0, sb);
        }
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

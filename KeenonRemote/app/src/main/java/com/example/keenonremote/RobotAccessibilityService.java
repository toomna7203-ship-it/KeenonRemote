package com.example.keenonremote;
import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
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

    static final int MAX_MULTI_INDUSTRY = 10;   // S100: จำนวนจุดสูงสุดต่อกล่อง (ปรับได้)
    static int maxPoints() { return "diner".equals(mode) ? 4 : MAX_MULTI_INDUSTRY; }
    static final String[] OK_KEYS = { "OK", "ตกลง", "ยืนยันการรับ", "Confirm receipt" };
    static String[] startKeys() {
        return "diner".equals(mode)
            ? new String[] { "Start", "เริ่ม", "เริ่มต้น", "การออกตัวแบบด่วน", "~ออกตัว" }
            : new String[] { "เริ่มทำงาน", "start working" };
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
            String sp = specialState(allTexts());
            if (sp.length() > 0) return "busy: " + sp;
            return runOnce(pointsCsv, floor, 40000);
        } finally { LOCK.unlock(); }
    }

    static final String[] RETURN_KEYS = { "Return", "กลับ" };
    static final String[] CONFIRM_KEYS = { "Confirm", "ยืนยัน" };

    /** T9: กดปุ่ม Return (กลับ) มุมขวาบน เพื่อให้หุ่นกลับจุดพัก (ทำเมื่ออยู่หน้าเลือกจุดเท่านั้น) */
    String goReturn() {
        if (!LOCK.tryLock()) return "busy: robot is running another command";
        try {
            if (appPkg == null && launchIntent(this) == null) return "robot app not found";
            String sp = specialState(allTexts());
            if (sp.length() > 0) return "busy: " + sp;
            if (!"diner".equals(mode)) return returnIndustry();   // S100
            long end = System.currentTimeMillis() + 25000, lastLaunch = 0, lastWake = 0;
            int wakes = 0;
            while (System.currentTimeMillis() < end) {
                AccessibilityNodeInfo root = appRoot();
                if (!inApp(root)) {
                    lastLaunch = relaunch(lastLaunch);
                    if (lastLaunch < 0) return "robot app not found";
                    sleep(500); continue;
                }
                if (!hasText(root) && allTexts().isEmpty() && wakes < 3 && System.currentTimeMillis() - lastWake > 3000) {
                    wake(root); wakes++; lastWake = System.currentTimeMillis(); continue;
                }
                if (findAny(root, startKeys()) != null) {           // อยู่หน้าเลือกจุด ไม่ใช่หน้าต่าง OK
                    AccessibilityNodeInfo b = findAny(root, RETURN_KEYS);
                    if (b != null && click(b)) {
                        long end2 = System.currentTimeMillis() + 6000;       // หน้าต่าง "Return to the origin point?" -> กด Confirm/ยืนยัน ให้เอง
                        while (System.currentTimeMillis() < end2) {
                            sleep(400);
                            AccessibilityNodeInfo c = findAny(appRoot(), CONFIRM_KEYS);
                            if (c != null && click(c)) return "sent return (confirmed)";
                        }
                        return "sent return (ไม่พบหน้าต่างยืนยัน)";
                    }
                }
                sleep(300);
            }
            return "timeout (หุ่นไม่ได้อยู่หน้าเลือกจุด)";
        } finally { LOCK.unlock(); }
    }

    static final String[] INDUSTRY_RETURN_KEYS = { "back" };   // S100 ภาษาอังกฤษ: ปุ่มลูกศรมุมขวาบนเขียนว่า back (ภาษาไทยมีแต่ไอคอนลูกศร)

    /** S100: กดปุ่มลูกศรมุมขวาบน (back / ไอคอนลูกศร) ให้หุ่นกลับจุดพัก ทำได้จากหน้าเมนูหลัก / ตรงไป / หลายจุด */
    String returnIndustry() {
        long end = System.currentTimeMillis() + 25000, lastLaunch = 0, lastWake = 0;
        int wakes = 0;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = appRoot();
            if (!inApp(root)) {
                lastLaunch = relaunch(lastLaunch);
                if (lastLaunch < 0) return "robot app not found";
                sleep(500); continue;
            }
            if (!hasText(root) && allTexts().isEmpty() && wakes < 3 && System.currentTimeMillis() - lastWake > 3000) {
                wake(root); wakes++; lastWake = System.currentTimeMillis(); continue;
            }
            boolean hdr = find(root, "~" + UNSELECTED) != null;
            boolean twoBtn = find(root, "ยกเลิก") != null && find(root, "ตกลง") != null;
            if (hdr || twoBtn) {                                   // มีหน้าต่างเลือกจุด/ยืนยันค้างอยู่ -> ยกเลิกก่อน
                AccessibilityNodeInfo c = find(root, "ยกเลิก");
                if (c != null) click(c);
                sleep(700); continue;
            }
            boolean onMenu = find(root, "ตรงไป") != null || find(root, MULTI_TILE) != null
                || find(root, ADD_BOX) != null || findAny(root, startKeys()) != null;
            if (onMenu) {
                AccessibilityNodeInfo b = findAny(root, INDUSTRY_RETURN_KEYS);   // อังกฤษ: มีคำว่า back
                if (b == null) b = returnIconBtn(root);                           // ไทย: ไอคอนลูกศรไม่มีข้อความ
                if (b != null && click(b)) {
                    long end2 = System.currentTimeMillis() + 4000;                // ถ้ามีหน้าต่างถามยืนยันให้กดยืนยันเอง
                    while (System.currentTimeMillis() < end2) {
                        sleep(400);
                        AccessibilityNodeInfo r2 = appRoot();
                        if (r2 == null) continue;
                        if (find(r2, "ยกเลิก") != null) {
                            AccessibilityNodeInfo ok = find(r2, "ตกลง");
                            if (ok != null && click(ok)) return "sent return (confirmed)";
                        }
                        AccessibilityNodeInfo cf = findAny(r2, CONFIRM_KEYS);
                        if (cf != null && click(cf)) return "sent return (confirmed)";
                    }
                    return "sent return";
                }
            }
            sleep(300);
        }
        return "timeout (ไม่พบปุ่มลูกศรกลับจุดพัก มุมขวาบน เปิด /dump ดู)";
    }

    /** ไอคอนลูกศรมุมขวาบน (ไม่มีข้อความ): ปุ่มที่กดได้ไม่มีตัวอักษร อยู่ครึ่งขวา-ส่วนบนของจอ เลือกอันซ้ายสุด (ขวาของมันในหน้าเมนูหลักคือปุ่มตั้งค่า) */
    AccessibilityNodeInfo returnIconBtn(AccessibilityNodeInfo root) {
        Rect rr = bounds(root);
        int w = rr.width(), h = rr.height();
        if (w <= 0 || h <= 0) return null;
        ArrayList<AccessibilityNodeInfo> c = new ArrayList<AccessibilityNodeInfo>();
        collectBtns(root, null, c);
        AccessibilityNodeInfo best = null; int bx = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo x : c) {
            Rect r = bounds(x);
            if (r.centerX() < rr.left + w * 0.70 || r.centerY() > rr.top + h * 0.25) continue;
            if (r.width() > w * 0.3) continue;
            if (r.centerX() < bx) { bx = r.centerX(); best = x; }
        }
        return best;
    }

    String runOnce(String pointsCsv, String floor, long ms) {
        if ("diner".equals(mode)) return goDiner(pointsCsv, ms);
        ArrayList<String> pts = parsePoints(pointsCsv, maxPoints());
        if (pts.isEmpty()) return "no point";
        if (pts.size() > 1) return goIndustryMulti(pts, 60000);   // S100 หลายจุด
        return goIndustry(pts.get(0), floor, ms);                 // S100 จุดเดียว (ของเดิม)
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

    /** หน้าต่างที่กำลังใช้งานอยู่ (เหมือนเวอร์ชันแรกที่ใช้งานได้) ถ้าไม่ใช่แอพหุ่น แอพจะเปิดแอพหุ่นขึ้นมาเอง */
    AccessibilityNodeInfo appRoot() {
        return getRootInActiveWindow();
    }

    /** T9: เลือกจุด 1-4 จุดตามลำดับ แล้วกด Start */
    String goDiner(String csv, long ms) {
        ArrayList<String> pts = new ArrayList<String>();
        for (String s : csv.split(",")) {
            s = s.trim();
            if (s.length() > 0 && pts.size() < 4 && !pts.contains(s)) pts.add(s);
        }
        long end = System.currentTimeMillis() + ms, lastLaunch = 0, lastWake = 0;
        int next = 0, wakes = 0;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = appRoot();
            if (!inApp(root)) {
                lastLaunch = relaunch(lastLaunch);
                if (lastLaunch < 0) return "robot app not found";
                sleep(500); continue;
            }
            if (!hasText(root) && allTexts().isEmpty() && wakes < 3 && System.currentTimeMillis() - lastWake > 3000) {   // จอพัก/หน้าหุ่น
                wake(root); wakes++; lastWake = System.currentTimeMillis(); continue;
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
            if (!pointClicked && find(root, ADD_BOX) != null) { performGlobalAction(GLOBAL_ACTION_BACK); sleep(900); continue; }   // หุ่นค้างหน้าสั่งหลายจุด -> ย้อนกลับ
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

    // ---------------------------------------------------------------- S100: สั่งหลายจุด
    // ขั้นตอนในแอพ Industry: หลายจุด -> เพิ่มกล่องใหม่ -> แตะกล่อง -> กด + ที่จุด (ตามลำดับ) -> ตกลง -> เริ่มทำงาน
    static final String MULTI_TILE = "หลายจุด";
    static final String ADD_BOX = "เพิ่มกล่องใหม่";
    static final String PICK_HINT = "กรุณาเลือกจุดเป้าหมาย";
    static final String UNSELECTED = "ยังไม่ได้เลือกจุดเป้าหมาย";

    static ArrayList<String> parsePoints(String csv, int max) {
        ArrayList<String> pts = new ArrayList<String>();
        if (csv == null) return pts;
        for (String s : csv.split(",")) {
            s = s.trim();
            if (s.length() > 0 && pts.size() < max && !pts.contains(s)) pts.add(s);
        }
        return pts;
    }

    String goIndustryMulti(ArrayList<String> pts, long ms) {
        StringBuilder sc = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) { if (i > 0) sc.append(","); sc.append(pts.get(i)); }
        String csv = sc.toString();
        long end = System.currentTimeMillis() + ms, lastLaunch = 0;
        boolean cleaned = false;                        // เคลียร์กล่องเก่าในแอพแล้วหรือยัง
        int delTries = 0, mismatch = 0, addClicks = 0, backs = 0;
        int[] tries = new int[pts.size()], ft = new int[pts.size()];
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = appRoot();
            if (!inApp(root)) {
                lastLaunch = relaunch(lastLaunch);
                if (lastLaunch < 0) return "robot app not found";
                sleep(500); continue;
            }
            if ("estop".equals(specialState(allTexts()))) return "busy: estop";
            boolean hdr = find(root, "~" + UNSELECTED) != null;
            boolean twoBtn = find(root, "ยกเลิก") != null && find(root, "ตกลง") != null;

            if (!cleaned && (hdr || twoBtn)) {          // หน้าต่างค้างจากครั้งก่อน หรือถามยืนยันลบกล่อง
                AccessibilityNodeInfo c = hdr ? find(root, "ยกเลิก") : find(root, "ตกลง");
                if (c != null) click(c);
                sleep(700); continue;
            }
            if (cleaned && (hdr || twoBtn)) {           // หน้าต่าง "กรุณาเลือกจุดเป้าหมาย"
                String r = fillDialog(root, pts, tries, ft);
                if (r.startsWith("err:")) return r.substring(4);
                continue;
            }
            if (find(root, ADD_BOX) != null) {          // หน้ารายการกล่อง
                AccessibilityNodeInfo hint = find(root, PICK_HINT);
                ArrayList<AccessibilityNodeInfo> fb = new ArrayList<AccessibilityNodeInfo>();
                collectBoxes(root, fb);
                if (!cleaned) {                         // ลบกล่องเก่าที่ค้างอยู่ก่อน กันหุ่นไปจุดเก่าด้วย
                    AccessibilityNodeInfo anchor = hint != null ? hint : (fb.isEmpty() ? null : fb.get(0));
                    if (anchor == null) { cleaned = true; continue; }
                    if (++delTries > 8) return "ลบกล่องเดิมในแอพไม่ได้ (เปิด /dump ดูปุ่มลบ)";
                    AccessibilityNodeInfo d = rightmostBtn(anchor);
                    if (d == null) return "ไม่พบปุ่มลบกล่องเดิม (เปิด /dump ดู)";
                    click(d); sleep(800); continue;
                }
                if (hint != null) { click(hint); sleep(900); continue; }                 // กล่องว่าง -> เปิดหน้าเลือกจุด
                if (fb.isEmpty()) {                                                      // ยังไม่มีกล่อง -> เพิ่มกล่องใหม่
                    if (++addClicks > 4) return "เพิ่มกล่องแล้วไม่เจอช่องเลือกจุด (เปิด /dump ดู)";
                    click(find(root, ADD_BOX)); sleep(900); continue;
                }
                if (!boxMatches(root, pts)) {                                            // กล่องไม่ตรงที่ขอ -> ลบแล้วทำใหม่
                    if (++mismatch > 2) return "กล่องในแอพไม่ตรงกับจุดที่เลือก (เปิด /dump ดู)";
                    cleaned = false; delTries = 0; continue;
                }
                AccessibilityNodeInfo b = findAny(root, startKeys());
                if (b != null && b.isEnabled()) { click(b); return "sent to " + csv; }
                sleep(300); continue;
            }
            if (find(root, MULTI_TILE) != null && find(root, "ตรงไป") != null) {      // หน้าเมนูหลัก
                click(find(root, MULTI_TILE)); sleep(1200); continue;
            }
            if (find(root, "ตรงไป") != null && backs < 3 && findAny(root, startKeys()) != null) {   // อยู่หน้าตรงไป -> ย้อนกลับ
                backs++; performGlobalAction(GLOBAL_ACTION_BACK); sleep(1000); continue;
            }
            sleep(300);
        }
        return "timeout (เปิด /dump ดูว่าหน้าจออยู่ขั้นไหน)";
    }

    /** ในหน้าต่างเลือกจุด: กดเลือกจุดที่ยังไม่ได้เลือกทีละจุดตามลำดับ ครบแล้วกด ตกลง */
    String fillDialog(AccessibilityNodeInfo root, ArrayList<String> pts, int[] tries, int[] ft) {
        for (int i = 0; i < pts.size(); i++) {
            String p = pts.get(i);
            int st = ptState(root, p);
            if (st == 1) continue;
            if (st < 0) {                                        // ไม่เจอในชั้นนี้ -> ลองสลับชั้น
                if (switchFloor(root, ft, i)) return "";
                return "err:ไม่พบจุด " + p + " ในหน้าเลือกจุดของหุ่น (สแกนจุดใหม่ หรือเช็กชื่อจุด)";
            }
            if (++tries[i] > 4) return "err:กดเลือกจุด " + p + " ไม่สำเร็จ (เปิด /dump ดูปุ่ม +)";
            AccessibilityNodeInfo n = unselectedNode(root, p);
            boolean ok = false;
            if (n != null) {
                if (tries[i] % 2 == 1) {                         // ครั้งคี่: ลองกดปุ่ม + ก่อน ครั้งคู่: กดที่ตัวจุดเลย
                    AccessibilityNodeInfo plus = nearestBtn(n);
                    if (plus != null) ok = click(plus);
                }
                if (!ok) ok = click(n);
            }
            sleep(700);
            return "";
        }
        AccessibilityNodeInfo okb = find(root, "ตกลง");
        if (okb != null && click(okb)) { sleep(900); return "ok"; }
        return "";
    }

    /** 1 = เลือกแล้ว (อยู่เหนือหัวข้อ "ยังไม่ได้เลือก"), 0 = ยังไม่ได้เลือก, -1 = ไม่เจอจุดนี้บนจอ */
    int ptState(AccessibilityNodeInfo root, String p) {
        ArrayList<AccessibilityNodeInfo> l = new ArrayList<AccessibilityNodeInfo>();
        findAll(root, nk(p), l);
        if (l.isEmpty()) return -1;
        AccessibilityNodeInfo h = find(root, "~" + UNSELECTED);
        if (h == null) return 1;
        int ht = bounds(h).top;
        for (AccessibilityNodeInfo n : l) if (bounds(n).top > ht) return 0;
        return 1;
    }
    AccessibilityNodeInfo unselectedNode(AccessibilityNodeInfo root, String p) {
        ArrayList<AccessibilityNodeInfo> l = new ArrayList<AccessibilityNodeInfo>();
        findAll(root, nk(p), l);
        AccessibilityNodeInfo h = find(root, "~" + UNSELECTED);
        for (AccessibilityNodeInfo n : l) if (h == null || bounds(n).top > bounds(h).top) return n;
        return null;
    }
    boolean switchFloor(AccessibilityNodeInfo root, int[] ft, int i) {
        ArrayList<AccessibilityNodeInfo> fl = new ArrayList<AccessibilityNodeInfo>();
        collectMatch(root, "(ชั้น|floor)\\d+", fl);
        if (ft[i] >= fl.size()) return false;
        click(fl.get(ft[i]++)); sleep(700);
        return true;
    }
    void collectBoxes(AccessibilityNodeInfo n, ArrayList<AccessibilityNodeInfo> out) { collectMatch(n, "(กล่อง|box)\\d+", out); }
    void collectMatch(AccessibilityNodeInfo n, String regex, ArrayList<AccessibilityNodeInfo> out) {
        if (n == null) return;
        if (nk(n.getText()).matches(regex)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectMatch(n.getChild(i), regex, out);
    }
    /** กล่อง 1 แสดง "A、F" -> เทียบว่าตรงกับจุดที่ขอครบพอดี */
    boolean boxMatches(AccessibilityNodeInfo root, ArrayList<String> pts) {
        ArrayList<String> t = new ArrayList<String>();
        texts(root, t);
        for (String x : t) {
            ArrayList<String> seg = new ArrayList<String>();
            for (String s : x.split("[、,，;/|]+")) seg.add(nk(s));
            boolean all = true;
            for (String p : pts) if (!seg.contains(nk(p))) all = false;
            if (all && seg.size() == pts.size()) return true;
        }
        return false;
    }

    // ---- ตัวช่วยหาปุ่มไอคอน (+ / −) ที่ไม่มีข้อความ
    static int screenW(AccessibilityNodeInfo n) {
        while (n.getParent() != null) n = n.getParent();
        int w = bounds(n).width();
        return w > 0 ? w : 1000;
    }
    static Rect bounds(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r; }
    void findAll(AccessibilityNodeInfo n, String key, ArrayList<AccessibilityNodeInfo> out) {
        if (n == null) return;
        if (nk(n.getText()).equals(key) || nk(n.getContentDescription()).equals(key)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) findAll(n.getChild(i), key, out);
    }
    void collectBtns(AccessibilityNodeInfo n, AccessibilityNodeInfo skip, ArrayList<AccessibilityNodeInfo> out) {
        if (n == null) return;
        if (n.isClickable() && !hasText(n) && !n.equals(skip)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectBtns(n.getChild(i), skip, out);
    }
    /** ปุ่มไอคอนที่อยู่ใกล้ข้อความนี้ที่สุด (ปุ่ม + มุมขวาบนของจุด) */
    AccessibilityNodeInfo nearestBtn(AccessibilityNodeInfo anchor) {
        Rect a = bounds(anchor);
        long cap = (long) (screenW(anchor) * 0.18);            // ปุ่ม + ต้องอยู่ใกล้ตัวจุดเท่านั้น กันไปกดปุ่มอื่นของหน้า
        AccessibilityNodeInfo par = anchor.getParent();
        for (int up = 0; up < 3 && par != null; up++, par = par.getParent()) {
            ArrayList<AccessibilityNodeInfo> c = new ArrayList<AccessibilityNodeInfo>();
            collectBtns(par, anchor, c);
            AccessibilityNodeInfo best = null; long bd = Long.MAX_VALUE;
            for (AccessibilityNodeInfo x : c) {
                Rect r = bounds(x);
                long dx = r.centerX() - a.centerX(), dy = r.centerY() - a.centerY();
                long d = dx * dx + dy * dy;
                if (dx < -cap / 8 || d > cap * cap) continue;     // + อยู่มุมขวาบนของจุด
                if (d < bd) { bd = d; best = x; }
            }
            if (best != null) return best;
        }
        return null;
    }
    /** ปุ่มไอคอนทางขวาของแถวเดียวกับข้อความนี้ (ปุ่ม − ลบกล่อง) */
    AccessibilityNodeInfo rightmostBtn(AccessibilityNodeInfo anchor) {
        Rect a = bounds(anchor);
        long cap = (long) (screenW(anchor) * 0.12);            // ต้องอยู่แถวเดียวกับข้อความ
        AccessibilityNodeInfo par = anchor.getParent();
        for (int up = 0; up < 3 && par != null; up++, par = par.getParent()) {
            ArrayList<AccessibilityNodeInfo> c = new ArrayList<AccessibilityNodeInfo>();
            collectBtns(par, anchor, c);
            AccessibilityNodeInfo best = null; long bdy = Long.MAX_VALUE; int bx = -1;
            for (AccessibilityNodeInfo x : c) {
                Rect r = bounds(x);
                if (r.centerX() <= a.centerX()) continue;
                long dy = Math.abs(r.centerY() - a.centerY());
                if (dy > cap) continue;
                if (dy < bdy || (dy == bdy && r.centerX() > bx)) { bdy = dy; bx = r.centerX(); best = x; }
            }
            if (best != null) return best;
        }
        return null;
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
                if (!"diner".equals(mode) && find(root, ADD_BOX) != null) { performGlobalAction(GLOBAL_ACTION_BACK); sleep(900); continue; }   // ค้างหน้าสั่งหลายจุด
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
        return n.equals("ตรงไป") || n.equals("หลายจุด") || n.equals("วงจร")
            || n.equals("direct") || n.equals("multipoint") || n.equals("cycle")
            || n.equals("back") || n.equals("set up") || n.equals("setup") || n.equals("cancel") || n.equals("sure")
            || n.equals("beverage") || n.equals("return") || n.equals("กลับ")
            || n.equals("idle") || n.equals("operating") || n.equals("ว่าง")
            || n.equals("การใช้งานล่าสุด") || n.equals("ตำแหน่งทุกจุด")
            || n.matches("l\\d+|\\d+l") || ("diner".equals(mode) && n.matches("\\d+"))
            || n.startsWith("ชั้น") || n.startsWith("floor") || n.contains("%") || n.matches("\\d+\\s*s") || n.matches(".*\\d{1,2}:\\d{2}.*");
    }

    // ---------------------------------------------------------------- สถานะ / กด OK (T9 ถึงจุดแล้ว)
    /** หน้าจอพิเศษ: ปุ่มฉุกเฉินถูกกด / กำลังเข้าชาร์จ (จับคู่เสาชาร์จ) / กำลังชาร์จแบต */
    String specialState(ArrayList<String> all) {
        boolean estop = false, dock = false, chg = false;
        for (String x : all) {
            String n = norm(x);
            if (n.contains("emergency") || n.contains("ฉุกเฉิน") || n.contains("ถูกถ่ายภาพ") || n.contains("e-stop") || n.contains("motor unlocking")) estop = true;
            if (n.contains("กำลังจับคู่") || n.contains("มาถึงเสาชาร์จ") || n.contains("pairing")) dock = true;
            if (n.contains("charging") || n.contains("กำลังชาร์จ") || n.contains("หยุดชาร์จ")) chg = true;
        }
        return estop ? "estop" : dock ? "docking" : chg ? "charging" : "";
    }
    /** เปอร์เซ็นต์แบตจากข้อความ เช่น "74%" (-1 ถ้าไม่เห็น) */
    int battery(ArrayList<String> all) {
        for (String x : all) {
            String n = norm(x).replace(" ", "");
            if (n.matches("\\d{1,3}%")) {
                try { return Integer.parseInt(n.substring(0, n.length() - 1)); } catch (Exception ignored) { }
            }
        }
        return -1;
    }

    String status() {
        ArrayList<String> all = allTexts();                      // ข้อความจากทุกหน้าต่างบนจอ
        String tail = ",\"battery\":" + battery(all) + ",\"seen\":" + seenJson(all);
        String sp = specialState(all);
        if (sp.length() > 0) return "{\"ok\":true,\"state\":\"" + sp + "\",\"idle\":false" + tail + ",\"msg\":\"\"}";
        AccessibilityNodeInfo root = appRoot();
        if (!inApp(root)) return "{\"ok\":true,\"state\":\"other\",\"idle\":false" + tail + ",\"msg\":\"\"}";
        ArrayList<String> t = new ArrayList<String>();
        texts(root, t);
        boolean idleWord = false, busyWord = false;            // แถบสถานะ: Idle/ว่าง หรือ Operating/ปฏิบัติ...
        for (String x : all) {
            String n = norm(x);
            if (n.equals("idle") || n.equals("ว่าง")) idleWord = true;
            if (n.equals("operating") || n.equals("working") || n.contains("ปฏิบัติ") || n.equals("กำลังทำงาน") || n.equals("กำลังดำเนินการ")) busyWord = true;
        }
        if ("diner".equals(mode) && findAny(root, OK_KEYS) != null) {   // T9: มีหน้าต่าง "Please take Lx" + ปุ่ม OK
            String no = "", take = "";
            for (int i = 0; i < t.size(); i++) {
                String x = t.get(i);
                if (no.length() == 0 && norm(x).matches("no\\.? ?\\d+")) no = x;
                if (take.length() == 0 && (norm(x).contains("take") || norm(x).contains("โปรด"))) {
                    take = x;
                    if (!norm(x).matches(".*(l\\d+|\\d+l).*") && i + 1 < t.size() && norm(t.get(i + 1)).matches("l\\d+|\\d+l")) take = x + " " + t.get(i + 1);
                }
            }
            String msg = (no + " " + take).trim();
            return "{\"ok\":true,\"state\":\"arrived\",\"idle\":false" + tail + ",\"msg\":\"" + RobotService.esc(msg) + "\"}";
        }
        boolean homeScreen = !"diner".equals(mode) && find(root, "ตรงไป") != null;   // Industry: หน้าเมนูหลัก
        if (findAny(root, startKeys()) != null || homeScreen) {   // อยู่หน้าเมนูหลัก/เลือกจุด: ว่าง ถ้าไม่มีคำว่ากำลังทำงาน
            boolean free = idleWord || !busyWord;
            return "{\"ok\":true,\"state\":\"ready\",\"idle\":" + free + tail + ",\"msg\":\"\"}";
        }
        if (all.isEmpty()) return "{\"ok\":true,\"state\":\"sleep\",\"idle\":false" + tail + ",\"msg\":\"\"}";   // หน้าจอหน้าหุ่น/จอพัก
        return "{\"ok\":true,\"state\":\"busy\",\"idle\":false" + tail + ",\"msg\":\"\"}";
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
    static String nk(CharSequence c) { return norm(c).replace(" ", ""); }
    /** ข้อความภาษาอังกฤษของแอพ S100 (Industry) ที่ตรงกับข้อความภาษาไทยแต่ละคำ */
    static final java.util.HashMap<String, String[]> EN = new java.util.HashMap<String, String[]>();
    static {
        EN.put("ตรงไป", new String[] { "DIRECT" });
        EN.put("หลายจุด", new String[] { "MULTIPOINT" });
        EN.put("เพิ่มกล่องใหม่", new String[] { "add a new box" });
        EN.put("กรุณาเลือกจุดเป้าหมาย", new String[] { "please select a target point" });
        EN.put("ยังไม่ได้เลือกจุดเป้าหมาย", new String[] { "target point not selected" });
        EN.put("ยกเลิก", new String[] { "Cancel" });
        EN.put("ตกลง", new String[] { "Sure", "OK" });
        EN.put("เริ่มทำงาน", new String[] { "start working" });
    }
    /** หาโหนดจากข้อความ (ไทยหรืออังกฤษก็ได้ ขึ้นต้นด้วย ~ = ข้อความมีคำนี้อยู่) */
    AccessibilityNodeInfo find(AccessibilityNodeInfo n, String t) {
        boolean contains = t.startsWith("~");
        String base = contains ? t.substring(1) : t;
        AccessibilityNodeInfo r = contains ? findC(n, nk(base)) : findN(n, nk(base));
        if (r != null) return r;
        String[] alt = EN.get(base);
        if (alt != null) for (String a : alt) {
            r = contains ? findC(n, nk(a)) : findN(n, nk(a));
            if (r != null) return r;
        }
        return null;
    }
    AccessibilityNodeInfo findAny(AccessibilityNodeInfo n, String[] keys) {
        for (String k : keys) { AccessibilityNodeInfo r = find(n, k); if (r != null) return r; }
        return null;
    }
    AccessibilityNodeInfo findN(AccessibilityNodeInfo n, String key) {
        if (n == null) return null;
        if (nk(n.getText()).equals(key) || nk(n.getContentDescription()).equals(key)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findN(n.getChild(i), key);
            if (r != null) return r;
        }
        return null;
    }
    AccessibilityNodeInfo findC(AccessibilityNodeInfo n, String key) {
        if (n == null || key.length() == 0) return null;
        if (nk(n.getText()).contains(key) || nk(n.getContentDescription()).contains(key)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findC(n.getChild(i), key);
            if (r != null) return r;
        }
        return null;
    }
    boolean hasText(AccessibilityNodeInfo n) {
        if (n == null) return false;
        CharSequence cs = n.getText();
        if (cs != null && cs.toString().trim().length() > 0) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (hasText(n.getChild(i))) return true;
        return false;
    }
    AccessibilityNodeInfo firstClickable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isClickable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstClickable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
    /** ปลุกหน้าจอพัก/หน้าหุ่น: แตะจุดที่กดได้จุดแรก (ไม่กดย้อนกลับ เพื่อไม่ให้หลุดออกจากแอพ) */
    void wake(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo c = firstClickable(root);
        if (c != null) c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(1500);
    }
    /** ข้อความจากทุกหน้าต่างบนจอ (ต้องเปิด flagRetrieveInteractiveWindows) ไม่งั้นใช้เฉพาะหน้าต่างแอพ */
    ArrayList<String> allTexts() {
        ArrayList<String> out = new ArrayList<String>();
        boolean any = false;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) { texts(r, out); any = true; }
            }
        } catch (Exception ignored) { }
        if (!any) texts(appRoot(), out);
        return out;
    }
    String seenJson(ArrayList<String> all) {
        ArrayList<String> u = new ArrayList<String>();
        for (String x : all) {
            String v = x.length() > 24 ? x.substring(0, 24) : x;
            if (!u.contains(v)) u.add(v);
            if (u.size() >= 14) break;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < u.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(RobotService.esc(u.get(i))).append("\"");
        }
        return sb.append("]").toString();
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

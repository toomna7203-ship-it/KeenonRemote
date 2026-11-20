package com.example.keenonremote;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    static final int INK = Color.parseColor("#0E0F11");
    static final int PAPER = Color.parseColor("#F2F3F5");
    static final int GREEN = Color.parseColor("#12A150");
    static final int RED = Color.parseColor("#D64545");
    static final int MUTED = Color.parseColor("#6B7280");

    TextView addr, accState;
    View accDot;

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    GradientDrawable box(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    Button bigButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackground(box(primary ? INK : Color.WHITE, 14, primary ? 0 : Color.parseColor("#D5D9E0")));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(60));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startService(new Intent(this, RobotService.class));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(PAPER);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        sv.addView(root, new ScrollView.LayoutParams(-1, -2));

        // โลโก้ + ชื่อ
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setAdjustViewBounds(true);
        root.addView(logo, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView title = new TextView(this);
        title.setText("Keenon Remote");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, 0);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("LOGTHAI SOLUTECH");
        sub.setTextSize(13);
        sub.setLetterSpacing(0.2f);
        sub.setTextColor(MUTED);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(2), 0, dp(24));
        root.addView(sub);

        // การ์ดที่อยู่เว็บ
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(box(Color.WHITE, 18, Color.parseColor("#E1E4EA")));
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView l1 = new TextView(this);
        l1.setText("เปิดหน้าสั่งงานที่เบราว์เซอร์ (มือถือ / คอม ที่ต่อ WiFi เดียวกัน)");
        l1.setTextSize(14);
        l1.setTextColor(MUTED);
        card.addView(l1);

        addr = new TextView(this);
        addr.setTextSize(22);
        addr.setTypeface(Typeface.DEFAULT_BOLD);
        addr.setTextColor(INK);
        addr.setPadding(0, dp(6), 0, dp(14));
        addr.setTextIsSelectable(true);
        card.addView(addr);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        accDot = new View(this);
        row.addView(accDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        accState = new TextView(this);
        accState.setTextSize(16);
        accState.setPadding(dp(10), 0, 0, 0);
        row.addView(accState);
        card.addView(row);

        // ปุ่ม
        Button acc = bigButton("1  เปิดสิทธิ์ Accessibility", true);
        acc.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Toast.makeText(MainActivity.this, "เลือก Keenon Remote แล้วกดเปิด", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
        Button open = bigButton("2  เปิดแอพ Industry", false);
        open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Intent i = RobotAccessibilityService.launchIntent(MainActivity.this);
            if (i != null) startActivity(i); else Toast.makeText(MainActivity.this, "ไม่พบแอพ Industry", Toast.LENGTH_LONG).show(); }});
        root.addView(acc);
        root.addView(open);

        TextView foot = new TextView(this);
        foot.setText("ตั้งค่าครั้งเดียว แล้วปล่อยแอพนี้ทำงานอยู่เบื้องหลังได้เลย");
        foot.setTextSize(13);
        foot.setTextColor(MUTED);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(20), 0, 0);
        root.addView(foot);

        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        int ip = ((WifiManager) getSystemService(WIFI_SERVICE)).getConnectionInfo().getIpAddress();
        String s = (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
        addr.setText("http://" + s + ":" + RobotService.PORT);
        boolean on = RobotAccessibilityService.instance != null;
        accState.setText(on ? "Accessibility เปิดแล้ว พร้อมใช้งาน" : "Accessibility ยังไม่เปิด (กดปุ่ม 1)");
        accState.setTextColor(on ? GREEN : RED);
        accDot.setBackground(box(on ? GREEN : RED, 6, 0));
    }
}

package com.example.keenonremote;
import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    TextView info;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startService(new Intent(this, RobotService.class));
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 40, 40, 40);
        info = new TextView(this); info.setTextSize(20);
        Button acc = new Button(this); acc.setText("1) เปิด Accessibility (เปิด Keenon Remote)");
        acc.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
        Button open = new Button(this); open.setText("2) เปิดแอพ Industry");
        open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Intent i = RobotAccessibilityService.launchIntent(MainActivity.this);
            if (i != null) startActivity(i); else Toast.makeText(MainActivity.this, "ไม่พบแอพ Industry", Toast.LENGTH_LONG).show(); }});
        l.addView(info); l.addView(acc); l.addView(open);
        setContentView(l);
    }
    @Override protected void onResume() {
        super.onResume();
        int ip = ((WifiManager) getSystemService(WIFI_SERVICE)).getConnectionInfo().getIpAddress();
        String s = (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
        info.setText("เปิดในคอมที่เบราว์เซอร์:\nhttp://" + s + ":8080\n\nAccessibility: " +
            (RobotAccessibilityService.instance != null ? "เปิดแล้ว ✔" : "ยังไม่เปิด ✘"));
    }
}

package com.example.keenonremote;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import java.io.*;
import java.net.*;

public class RobotService extends Service {
    static final int PORT = 9999;   // ถ้าเคยเปลี่ยนพอร์ต ให้แก้เลขตรงนี้ให้เหมือนเดิม
    volatile boolean running = false;
    ServerSocket ss;

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int f, int id) {
        if (!running) {
            running = true;
            new Thread(new Runnable() { public void run() { loop(); }}).start();
        }
        return START_STICKY;
    }
    void loop() {
        try {
            ss = new ServerSocket(PORT);
            while (running) {
                final Socket s = ss.accept();
                new Thread(new Runnable() { public void run() { handle(s); }}).start();
            }
        } catch (Exception e) { running = false; }
    }
    void handle(Socket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            String h; while ((h = r.readLine()) != null && h.length() > 0) { }
            if (line == null) { s.close(); return; }
            String path = line.split(" ")[1];
            Uri u = Uri.parse("http://x" + path);
            String type = "application/json", body;
            RobotAccessibilityService acc = RobotAccessibilityService.instance;
            String p = u.getPath() == null ? "/" : u.getPath();
            if (p.equals("/go")) {
                String point = u.getQueryParameter("point");
                String floor = u.getQueryParameter("floor");
                body = acc == null ? "{\"ok\":false,\"msg\":\"Accessibility not enabled\"}"
                    : "{\"ok\":true,\"msg\":\"" + acc.go(this, point, floor) + "\"}";
            } else if (p.equals("/points")) {
                body = acc == null ? "{\"ok\":false,\"msg\":\"Accessibility not enabled\"}" : acc.points(this);
            } else if (p.equals("/ping")) {
                body = "{\"ok\":true,\"acc\":" + (acc != null) + "}";
            } else if (p.equals("/dump")) {
                type = "text/plain";
                body = acc == null ? "Accessibility not enabled" : acc.dump();
            } else {
                type = "text/html";
                try {
                    InputStream in = getAssets().open("hub.html");
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    body = bo.toString("UTF-8");
                } catch (Exception e) { body = PAGE; }
            }
            byte[] b = body.getBytes("UTF-8");
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length +
                "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes());
            o.write(b); o.flush(); s.close();
        } catch (Exception e) { try { s.close(); } catch (Exception ignored) { } }
    }

    static final String PAGE = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
        + "<title>Keenon Remote</title><body style='font-family:sans-serif;margin:30px'>"
        + "<h2>Keenon Remote พร้อมใช้งาน</h2>ใช้หน้า hub.html ในคอมเพื่อสั่งงาน"
        + "<p><a href=/dump>dump หน้าจอ</a> | <a href=/points>อ่านรายชื่อจุด</a></p>";
}

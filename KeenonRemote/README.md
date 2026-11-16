# Keenon Remote
1. สร้าง repo (public) บน GitHub แล้ว push โฟลเดอร์นี้ขึ้น branch main
2. รอ Actions build เสร็จ -> ลิงก์ติดตั้ง:
   https://github.com/<user>/<repo>/releases/download/latest/app-debug.apk
3. เปิดลิงก์บนแท็บเล็ต -> อนุญาต "แหล่งที่ไม่รู้จัก" -> ติดตั้ง
4. เปิดแอพ Keenon Remote -> กดปุ่ม 1) เปิด Accessibility -> เปิด Keenon Remote (ทำครั้งเดียว)
5. คอมเปิดเบราว์เซอร์ http://<IP แท็บเล็ต>:8080 แล้วกด A/B/C/D
   หรือ curl "http://<IP>:8080/go?point=A&floor=ชั้น 1"

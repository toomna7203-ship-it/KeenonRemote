package com.example.keenonremote;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { c.startService(new Intent(c, RobotService.class)); }
}

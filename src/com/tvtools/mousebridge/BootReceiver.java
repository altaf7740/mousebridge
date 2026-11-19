package com.tvtools.mousebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts Mouse Bridge when the TV boots (CursorService then launches the shell-side Daemon through
 * the TV's own adb). Also handles the ENABLE / DISABLE commands sent by start.sh and stop.sh.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_ENABLE = "com.tvtools.mousebridge.ENABLE";
    static final String ACTION_DISABLE = "com.tvtools.mousebridge.DISABLE";

    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            CursorService.setEnabled(c, false);
            c.stopService(new Intent(c, CursorService.class));
            Log.i(CursorService.TAG, "auto-start disabled");
            return;
        }
        if (ACTION_ENABLE.equals(action)) CursorService.setEnabled(c, true);
        if (!CursorService.isEnabled(c)) return;
        Intent s = new Intent(c, CursorService.class);
        if (ACTION_ENABLE.equals(action)) s.setAction(CursorService.ACTION_CHECK_ADB);
        c.startForegroundService(s);
        Log.i(CursorService.TAG, "started from " + action);
    }
}

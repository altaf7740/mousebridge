package com.tvtools.mousebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * App-side half of Mouse Bridge: draws the cursor. Android shows no pointer for injected mouse
 * events, so the daemon streams the cursor position here over a loopback socket.
 *
 * The overlay is not touchable and sits a couple of pixels below-right of the click point, so it
 * never covers the spot being clicked (Android blocks input under untrusted overlays).
 */
public class CursorService extends Service {
    static final String TAG = "MouseBridge";
    static final String ACTION_CHECK_ADB = "com.tvtools.mousebridge.CHECK_ADB";
    private static final int OFFSET = 2;      // overlay origin relative to the click point
    private static final int SIZE = 40;       // overlay size in px
    private static final long HIDE_AFTER_MS = 8000;
    private static final long LAUNCH_AFTER_MS = 6000;   // daemon missing this long -> launch it
    private static final long RELAUNCH_EVERY_MS = 30000;
    // Android forgets an "Always allow" adb key that goes unused for 7 days; using it once a day
    // keeps it valid while the TV stays on.
    private static final long KEEPALIVE_EVERY_MS = 24L * 60 * 60 * 1000;
    private volatile long lastKeepAlive = SystemClock.uptimeMillis();

    /**
     * Same command start.sh uses: stop an old daemon, then start one detached from the adb session,
     * from the installed APK, with the options start.sh saved in /data/local/tmp/mousebridge.args.
     */
    static final String DAEMON_START =
            "p=$(pidof mousebridge); [ -n \"$p\" ] && kill $p; sleep 0.3; "
            + "L=/data/local/tmp/mousebridge.log; [ $(wc -c < $L 2>/dev/null || echo 0) -gt 200000 ] && tail -c 50000 $L > $L.tmp && mv $L.tmp $L; "
            + "echo \"--- start $(date) (boot)\" >> $L; "
            + "A=$(cat /data/local/tmp/mousebridge.args 2>/dev/null); "
            + "APK=$(pm path com.tvtools.mousebridge | head -1 | cut -d: -f2); "
            + "setsid nohup app_process -Djava.class.path=$APK /system/bin --nice-name=mousebridge "
            + "com.tvtools.mousebridge.Daemon $A >> /data/local/tmp/mousebridge.log 2>&1 < /dev/null & "
            + "echo launched";

    static boolean isEnabled(android.content.Context c) {
        return c.getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("enabled", true);
    }

    static void setEnabled(android.content.Context c, boolean on) {
        c.getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
    }

    private volatile long disconnectedSince = SystemClock.uptimeMillis();
    private volatile long lastLaunch;
    private volatile boolean adbBusy;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View cursor;
    private WindowManager.LayoutParams lp;
    private volatile boolean running = true;
    private volatile Socket socket;
    private volatile int px, py;
    private volatile boolean connected;
    private volatile long lastMove;
    private boolean pending;

    private final Runnable apply = new Runnable() {
        @Override public void run() {
            pending = false;
            boolean show = connected && SystemClock.uptimeMillis() - lastMove < HIDE_AFTER_MS;
            cursor.setVisibility(show ? View.VISIBLE : View.GONE);
            lp.x = px + OFFSET;
            lp.y = py + OFFSET;
            try { wm.updateViewLayout(cursor, lp); } catch (Throwable ignored) { }
        }
    };

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            schedule();
            long now = SystemClock.uptimeMillis();
            if (!connected && now - disconnectedSince > LAUNCH_AFTER_MS && now - lastLaunch > RELAUNCH_EVERY_MS) {
                lastLaunch = now;
                runAdb(DAEMON_START, "launch daemon");
            } else if (now - lastKeepAlive > KEEPALIVE_EVERY_MS && !adbBusy) {
                lastKeepAlive = now;
                runAdb("echo keep-alive", "keep-alive");
            }
            main.postDelayed(this, 1000);
        }
    };

    /** Runs a shell command through the TV's own adb (127.0.0.1:5555) on a background thread. */
    private void runAdb(String command, String what) {
        if (adbBusy) return;
        adbBusy = true;
        new Thread(() -> {
            try {
                String out = new AdbClient(this).shell("127.0.0.1", 5555, command, 90000);
                android.util.Log.i(TAG, "self-adb ok (" + what + "): " + out.trim());
            } catch (Throwable t) {
                android.util.Log.w(TAG, "self-adb failed (" + what + "): " + t);
            } finally {
                adbBusy = false;
            }
        }, "self-adb").start();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("cursor", "Mouse cursor", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "cursor")
                .setContentTitle("Mouse Bridge")
                .setContentText("Drawing the USB mouse cursor")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        wm = getSystemService(WindowManager.class);
        cursor = new CursorView(this);
        lp = new WindowManager.LayoutParams(SIZE, SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("MouseBridgeCursor");
        lp.alpha = 0.8f; // max opacity Android allows for a pass-through overlay; avoids a log warning per update
        cursor.setVisibility(View.GONE);
        wm.addView(cursor, lp);
        main.post(idleCheck);

        Thread t = new Thread(this::receiveLoop, "cursor-client");
        t.setDaemon(true);
        t.start();
    }

    private void receiveLoop() {
        while (running) {
            try (Socket s = new Socket()) {
                socket = s;
                s.connect(new InetSocketAddress("127.0.0.1", Daemon.PORT), 2000);
                s.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(s.getInputStream());
                connected = true;
                lastMove = SystemClock.uptimeMillis();
                while (running) {
                    px = in.readInt();
                    py = in.readInt();
                    lastMove = SystemClock.uptimeMillis();
                    schedule();
                }
            } catch (Throwable e) {
                if (connected) disconnectedSince = SystemClock.uptimeMillis();
                connected = false;
                schedule();
                SystemClock.sleep(1000);
            }
        }
    }

    private void schedule() {
        main.post(() -> {
            if (!pending) { pending = true; main.post(apply); }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_CHECK_ADB.equals(intent.getAction())) {
            // Sent by start.sh: makes sure this app's adb key is authorised now (the TV shows the
            // one-time "Allow network debugging?" prompt), so the next boot can start silently.
            runAdb("echo adb-authorised", "check");
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        try { Socket s = socket; if (s != null) s.close(); } catch (Throwable ignored) { }
        main.removeCallbacksAndMessages(null);
        try { wm.removeView(cursor); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** A standard arrow pointer with its tip at (-OFFSET, -OFFSET) relative to the view. */
    static final class CursorView extends View {
        private final Path path = new Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorView(Context c) {
            super(c);
            float s = 1.5f; // arrow drawn on a 13x21 grid, scaled
            float o = 0.5f; // keep the outline inside the view; the tip is OFFSET px from the click point
            path.moveTo(o, o);
            path.lineTo(o, o + 17 * s);
            path.lineTo(o + 4 * s, o + 13 * s);
            path.lineTo(o + 7 * s, o + 20 * s);
            path.lineTo(o + 10 * s, o + 19 * s);
            path.lineTo(o + 7 * s, o + 12 * s);
            path.lineTo(o + 12 * s, o + 12 * s);
            path.close();
            fill.setColor(0xFFFFFFFF);
            stroke.setColor(0xFF000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.6f);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
        }
    }
}

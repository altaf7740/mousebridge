package com.tvtools.mousebridge;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Shell-side half of Mouse Bridge. Run with app_process as the adb shell user (see start.sh).
 *
 * This TV's firmware drops the button presses of physical USB mice (the input dispatcher logs
 * "Conflicting pointer actions" and delivers only the release), while injected mouse input works.
 * So this daemon takes the mouse's evdev node exclusively (EVIOCGRAB), keeps track of the cursor
 * position itself and injects complete mouse events through InputManager, the same way scrcpy does.
 * Android draws no cursor for injected events, so the position is streamed to CursorService, which
 * draws one.
 */
public final class Daemon {
    static final int PORT = 47110;
    private static final int EVIOCGRAB = 0x40044590; // _IOW('E', 0x90, int)
    private static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2;
    private static final int REL_X = 0, REL_Y = 1, REL_HWHEEL = 6, REL_WHEEL = 8;
    private static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111, BTN_MIDDLE = 0x112, BTN_SIDE = 0x113, BTN_EXTRA = 0x114;

    private static Method injectMethod;
    private static Object injectTarget;

    private static int width = 1920, height = 1080;
    private static float sensitivity = 1.0f;
    private static float x, y;
    private static final int BACK_BITS = MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_BACK;
    private static final int KEY_BITS = BACK_BITS | MotionEvent.BUTTON_FORWARD;
    private static final int deviceId = 0;
    private static int buttons;      // BUTTON_PRIMARY while the left button is held
    private static int keyButtons;   // right / side buttons currently held (mapped to keys)
    private static long downTime;
    private static volatile DataOutputStream client;

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if (a.startsWith("--sensitivity=")) sensitivity = Float.parseFloat(a.substring(14));
        }
        initInjection();
        readDisplaySize();
        x = width / 2f;
        y = height / 2f;
        log("display " + width + "x" + height + ", sensitivity " + sensitivity);
        startCursorServer();
        exec("am start-foreground-service -n com.tvtools.mousebridge/.CursorService");

        while (true) {
            String dev = findMouse();
            if (dev == null) { SystemClock.sleep(2000); continue; }
            runDevice(dev);
            SystemClock.sleep(1000);
        }
    }

    // ---------- device ----------

    /**
     * The external mouse's evdev node: a USB (bus 0003) or Bluetooth (0005) device reporting
     * REL_X, REL_Y and a left button. Built-in devices are skipped: the TV remote ("IR_sony", bus
     * 0000) also reports mouse axes for its pointer mode, and grabbing it would break the remote.
     * A receiver can expose several such nodes (keyboard interface and mouse interface); the one
     * without letter keys is the mouse, so it wins. Device numbers change between boots.
     */
    private static String findMouse() {
        String out = exec("getevent -il");
        if (out == null) return null;
        String best = null, bestName = "";
        int bestScore = -1;
        String path = null, name = "", bus = "";
        boolean relX = false, relY = false, btn = false, letters = false;
        for (String line : (out + "\nadd device 0: END").split("\n")) {
            line = line.trim();
            if (line.startsWith("add device")) {
                boolean external = bus.equals("0003") || bus.equals("0005");
                if (path != null && external && relX && relY && btn) {
                    int score = letters ? 1 : 2;
                    if (score > bestScore) { best = path; bestName = name; bestScore = score; }
                }
                int i = line.indexOf("/dev/input/");
                path = i >= 0 ? line.substring(i) : null;
                name = bus = "";
                relX = relY = btn = letters = false;
            } else if (line.startsWith("bus:")) {
                bus = line.substring(4).trim();
            } else if (line.startsWith("name:")) {
                name = line.substring(5).trim();
            } else {
                for (String tok : line.split("\\s+")) {
                    if (tok.equals("REL_X")) relX = true;
                    else if (tok.equals("REL_Y")) relY = true;
                    else if (tok.equals("BTN_MOUSE") || tok.equals("BTN_LEFT")) btn = true;
                    else if (tok.equals("KEY_A")) letters = true;
                }
            }
        }
        if (best != null && !best.equals(lastFound)) log("mouse found: " + best + " " + bestName);
        lastFound = best;
        return best;
    }

    private static String lastFound;

    private static void runDevice(String dev) {
        FileDescriptor fd = null;
        try {
            fd = Os.open(dev, OsConstants.O_RDONLY, 0);
            Method ioctlInt = Os.class.getMethod("ioctlInt", FileDescriptor.class, int.class);
            ioctlInt.invoke(null, fd, EVIOCGRAB); // passes a non-null int*, which evdev treats as "grab"
            log("grabbed " + dev);
            int size = android.os.Process.is64Bit() ? 24 : 16;
            byte[] buf = new byte[size * 64];
            int dx = 0, dy = 0, wheel = 0, hwheel = 0, newButtons = buttons | keyButtons;
            while (true) {
                int n = Os.read(fd, buf, 0, buf.length);
                if (n <= 0) break;
                for (int off = 0; off + size <= n; off += size) {
                    int b = off + size - 8;
                    int type = (buf[b] & 0xff) | (buf[b + 1] & 0xff) << 8;
                    int code = (buf[b + 2] & 0xff) | (buf[b + 3] & 0xff) << 8;
                    int value = (buf[b + 4] & 0xff) | (buf[b + 5] & 0xff) << 8 | (buf[b + 6] & 0xff) << 16 | buf[b + 7] << 24;
                    if (type == EV_REL) {
                        if (code == REL_X) dx += value;
                        else if (code == REL_Y) dy += value;
                        else if (code == REL_WHEEL) wheel += value;
                        else if (code == REL_HWHEEL) hwheel += value;
                    } else if (type == EV_KEY) {
                        int bit = buttonBit(code);
                        if (bit != 0) {
                            newButtons = value != 0 ? newButtons | bit : newButtons & ~bit;
                        } else if (value != 2) {
                            forwardKey(code, value != 0);
                        }
                    } else if (type == EV_SYN && code == 0) {
                        frame(dx, dy, wheel, hwheel, newButtons);
                        dx = dy = wheel = hwheel = 0;
                    }
                }
            }
        } catch (Throwable t) {
            log("device " + dev + " ended: " + t);
        } finally {
            if (buttons != 0 || keyButtons != 0) frame(0, 0, 0, 0, 0); // never leave a button stuck down
            if (fd != null) try { Os.close(fd); } catch (Throwable ignored) { }
        }
    }

    private static int buttonBit(int code) {
        switch (code) {
            case BTN_LEFT: return MotionEvent.BUTTON_PRIMARY;
            case BTN_RIGHT: return MotionEvent.BUTTON_SECONDARY;
            case BTN_MIDDLE: return MotionEvent.BUTTON_TERTIARY;
            case BTN_SIDE: return MotionEvent.BUTTON_BACK;
            case BTN_EXTRA: return MotionEvent.BUTTON_FORWARD;
            default: return 0;
        }
    }

    // ---------- one SYN_REPORT worth of input ----------

    private static void frame(int dx, int dy, int wheel, int hwheel, int newButtons) {
        long now = SystemClock.uptimeMillis();
        boolean moved = false;
        if (dx != 0 || dy != 0) {
            float speed = (float) Math.hypot(dx, dy);
            float gain = sensitivity * Math.min(2.2f, 1f + Math.max(0f, speed - 2f) * 0.08f);
            x = Math.max(0, Math.min(width - 1, x + dx * gain));
            y = Math.max(0, Math.min(height - 1, y + dy * gain));
            moved = true;
        }
        // Right and side buttons become keys: right = Back (the Android TV convention), side = Back,
        // extra = Forward. A right-button *press* would be dropped by this firmware anyway (see below).
        int keyChanged = (keyButtons ^ newButtons) & KEY_BITS;
        boolean backWas = (keyButtons & BACK_BITS) != 0, backIs = (newButtons & BACK_BITS) != 0;
        if (backWas != backIs) injectKey(KeyEvent.KEYCODE_BACK, backIs);
        if ((keyChanged & MotionEvent.BUTTON_FORWARD) != 0) injectKey(KeyEvent.KEYCODE_FORWARD, (newButtons & MotionEvent.BUTTON_FORWARD) != 0);
        keyButtons = newButtons & KEY_BITS;

        // The left button drives the pointer gesture. This firmware silently drops any mouse
        // ACTION_DOWN whose buttonState is non-zero (which is what every real mouse sends, and why
        // clicks never reached Chrome), but delivers DOWN/MOVE/UP with buttonState 0 -- the same
        // shape `input mouse tap` uses. Chrome still reports these as pointerType "mouse" clicks.
        boolean wasDown = (buttons & MotionEvent.BUTTON_PRIMARY) != 0;
        boolean isDown = (newButtons & MotionEvent.BUTTON_PRIMARY) != 0;
        if (moved) {
            inject(motion(wasDown ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, now, wasDown, 0, 0));
        }
        if (!wasDown && isDown) {
            downTime = now;
            inject(motion(MotionEvent.ACTION_DOWN, now, true, 0, 0));
        } else if (wasDown && !isDown) {
            inject(motion(MotionEvent.ACTION_UP, now, false, 0, 0));
            inject(motion(MotionEvent.ACTION_HOVER_MOVE, now, false, 0, 0));
        }
        if (wheel != 0 || hwheel != 0) {
            inject(motion(MotionEvent.ACTION_SCROLL, now, isDown, wheel, hwheel));
        }
        buttons = newButtons & MotionEvent.BUTTON_PRIMARY;
        if (moved) sendPosition();
    }

    private static MotionEvent motion(int action, long now, boolean pressed, float vscroll, float hscroll) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = pressed ? 1f : 0f;
        c.size = 1f;
        if (vscroll != 0) c.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        if (hscroll != 0) c.setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        boolean inGesture = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP;
        return MotionEvent.obtain(inGesture ? downTime : now, now, action, 1,
                new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                0, 0 /* buttonState: must stay 0, see frame() */, 1f, 1f, deviceId, 0, InputDevice.SOURCE_MOUSE,
                0 /* display */, 0, 0);
    }

    // Media / navigation keys that share the mouse's evdev node on combo receivers.
    private static void forwardKey(int linuxCode, boolean down) {
        int k;
        switch (linuxCode) {
            case 113: k = KeyEvent.KEYCODE_VOLUME_MUTE; break;
            case 114: k = KeyEvent.KEYCODE_VOLUME_DOWN; break;
            case 115: k = KeyEvent.KEYCODE_VOLUME_UP; break;
            case 158: k = KeyEvent.KEYCODE_BACK; break;
            case 159: k = KeyEvent.KEYCODE_FORWARD; break;
            case 163: k = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case 164: k = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case 165: k = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case 166: k = KeyEvent.KEYCODE_MEDIA_STOP; break;
            case 172: k = KeyEvent.KEYCODE_HOME; break;
            default: return;
        }
        injectKey(k, down);
    }

    private static void injectKey(int keyCode, boolean down) {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    // ---------- injection (hidden APIs, reachable because we run as the shell user) ----------

    private static void initInjection() throws Exception {
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
            injectTarget = g.getMethod("getInstance").invoke(null);
            injectMethod = g.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Throwable t) {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            injectTarget = im.getMethod("getInstance").invoke(null);
            injectMethod = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
    }

    private static void inject(InputEvent e) {
        try {
            if (!(Boolean) injectMethod.invoke(injectTarget, e, 0 /* async */)) log("inject refused: " + e);
        } catch (Throwable t) {
            log("inject failed: " + t);
        }
    }

    private static void readDisplaySize() {
        try {
            Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object inst = dmg.getMethod("getInstance").invoke(null);
            Object info = dmg.getMethod("getDisplayInfo", int.class).invoke(inst, 0);
            width = info.getClass().getField("logicalWidth").getInt(info);
            height = info.getClass().getField("logicalHeight").getInt(info);
        } catch (Throwable t) {
            log("display size fallback 1920x1080: " + t);
        }
    }

    // ---------- cursor position stream for CursorService ----------

    private static void startCursorServer() throws Exception {
        final ServerSocket server = new ServerSocket(PORT, 2, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    DataOutputStream old = client;
                    client = new DataOutputStream(s.getOutputStream());
                    if (old != null) try { old.close(); } catch (Throwable ignored) { }
                    log("cursor service connected");
                    sendPosition();
                } catch (Throwable e) {
                    log("accept: " + e);
                    SystemClock.sleep(500);
                }
            }
        }, "cursor-server");
        t.setDaemon(true);
        t.start();
    }

    private static void sendPosition() {
        DataOutputStream c = client;
        if (c == null) return;
        try {
            synchronized (Daemon.class) {
                c.writeInt(Math.round(x));
                c.writeInt(Math.round(y));
                c.flush();
            }
        } catch (Throwable e) {
            client = null;
        }
    }

    // ---------- helpers ----------

    private static String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            log("exec " + cmd + ": " + t);
            return null;
        }
    }

    static void log(String s) {
        System.out.println(SystemClock.uptimeMillis() + " " + s);
        System.out.flush();
    }
}

# Mouse Bridge (Sony BRAVIA VU31, Android TV 14)

Makes a USB mouse (tested with a Logitech USB Receiver, 046d:c534) click properly on the TV.
The mouse works in Chrome, WebView browsers and all other apps, including dropdowns and popovers.

## The problem

On this TV, moving a USB mouse worked but its clicks were lost. The keyboard, the TV remote's
pointer mode, scrcpy and `adb shell input mouse tap` all worked.

What was found:

- The mouse sends normal button down/up events (checked with `getevent`).
- The firmware's input dispatcher drops every mouse `ACTION_DOWN` whose `buttonState` is non-zero.
  Every real mouse sends that, so only the release arrived. The log shows
  `InputDispatcher: Conflicting pointer actions: Down received while already down or hovering.`
  Chrome's input trace showed only lone `ACTION_UP` events, which it ignores, so the page never
  got `pointerdown`/`click`.
- A press with `buttonState = 0` (the shape `input mouse tap` uses) gets through, and Chrome still
  reports it as a `pointerType: "mouse"` click.
- Ruled out: the web app (Vue/PrimeVue), scrcpy, the 1080p-on-4K display scaling, the Realtek input
  HAL, and the receiver's unusual device classes (a plain virtual HID mouse failed the same way).

## How the bridge works

Two parts, both in `build/mousebridge.apk`:

1. **Daemon** (`src/com/tvtools/mousebridge/Daemon.java`) runs as the adb shell user through
   `app_process`, like scrcpy's server. It finds the mouse's `/dev/input/eventN` node and takes it
   exclusively (`EVIOCGRAB`), so the firmware no longer sees it. Only USB (bus `0003`) or Bluetooth
   (`0005`) devices with `REL_X`, `REL_Y` and a left button count as the mouse. The TV remote
   (`IR_sony`, bus `0000`) reports the same axes for its pointer mode, so without this rule it could
   be grabbed instead. If a receiver exposes a keyboard node and a mouse node, the node without
   letter keys is used. `/dev/input/eventN` numbers change between boots, so the mouse is looked up
   by these properties, never by number. It tracks the cursor position and
   injects mouse events with `buttonState = 0` through `InputManagerGlobal.injectInputEvent`.
   - Left button: click and drag (`DOWN`/`MOVE`/`UP`)
   - Wheel: native `ACTION_SCROLL`
   - Right button and side button: Back. Extra button: Forward.
   - Media keys on the receiver (volume, play/pause, etc.) are passed through.
   - After an unplug/replug it scans for the mouse again every 2 s.
2. **CursorService** (`src/com/tvtools/mousebridge/CursorService.java`) is a small app that draws the
   white arrow cursor, because Android draws none for injected events. The daemon sends it the
   cursor position over `127.0.0.1:47110`. The overlay ignores input and sits 2 px off the click
   point, so it never blocks clicks.

## Auto-start after a reboot

Processes started over adb do not survive a reboot, and an ordinary app cannot read input devices
or inject events. So at boot the app starts the daemon through the TV's own adb:

1. `BootReceiver` gets `BOOT_COMPLETED` and starts `CursorService`.
2. `CursorService` notices the daemon is not running and, through `AdbClient`, connects to
   `127.0.0.1:5555` (the TV's network adb) as an adb client with its own RSA key. It then runs the
   same start command `start.sh` uses: the installed APK, with the options saved in
   `/data/local/tmp/mousebridge.args`.
3. The same check runs as a watchdog. If the daemon is gone for more than 6 s, it is started again,
   at most once every 30 s.
4. Once a day the app also runs a no-op command (`echo keep-alive`) over the same connection.
   Android forgets an "Always allow" key that goes unused for 7 days
   (`adb_allowed_connection_time`), and this keeps the key fresh while the TV stays on.

Tested with `adb reboot`: the app started on `BOOT_COMPLETED` and launched the daemon about 8 s
later with no prompt. The mouse worked as soon as the home screen appeared. Also tested by unplugging the TV's power cable: after
the cold boot the daemon started once, about 7 s after boot, and grabbed the Logitech mouse node
(`event3`), not the remote. Turning the TV off and
on with the remote is only standby, not a reboot: the daemon just keeps running.

The TV has to trust the app's adb key once. When `./start.sh` runs, the TV shows
**Allow USB debugging?** for the key `mousebridge@tv`. Tick **Always allow from this computer** and
choose **Allow**. `start.sh` then prints `Auto-start at boot: enabled.`

If the TV's adb keys are ever cleared (Developer options > Revoke USB debugging authorisations), or
network debugging is turned off, auto-start stops working. Run `./start.sh` again and accept the
prompt. The same happens if the TV is unplugged for more than 7 days, because the key expires. In
that case the prompt appears on screen at the next boot: tick **Always allow** and choose **Allow**,
and the bridge starts by itself.

## Usage (from the Mac, TV reachable over adb)

```bash
./build.sh                          # build build/mousebridge.apk (needs only the Android SDK + JDK)
./start.sh                          # install, start, and turn on auto-start at boot
./start.sh 192.161.0.109:5555 1.3   # pick a device and set cursor speed (default 1.0)
./stop.sh                           # stop and turn off auto-start; clicks are broken again
adb shell cat /data/local/tmp/mousebridge.log   # daemon log (every start is kept; shows which device was grabbed)
adb logcat -s MouseBridge           # app log (boot start, self-adb, watchdog)
adb shell pidof mousebridge         # is the daemon running?
```

`start.sh` does the following:
- Installs the app and grants it `SYSTEM_ALERT_WINDOW`, which the overlay needs.
- Saves the options in `/data/local/tmp/mousebridge.args`.
- Starts the daemon detached, under the process name `mousebridge`.
- Turns on auto-start and checks that the TV trusts the app's adb key.

Run it once, then again only after changing the code or the cursor speed.

## Files

| File | Purpose |
| --- | --- |
| `src/.../Daemon.java` | Shell-side daemon: grabs the mouse and injects fixed events |
| `src/.../CursorService.java` | Draws the cursor overlay, starts the daemon, acts as the watchdog, and refreshes the adb key daily |
| `src/.../AdbClient.java` | Minimal adb protocol client (connect, RSA auth, run a shell command) |
| `src/.../BootReceiver.java` | Boot auto-start, plus the ENABLE/DISABLE commands from start.sh and stop.sh |
| `AndroidManifest.xml` | App manifest |
| `build.sh`, `start.sh`, `stop.sh` | Build, install/start, and stop scripts |

## Notes

- Network debugging (adb over port 5555) must stay on, because both the Mac and auto-start use it.
- Web right-click menus are not possible: the firmware drops right-button presses as well.
- Pages get no hover events (`:hover`, `mouseenter`), because Chrome ignores injected hover moves.
  Clicks, drags and scrolling work.

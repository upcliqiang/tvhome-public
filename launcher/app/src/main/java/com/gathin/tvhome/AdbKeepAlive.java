package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps ADB reachable across a restart when the owner asks for it.
 *
 * The TV resets {@code adb_enabled} (the developer-options switch) to 0 on boot with its own
 * security policy, while the port property {@code persist.adb.tcp.port} stays at 5555. So the only
 * thing a restart loses is that switch: with it on the system starts adbd again and adbd listens on
 * the port it remembers. Writing a global setting needs WRITE_SECURE_SETTINGS, which a sideloaded
 * app cannot hold by itself; grant it once over ADB:
 * <pre>
 *   adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS
 * </pre>
 * With the switch off the launcher does not touch anything: ADB follows the TV's own setting again.
 */
final class AdbKeepAlive {
    private static final String TAG = "AdbKeepAlive";
    private static final String PREFS = "adb_keep";
    private static final String KEEP_ON = "keep_on";
    private static final String ADB_ENABLED = "adb_enabled";

    private AdbKeepAlive() { }

    /** Whether the owner asked for ADB to stay on (the panel's switch). */
    static boolean isOn(Context ctx) {
        return prefs(ctx).getBoolean(KEEP_ON, false);
    }

    /** Turns the keep-on preference on or off; on restores the switch right away. */
    static void setOn(Context ctx, boolean on) {
        // commit, not apply: a hard power cut right after the panel closes must not lose the choice.
        boolean saved = prefs(ctx).edit().putBoolean(KEEP_ON, on).commit();
        Log.i(TAG, "setOn " + on + " saved=" + saved);
        if (on) enable(ctx);
    }

    /** Called on every start and every return to Home: with keep-on set, put the ADB switch back. */
    static void apply(Context ctx) {
        if (!isOn(ctx)) return;
        boolean system = systemEnabled(ctx);
        boolean port = portListening();
        if (system && port) return;   // nothing to repair, and nothing to log on every resume
        trace(ctx, "apply keepOn=true system=" + system + " port=" + port);
        enable(ctx);
    }

    /** Whether adbd holds a LISTEN socket on the ADB port, read from the kernel's own table (no connect needed). */
    static boolean portListening() {
        // 0x15B3 = 5555; the state column is 0A for LISTEN.
        for (String table : new String[]{"/proc/net/tcp6", "/proc/net/tcp"}) {
            try (BufferedReader reader = new BufferedReader(new FileReader(table))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(":15B3") && line.contains(" 0A ")) return true;
                }
            } catch (Exception ignored) {
                // Table unreadable on this build; try the next one.
            }
        }
        return false;
    }

    /** The TV's own value of the switch, for the panel's state text. */
    static boolean systemEnabled(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), ADB_ENABLED, 0) != 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Back to the TV's own behaviour now: the switch off, exactly as a restart would leave it. */
    static void disableNow(Context ctx) {
        put(ctx, false);
    }

    private static void enable(Context ctx) {
        put(ctx, true);
    }

    private static void put(Context ctx, boolean on) {
        boolean before = systemEnabled(ctx);
        try {
            // Written unconditionally: the TV may reset it between our read and our write.
            Settings.Global.putInt(ctx.getContentResolver(), ADB_ENABLED, on ? 1 : 0);
            if (on && !before) trace(ctx, "restored system=1 port=" + portListening());
        } catch (Exception failure) {
            trace(ctx, "put " + on + " FAILED before=" + before + ": " + failure);
        }
    }

    /**
     * The same line to logcat and to a small file under the app's external files, so a start that left
     * ADB unreachable can still be read back once the owner has switched ADB on by hand.
     */
    private static void trace(Context ctx, String message) {
        Log.i(TAG, message);
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File file = new File(dir, "adb_keep.log");
            if (file.length() > 32 * 1024) file.delete();
            try (FileWriter out = new FileWriter(file, true)) {
                out.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(new Date()) + " " + message + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

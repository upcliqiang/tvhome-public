package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private static final String TAG = "TvHome.Settings";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    /** Wallpaper rotation in minutes: off, then the intervals the row offers. */
    private static final int[] ROTATIONS = {0, 15, 60, 240};
    private final TextView[] rotationChoices = new TextView[ROTATIONS.length];
    /** Idle time on Home before the screensaver: off, then the intervals the row offers. */
    private static final int[] IDLE_CLOCKS = {0, 1, 5, 10, 30};
    /** The idle-time chips in the layout, left to right; one per {@link #IDLE_CLOCKS} entry, in this order. */
    private static final int[] SAVER_CHIP_IDS =
            {R.id.saver0, R.id.saver1, R.id.saver5, R.id.saver10, R.id.saver30};
    /** How many idle-time chips are wired: the shorter of the two lists, so one side growing alone cannot crash. */
    private static final int SAVER_COUNT = Math.min(SAVER_CHIP_IDS.length, IDLE_CLOCKS.length);
    private final TextView[] saverChoices = new TextView[SAVER_COUNT];
    /** The look chips in the layout, left to right; {@link ScreensaverStyle#ALL} lists the same looks in this order. */
    private static final int[] STYLE_CHIP_IDS =
            {R.id.style0, R.id.style1, R.id.style2, R.id.style3, R.id.style4, R.id.style5};
    /** How many look chips are actually wired: the shorter of the two lists, so one side growing alone cannot crash. */
    private static final int STYLE_COUNT = Math.min(STYLE_CHIP_IDS.length, ScreensaverStyle.ALL.length);
    /** The screensaver's looks, in the order {@link ScreensaverStyle#ALL} lists them. */
    private final TextView[] styleChoices = new TextView[STYLE_COUNT];
    /** The wallpaper row, built from whatever pictures the theme ships: chips and the choice each one stores. */
    private final List<TextView> wallpaperChipViews = new ArrayList<>();
    private final List<String> wallpaperChipChoices = new ArrayList<>();
    private View animationsChoice;
    private View advancedChoice;
    /** The lunar on/off capsules, in layout order: on, off. */
    private static final int[] LUNAR_CHIP_IDS = {R.id.lunarOn, R.id.lunarOff};
    private final TextView[] lunarChoices = new TextView[LUNAR_CHIP_IDS.length];
    private View followThemeChoice;
    private View openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int wallpaperRotation;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;
    private int idleClockMinutes;
    private String screensaverStyle;
    private boolean lunarDate;

    /** The focus direction of the direction key being handled, 0 when the move has no key behind it. */
    private int navKeyDirection;

    /**
     * The rows sit inside a ScrollView, and its arrowScroll moves focus by calling requestFocus itself; that path
     * skips the navigation sound the framework plays when it runs the focus search. So this listener plays the
     * vertical sound itself. Horizontal moves between the rows DO reach the framework's own focus navigation,
     * which already plays the sound — playing here too made every Left/Right a double tick — so only the
     * wallpaper row (whose HorizontalScrollView consumes Left/Right itself, also silently) needs one from here.
     *
     * The move's direction comes from the direction key itself (see navKeyDirection), not from screen offsets:
     * a pill at a row's right end moving down to a full-width row below shifts sideways more than it shifts
     * down, and judging that by geometry silenced the sound. Geometry stays as the fallback for dialogs, whose
     * keys never reach this Activity (they have their own window) but whose full-width rows measure reliably.
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener navSound = (oldFocus, newFocus) -> {
        if (oldFocus == null || newFocus == null || oldFocus == newFocus) return;
        boolean vertical;
        int upEffect;
        switch (navKeyDirection) {
            case View.FOCUS_UP:
                vertical = true; upEffect = SoundEffectConstants.NAVIGATION_UP; break;
            case View.FOCUS_DOWN:
                vertical = true; upEffect = SoundEffectConstants.NAVIGATION_DOWN; break;
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
                vertical = false; upEffect = 0; break;
            default:
                int[] from = new int[2];
                int[] to = new int[2];
                oldFocus.getLocationOnScreen(from);
                newFocus.getLocationOnScreen(to);
                int dx = to[0] - from[0];
                int dy = to[1] - from[1];
                vertical = Math.abs(dy) >= Math.abs(dx);
                upEffect = dy < 0 ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN;
                if (!vertical && !insideHorizontalScroller(newFocus)) return; // framework already played it
        }
        if (vertical) {
            newFocus.playSoundEffect(upEffect);
        } else if (insideHorizontalScroller(newFocus)) {
            newFocus.playSoundEffect(navKeyDirection == View.FOCUS_LEFT
                    ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: navKeyDirection = View.FOCUS_UP; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: navKeyDirection = View.FOCUS_DOWN; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: navKeyDirection = View.FOCUS_LEFT; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: navKeyDirection = View.FOCUS_RIGHT; break;
                default: navKeyDirection = 0; break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** True when {@code view} sits inside a HorizontalScrollView: that row consumes Left/Right itself. */
    private static boolean insideHorizontalScroller(View view) {
        View v = view;
        while (v.getParent() instanceof View) {
            v = (View) v.getParent();
            if (v instanceof HorizontalScrollView) return true;
        }
        return false;
    }

    /** Focus moves in this window make a navigation sound from now on (see navSound). */
    private void playNavSoundOn(View root) {
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(navSound);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        playNavSoundOn(findViewById(android.R.id.content));
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        wallpaperRotation = config.wallpaperRotationMinutes;
        idleClockMinutes = config.homeIdleClockMinutes;
        screensaverStyle = ScreensaverStyle.current(this, config);
        lunarDate = config.lunarDate;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt(Config.COLUMNS_KEY, value).apply();
                changed(Config.COLUMNS_KEY);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed(Config.ROWS_BELOW_DOCK_KEY);
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed(Config.DOCK_OPACITY_KEY);
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed(Config.CLOCK_FORMAT_KEY);
                refresh();
            });
        }
        int[] rotationIds = {R.id.rotation0, R.id.rotation15, R.id.rotation60, R.id.rotation240};
        for (int i = 0; i < rotationIds.length; i++) {
            final int minutes = ROTATIONS[i];
            rotationChoices[i] = findViewById(rotationIds[i]);
            rotationChoices[i].setOnClickListener(v -> {
                if (wallpaperRotation == minutes) return;
                wallpaperRotation = minutes;
                // Rotation plays the whole wallpapers/ folder, so a single-picture pick would contradict it.
                choices.edit().putInt(Config.WALLPAPER_ROTATION_KEY, minutes)
                        .remove(Config.WALLPAPER_CHOICE_KEY).apply();
                changed(Config.WALLPAPER_ROTATION_KEY);
                refresh();
            });
        }
        // The screensaver is one setting with two segments: off/1/5/10/30, and the six looks beside it.
        // Same guard as the looks below: an interval added to one list only masks the spare chip instead of
        // reading past the end of the other.
        if (SAVER_COUNT != IDLE_CLOCKS.length || SAVER_COUNT != SAVER_CHIP_IDS.length) {
            Log.w(TAG, "screensaver intervals = " + IDLE_CLOCKS.length + ", chips = "
                    + SAVER_CHIP_IDS.length + "; showing " + SAVER_COUNT);
        }
        for (int i = 0; i < SAVER_COUNT; i++) {
            final int minutes = IDLE_CLOCKS[i];
            saverChoices[i] = findViewById(SAVER_CHIP_IDS[i]);
            saverChoices[i].setText(minutes == 0 ? getString(R.string.off)
                    : getString(R.string.screensaver_after, minutes));
            saverChoices[i].setOnClickListener(v -> {
                if (idleClockMinutes == minutes) return;
                idleClockMinutes = minutes;
                choices.edit().putInt(Config.HOME_IDLE_CLOCK_KEY, minutes).apply();
                changed(Config.HOME_IDLE_CLOCK_KEY);
                refresh();
            });
        }
        for (int i = SAVER_COUNT; i < SAVER_CHIP_IDS.length; i++) {
            findViewById(SAVER_CHIP_IDS[i]).setVisibility(View.GONE);
        }
        // A look added to the code without a chip (or a chip without a look) would otherwise read past the end of
        // one of the two lists; wire the pairs that exist, hide the spare chips, and say so in the log.
        if (STYLE_COUNT != ScreensaverStyle.ALL.length || STYLE_COUNT != STYLE_CHIP_IDS.length) {
            Log.w(TAG, "screensaver looks = " + ScreensaverStyle.ALL.length + ", chips = "
                    + STYLE_CHIP_IDS.length + "; showing " + STYLE_COUNT);
        }
        for (int i = 0; i < STYLE_COUNT; i++) {
            final String style = ScreensaverStyle.ALL[i];
            styleChoices[i] = findViewById(STYLE_CHIP_IDS[i]);
            styleChoices[i].setText(ScreensaverStyle.label(this, style));
            styleChoices[i].setOnClickListener(v -> {
                if (style.equals(screensaverStyle)) return;
                screensaverStyle = style;
                choices.edit().putString(ScreensaverStyle.KEY, style).apply();
                changed(ScreensaverStyle.KEY);
                refresh();
            });
        }
        for (int i = STYLE_COUNT; i < STYLE_CHIP_IDS.length; i++) {
            findViewById(STYLE_CHIP_IDS[i]).setVisibility(View.GONE);
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
            reverseRow(lunarChoices);
            reverseRow(rotationChoices);
            if (saverChoices.length > 0) reverseRow(saverChoices);
            if (styleChoices.length > 0) reverseRow(styleChoices);
        }
        buildWallpaperChips();
        for (int i = 0; i < LUNAR_CHIP_IDS.length; i++) {
            final boolean on = i == 0;
            lunarChoices[i] = findViewById(LUNAR_CHIP_IDS[i]);
            lunarChoices[i].setOnClickListener(v -> {
                if (on == lunarDate) return;
                lunarDate = on;
                choices.edit().putBoolean(Config.LUNAR_DATE_KEY, lunarDate).apply();
                changed(Config.LUNAR_DATE_KEY);
                refresh();
            });
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean(Config.ANIMATIONS_KEY, animations)
                    .putBoolean(Config.ADVANCED_EFFECTS_KEY, advancedEffects).apply();
            changed(Config.ANIMATIONS_KEY);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                AlertDialog confirm = dialog()
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                // Without this the dialog opens with focus on its scrolling frame, so no button looks selected.
                confirm.setOnShowListener(shown -> {
                        navKeyDirection = 0; // the dialog's keys never reach this Activity; don't reuse the last one
                        confirm.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
                });
                confirm.show();
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove(Config.COLUMNS_KEY).remove(Config.ROWS_BELOW_DOCK_KEY).remove(Config.ANIMATIONS_KEY)
                    .remove(Config.ADVANCED_EFFECTS_KEY).remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY)
                    .remove(Config.HOME_IDLE_CLOCK_KEY).remove(Config.LUNAR_DATE_KEY)
                    .remove(ScreensaverStyle.KEY)
                    .remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            idleClockMinutes = current.homeIdleClockMinutes;
            screensaverStyle = ScreensaverStyle.current(this, current);
            lunarDate = current.lunarDate;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed(Config.RESET_KEY);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        // Wallpapers from a phone: a QR code the phone can scan, so nobody has to type the address.
        UploadServer.get(this).start();
        findViewById(R.id.uploadChoice).setOnClickListener(v -> showUploadDialog());
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    /** A dialog that belongs to this page: the same glass sheet and white focus pill, not the platform's box. */
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, R.style.Theme_TvHome_Dialog);
    }

    /**
     * The wallpaper upload page, as a QR code: the phone's camera opens the address, which beats typing
     * {@code http://192.168.x.x:8080} with a remote. The address is repeated as text for anything that cannot
     * scan, and when the server never came up the dialog says so instead of showing a dead code.
     */
    private void showUploadDialog() {
        String address = UploadServer.get(this).address();
        AlertDialog.Builder builder = dialog().setTitle(R.string.upload_dialog_title);
        if (address == null) {
            builder.setMessage(R.string.upload_off);
        } else {
            View body = getLayoutInflater().inflate(R.layout.dialog_upload_qr, null);
            ((TextView) body.findViewById(R.id.qrAddress)).setText(address);
            Bitmap code = qrCode(address, 640);
            ImageView image = body.findViewById(R.id.qrImage);
            if (code != null) image.setImageBitmap(code);
            else image.setVisibility(View.GONE);
            builder.setView(body);
        }
        AlertDialog dialog = builder.setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(shown -> {
            navKeyDirection = 0;   // the dialog's keys never reach this Activity
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
        });
        dialog.show();
    }

    /** Black modules on white at {@code size} pixels: what a camera expects, not the platform's grey box. */
    private static Bitmap qrCode(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                int row = y * size;
                for (int x = 0; x < size; x++) {
                    pixels[row + x] = matrix.get(x, y) ? 0xFF111111 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException | RuntimeException e) {
            Log.w("TvHome", "QR failed: " + e.getMessage());
            return null;
        }
    }

    private interface Pick {
        void onPick(int which);
    }

    /**
     * A one-of list in a dialog, built from the settings page's own rows: they take focus one by one and turn into
     * the white pill, and the current choice carries a check. The framework list it replaces drew grey bands behind
     * every row and no highlight at all on this platform.
     */
    private AlertDialog chooseDialog(int title, CharSequence[] labels, int checked, Pick pick) {
        final AlertDialog[] handle = new AlertDialog[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            View row = getLayoutInflater().inflate(R.layout.dialog_choice_row, list, false);
            ((TextView) row.findViewById(R.id.choiceLabel)).setText(labels[i]);
            row.findViewById(R.id.choiceCheck).setVisibility(i == checked ? View.VISIBLE : View.GONE);
            final int which = i;
            row.setOnClickListener(v -> {
                handle[0].dismiss();
                pick.onPick(which);
            });
            list.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // A long list (the start-up apps) stops growing at eight rows and scrolls inside the dialog instead.
        float density = getResources().getDisplayMetrics().density;
        int height = Math.round(Math.min(labels.length * 54f, 320f) * density);
        // The panel gives its custom view no inset of its own, so the rows would touch the rounded edge.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(12f * density);
        box.setPadding(pad, Math.round(10f * density), pad, Math.round(2f * density));
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        AlertDialog dialog = dialog().setTitle(title).setView(box)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> {
            navKeyDirection = 0; // the dialog's keys never reach this Activity; don't reuse the last one
            View row = list.getChildAt(checked < 0 ? 0 : checked);
            if (row != null) row.requestFocus();
            // Its rows scroll through a ScrollView exactly like the page's, so they need the sound from here too.
            playNavSoundOn(dialog.getWindow().getDecorView());
        });
        handle[0] = dialog;
        return dialog;
    }

    /**
     * The wallpaper row as segments instead of a dialog: theme default, the theme's single wallpaper.jpg, every
     * picture in wallpapers/, then none. A theme brings its own set, so the chips are made here and the row
     * scrolls sideways when a collection outgrows it.
     */
    private void buildWallpaperChips() {
        LinearLayout row = findViewById(R.id.wallpaperChips);
        File dir = Config.dir(this);
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        addWallpaperChip(row, null, getString(R.string.wallpaper_default_chip));
        if (single != null && single.isFile()) {
            addWallpaperChip(row, single.getName(), pictureLabel(single.getName()));
        }
        for (File image : WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers"))) {
            addWallpaperChip(row, image.getName(), pictureLabel(image.getName()));
        }
        addWallpaperChip(row, WallpaperRotation.CHOICE_NONE, getString(R.string.wallpaper_none));
        // A collection wide enough to scroll gets cut off by the viewport, and that cut is square: the trough would
        // end in a straight vertical line beside the rounded troughs above and below. Rounding the viewport's own
        // outline makes the cut follow the same 24dp arc as every other row.
        final float radius = getResources().getDisplayMetrics().density * 24;
        HorizontalScrollView scroll = findViewById(R.id.wallpaperScroll);
        scroll.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        scroll.setClipToOutline(true);
        // The outline above is measured on demand; a bounds change (first layout, a rotation, a locale switch) has
        // to drop the cached one or the arc keeps an older size.
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.invalidateOutline());
        // The other segment rows are mirrored above, before these chips existed; mirror this one too. Only the
        // views move, so wallpaperChipChoices stays in pick order for refresh()'s index-by-index comparison.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(wallpaperChipViews.toArray(new TextView[0]));
        }
    }

    /** One chip; {@code choice} is what the pick stores, or null for "let the theme decide". */
    private void addWallpaperChip(LinearLayout row, String choice, String label) {
        TextView chip = (TextView) getLayoutInflater().inflate(R.layout.settings_segment_choice, row, false);
        chip.setText(label);
        if (!wallpaperChipChoices.isEmpty()) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) chip.getLayoutParams();
            params.setMarginStart(Math.round(4 * getResources().getDisplayMetrics().density));
        }
        chip.setOnClickListener(v -> {
            String stored = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
            // One picture means no rotation: the two rows describe the same choice.
            if (sameChoice(choice, stored) && wallpaperRotation == 0) return;
            wallpaperRotation = 0;
            if (choice == null) {
                choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                        .remove(Config.WALLPAPER_ROTATION_KEY).apply();
            } else {
                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, choice)
                        .putInt(Config.WALLPAPER_ROTATION_KEY, 0).apply();
            }
            changed(Config.WALLPAPER_CHOICE_KEY);
            refresh();
        });
        wallpaperChipViews.add(chip);
        wallpaperChipChoices.add(choice);
        row.addView(chip);
    }

    private static boolean sameChoice(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * A picture's file name without its extension or the leading "01-" sort prefix, which is all a chip fits.
     * The row has to hold every picture plus "default" and "none", so a long name loses its tail words
     * ("blue-hour-sea" is shown as "blue-hour") rather than pushing the last chip out of sight.
     */
    private static String pictureLabel(String name) {
        int dot = name.lastIndexOf('.');
        String label = dot > 0 ? name.substring(0, dot) : name;
        String trimmed = label.replaceFirst("^[0-9]+[-_. ]+", "");
        if (!trimmed.isEmpty()) label = trimmed;
        while (label.length() > 12) {
            int dash = label.lastIndexOf('-');
            if (dash <= 0) break;
            label = label.substring(0, dash);
        }
        return label.length() > 12 ? label.substring(0, 12) + "…" : label;
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        chooseDialog(R.string.open_on_start_label, labels, checked, which -> {
            if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
            else {
                OpenOnStart.skipThisBoot(this);
                choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
            }
            refresh();
        }).show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /**
     * A choice was saved. Home picks it up when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result); the keys
     * say which ones changed, so Home only rebuilds for the ones it cannot apply in place.
     */
    private void changed(String... keys) {
        setResult(RESULT_OK);
        Set<String> changed = new HashSet<>(choices.getStringSet(Config.SETTINGS_CHANGED_KEY,
                Collections.emptySet()));
        Collections.addAll(changed, keys);
        choices.edit().putStringSet(Config.SETTINGS_CHANGED_KEY, changed)
                .putLong(Config.SETTINGS_REVISION_KEY,
                        choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean(Config.ADVANCED_EFFECTS_KEY, enabled).apply();
        changed(Config.ADVANCED_EFFECTS_KEY);
        refresh();
    }

    private void refresh() {
        // Down from the reorder pill lands on the row below's current choice, not on its last segment.
        findViewById(R.id.reorderChoice).setNextFocusDownId(
                columnChoices[Math.max(0, Math.min(columns - 4, 3))].getId());
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        for (int i = 0; i < rotationChoices.length; i++) {
            rotationChoices[i].setSelected(wallpaperRotation == ROTATIONS[i]);
        }
        for (int i = 0; i < saverChoices.length; i++) {
            saverChoices[i].setSelected(idleClockMinutes == IDLE_CLOCKS[i]);
        }
        for (int i = 0; i < styleChoices.length; i++) {
            styleChoices[i].setSelected(ScreensaverStyle.ALL[i].equals(screensaverStyle));
        }
        setRowValue(R.id.rowValueAnimations, getString(animations ? R.string.on : R.string.off));
        setRowValue(R.id.rowValueAdvanced, getString(advancedEffects ? R.string.on : R.string.off));
        String uploadAddress = UploadServer.get(this).address();
        setRowValue(R.id.rowValueUpload, uploadAddress == null ? getString(R.string.upload_off)
                : uploadAddress.replaceFirst("^https?://", ""));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        for (int i = 0; i < wallpaperChipViews.size(); i++) {
            wallpaperChipViews.get(i).setSelected(sameChoice(wallpaperChipChoices.get(i), wallpaperCurrent));
        }
        setRowValue(R.id.rowValueOpenOnStart, openOnStartLabel());
        lunarChoices[0].setSelected(lunarDate);
        lunarChoices[1].setSelected(!lunarDate);
        boolean followsTheme = !hasOnTvChoices();
        followThemeChoice.setSelected(followsTheme);
        findViewById(R.id.rowCheck).setVisibility(followsTheme ? View.VISIBLE : View.GONE);
    }

    /** Writes the right-hand value of a row laid out as label, value and an optional chevron. */
    private void setRowValue(int id, CharSequence value) {
        TextView text = findViewById(id);
        if (text != null) text.setText(value);
    }

    private boolean hasOnTvChoices() {
        return choices.contains(Config.COLUMNS_KEY) || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains(Config.ANIMATIONS_KEY) || choices.contains(Config.ADVANCED_EFFECTS_KEY)
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.WALLPAPER_ROTATION_KEY) || choices.contains(Config.CLOCK_FORMAT_KEY)
                || choices.contains(Config.HOME_IDLE_CLOCK_KEY) || choices.contains(ScreensaverStyle.KEY)
                || choices.contains(Config.LUNAR_DATE_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}

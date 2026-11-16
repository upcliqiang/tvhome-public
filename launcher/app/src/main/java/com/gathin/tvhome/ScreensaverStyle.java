package com.gathin.tvhome;

import android.content.Context;

/**
 * The screensaver's six looks, each a backdrop and what sits on it. A theme still picks the default through
 * clock_wallpaper and clock_glow; a choice made on the TV is stored under {@link #KEY} and wins, like every
 * other row in Launcher settings.
 */
final class ScreensaverStyle {
    static final String KEY = "screensaver_style";

    /** The clock over the slow ambient glow: the original look, and the default. */
    static final String CLOCK_GLOW = "clock_glow";
    /** The clock over Home's wallpaper, darkened by clock_wallpaper_dim. */
    static final String CLOCK_WALLPAPER = "clock_wallpaper";
    /** The clock on plain black, for an OLED. */
    static final String CLOCK_BLACK = "clock_black";
    /** The date alone, large, over the glow: no clock. */
    static final String DATE = "date";
    /** One line of text, changed every few minutes, over the glow: no clock. */
    static final String QUOTE = "quote";
    /** The wallpaper alone, full bleed, with nothing on it. */
    static final String PHOTO = "photo";

    static final String[] ALL = {CLOCK_GLOW, CLOCK_WALLPAPER, CLOCK_BLACK, DATE, QUOTE, PHOTO};

    private static final int[] LABELS = {
            R.string.screensaver_style_clock_glow,
            R.string.screensaver_style_clock_wallpaper,
            R.string.screensaver_style_clock_black,
            R.string.screensaver_style_date,
            R.string.screensaver_style_quote,
            R.string.screensaver_style_photo,
    };

    private ScreensaverStyle() {
    }

    /** The style in force: the on-TV choice when it is still one of these, else the theme's clock_* defaults. */
    static String current(Context ctx, Config cfg) {
        String stored = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, null);
        return indexOf(stored) >= 0 ? stored : fromConfig(cfg);
    }

    /** A theme that turns on clock_wallpaper or off clock_glow keeps meaning what it meant before this row existed. */
    static String fromConfig(Config cfg) {
        return cfg.clockWallpaper ? CLOCK_WALLPAPER : cfg.clockGlow ? CLOCK_GLOW : CLOCK_BLACK;
    }

    static int indexOf(String style) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].equals(style)) return i;
        }
        return -1;
    }

    static String label(Context ctx, String style) {
        int i = indexOf(style);
        return ctx.getString(LABELS[i < 0 ? 0 : i]);
    }

    /** The coloured glow behind the content. */
    static boolean glow(String style) {
        return CLOCK_GLOW.equals(style) || DATE.equals(style) || QUOTE.equals(style);
    }

    /** The wallpaper behind the content (or as the whole picture, for PHOTO). */
    static boolean wallpaper(String style) {
        return CLOCK_WALLPAPER.equals(style) || PHOTO.equals(style);
    }

    /** The layout inflated into dreamHost, or 0 for a look with no content at all. */
    static int content(String style) {
        if (CLOCK_GLOW.equals(style) || CLOCK_WALLPAPER.equals(style) || CLOCK_BLACK.equals(style)) {
            return R.layout.screensaver_clock;
        }
        if (DATE.equals(style)) return R.layout.screensaver_date;
        if (QUOTE.equals(style)) return R.layout.screensaver_quote;
        return 0;
    }

    /**
     * The content drifts every minute so a static panel cannot burn in. A full-screen picture needs no drift, and
     * the quote fades to a new line every few minutes instead (its own animation, not this one).
     */
    static boolean drifts(String style) {
        return !PHOTO.equals(style) && !QUOTE.equals(style);
    }
}

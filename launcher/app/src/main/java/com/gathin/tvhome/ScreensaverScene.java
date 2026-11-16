package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The screensaver's scene (layout screensaver_scene): a backdrop (ambient glow, Home's wallpaper, or black) with
 * one content layout from {@link ScreensaverStyle} on it. The content drifts once a minute and the glow shifts
 * colour, so nothing burns in.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ScreensaverScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** A quote lasts five minutes: long enough to read it twice, short enough that the screen is not frozen. */
    private static final int QUOTE_TICKS = 5;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final String style;
    private final Resources res;
    /** Null for a style with nothing on the backdrop (PHOTO). */
    private final View content;
    private final ImageView ambient;
    private final GradientDrawable glow = new GradientDrawable();
    private final TextView quoteView;
    private final TextView dateMain;
    private final TextView dateWeek;
    private final TextView dateLunar;
    private int colorIndex;
    private int glowColor;
    /** True once a coloured glow is the backdrop; the wallpaper and black styles leave it off. */
    private boolean glowEnabled;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;
    /** DATE: whether the second line shows the lunar date, and which time zone the calendar is computed for. */
    private boolean lunar;
    private int lunarOffset = -1;
    private String lunarText = "";
    private int quoteIndex = -1;
    private int quoteTicks;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveContent();
            shiftGlow();
            advanceWallpaper();
            if (quoteView != null && ++quoteTicks >= QUOTE_TICKS) {
                quoteTicks = 0;
                nextQuote();
            }
            // Midnight passes while the screensaver is up: the date has to follow it.
            if (dateMain != null) updateDate();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated screensaver_scene layout. */
    ScreensaverScene(View root, String style) {
        this.style = style;
        res = root.getResources();
        ambient = root.findViewById(R.id.dreamAmbient);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);

        View host = root.findViewById(R.id.dreamHost);
        int layout = ScreensaverStyle.content(style);
        if (layout != 0) {
            LayoutInflater.from(root.getContext()).inflate(layout, (ViewGroup) host, true);
            content = host.findViewById(R.id.dreamContent);
            quoteView = content.findViewById(R.id.dreamQuote);
            dateMain = content.findViewById(R.id.dreamDateMain);
            dateWeek = content.findViewById(R.id.dreamDateWeek);
            dateLunar = content.findViewById(R.id.dreamDateLunar);
        } else {
            content = null;
            quoteView = null;
            dateMain = null;
            dateWeek = null;
            dateLunar = null;
        }
        if (quoteView != null) {
            // A long line wraps instead of running off the screen; place() then has room to centre it.
            DisplayMetrics dm = res.getDisplayMetrics();
            quoteView.setMaxWidth(Math.round(dm.widthPixels * 0.72f));
        }
        if (ScreensaverStyle.glow(style)) enableGlow();
    }

    void start() {
        if (content != null) {
            content.setAlpha(0f);
            // The content was inflated after setContentView, so its size is still 0 in this frame; place() needs
            // the measured width or the block would be positioned as if it filled the screen.
            if (content.getWidth() > 0) {
                enter();
            } else {
                content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b,
                                               int ol, int ot, int or, int ob) {
                        if (content.getWidth() == 0) return;
                        content.removeOnLayoutChangeListener(this);
                        enter();
                    }
                });
            }
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (quoteView != null && quoteView.getText().length() == 0) quoteView.setText(pickQuote());
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    /** First position, then the fade in: both wait for the layout to know how big the block is. */
    private void enter() {
        place();
        content.animate().alpha(1f).setDuration(1200).start();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        if (content != null) content.animate().cancel();
        if (quoteView != null) quoteView.animate().cancel();   // a line fading in must not finish on a dead scene
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * The backdrop and the date, read from Config before start(): the wallpaper styles put the picture Home is
     * showing behind the content, darkened, and carry a collection's rotation on while the screensaver is up.
     * Decoded off the main thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if
     * it cannot be read, the glow stays.
     */
    void prepare(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        lunar = cfg.lunarDate;
        lunarOffset = cfg.lunarUtcOffset;
        updateDate();
        if (!ScreensaverStyle.wallpaper(style)) return;
        // What Home plays, and Config puts the single wallpaper in it when a theme has no collection.
        // Reading cfg.wallpaper first held the screensaver on wallpaper.jpg while Home rotated.
        if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            // Start on the picture Home is showing. WallpaperPlayer records it under the same
            // preferences and the screensaver runs in the same process, so this is just a read.
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            // Home only rotates while it is visible, so the screensaver carries the rotation on
            // itself; without this it holds one picture for as long as it is up.
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        if (wallpaperFile == null) {
            // Chosen the full-screen picture but there is none to show: a glow beats an empty black panel.
            enableGlow();
            return;
        }
        // The picture is the subject in the PHOTO style, so it is not darkened there.
        wallpaperShade = ScreensaverStyle.PHOTO.equals(style) ? 0 : (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = res;
    }

    private void enableGlow() {
        if (glowEnabled) return;
        glowEnabled = true;
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources resources = wallpaperRes;
        if (file == null || resources == null) return;
        DisplayMetrics dm = resources.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(resources, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;   // a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods == null || moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private void moveContent() {
        if (content == null || !ScreensaverStyle.drifts(style)) return;
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            place();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole block on screen, with the glow centred behind it. */
    private void place() {
        if (content == null) return;
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x, y;
        if (ScreensaverStyle.QUOTE.equals(style)) {
            x = maxX / 2f;   // a sentence reads best where the eye already is
            y = maxY / 2f;
        } else {
            x = maxX * (0.1f + 0.8f * random.nextFloat());
            y = maxY * (0.1f + 0.8f * random.nextFloat());
        }
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    /** The day, the weekday and the lunar date, in the locale's own order. */
    private void updateDate() {
        if (dateMain == null) return;
        Locale locale = res.getConfiguration().getLocales().get(0);
        Calendar now = Calendar.getInstance();
        dateMain.setText(format(now, locale, "MMMd"));
        dateWeek.setText(format(now, locale, "EEEE"));
        if (!lunar) {
            dateLunar.setVisibility(View.GONE);
            return;
        }
        String text = Lunar.today(res, Lunar.offsetFor(lunarOffset, locale));
        if (!text.equals(lunarText)) {
            lunarText = text;
            dateLunar.setText(text);
        }
    }

    private static String format(Calendar day, Locale locale, String skeleton) {
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(day.getTime());
    }

    /** The next line of the quote style, faded out and in so the change is not a jolt. */
    private void nextQuote() {
        final String text = pickQuote();
        quoteView.animate().alpha(0f).setDuration(700).withEndAction(() -> {
            quoteView.setText(text);
            quoteView.animate().alpha(1f).setDuration(900).start();
        }).start();
    }

    /** A line other than the one on screen, so a small list never repeats twice in a row. */
    private String pickQuote() {
        String[] quotes = res.getStringArray(R.array.screensaver_quotes);
        if (quotes.length == 0) return "";
        int next = random.nextInt(quotes.length);
        if (quotes.length > 1 && next == quoteIndex) next = (next + 1) % quotes.length;
        quoteIndex = next;
        return quotes[next];
    }

    private void shiftGlow() {
        if (!glowEnabled || wallpaperShown) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}

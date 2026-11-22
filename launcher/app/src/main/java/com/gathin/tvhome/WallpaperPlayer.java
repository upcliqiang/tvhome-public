package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final int backgroundColor;
    /** The pictures on rotation; swapped in place when a wallpaper is picked in Launcher settings. */
    private final List<File> files = new ArrayList<>();
    private long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    /** The decoded pixels of the pictures seen so far, so a start does not decode the JPEG again. */
    private final WallpaperCache cache;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files.addAll(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        cache = new WallpaperCache(new File(context.getCacheDir(), "wallpaper-raw"));
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is put in place right here, on the main thread, as the single wallpaper always
     * was: Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * It comes from the pixel cache when that is current, so the JPEG behind it is only decoded on the very first
     * start (or after the picture changed); only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    /**
     * Swaps the collection in place, for a wallpaper picked in Launcher settings: Home keeps running, so only the
     * picture changes. An empty list turns the wallpaper off and puts Home's plain colour back. The first picture is
     * decoded here, on the main thread, exactly as at a start: the pixel cache makes that a memcpy on any picture
     * seen before, and a change should not flash the plain colour first.
     */
    void setCollection(List<File> images, int rotationMinutes) {
        if (closed) return;
        interval = rotationMinutes * 60_000L;
        // The same picture stays: another rotation interval must not restart the collection.
        if (!files.isEmpty() && files.size() == 1 && images.size() == 1
                && files.get(0).getName().equals(images.get(0).getName()) && image.getDrawable() != null) {
            files.clear();
            files.addAll(images);
            return;
        }
        files.clear();
        files.addAll(images);
        // Playback state belongs to one collection (see the constructor): a new set starts from its first picture.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        // Let the picture on screen go: it is replaced below, and nothing else draws it.
        image.setImageDrawable(null);
        current = null;
        displayed = "";
        if (files.isEmpty()) {
            if (image.getParent() != null) root.removeView(image);
            root.setBackgroundColor(backgroundColor);
            return;
        }
        if (image.getParent() == null) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.setBackgroundColor(backgroundColor);
        showFirst();
        // Every picture unreadable: keep the plain colour rather than an empty ImageView over it.
        if (displayed.isEmpty()) {
            image.setImageDrawable(null);
            return;
        }
        if (active) update();
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** Drops the wallpaper bitmap when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null) return;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references it. Drop our reference and let the picture be
        // reclaimed once nothing draws it, instead of freeing its pixels mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /**
     * Drops the wallpaper and frees its pixels, for a Home that is going away.
     *
     * A relaunch on this TV keeps the old Home reachable from the renderer, so a picture only released here waits
     * for a collection that never comes and its pixels stay in the native heap for the life of the process: a
     * 1080p RGB_565 wallpaper is 4.15 MB, paid again on every settings change. Nothing draws it any more, so the
     * pixels can go now rather than mid-frame.
     */
    void releaseForRebuild() {
        if (current == null) return;
        image.setImageDrawable(null);
        if (!current.isRecycled()) current.recycle();
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        try {
            decoder.execute(() -> {
                Bitmap bitmap = decode(next);
                root.post(() -> {
                    loading = false;
                    if (closed || !active) {
                        // decode() queues the copy of this picture's pixels for the disk cache on the decoder
                        // thread, and that copy can still be running here: recycling the bitmap would free the
                        // pixels under it. Let it be collected instead, once the copy has finished.
                        return;
                    }
                    if (bitmap == null) {
                        files.remove(next);
                        Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                        update();
                        return;
                    }
                    show(bitmap);
                    displayed = next.getName();
                    showCaption(true);
                    if (!displayed.equals(previous) || changed == 0) {
                        preferences.edit().putString("image", displayed)
                                .putLong("changed", System.currentTimeMillis()).apply();
                    }
                });
            });
        } catch (RejectedExecutionException closing) {
            // close() ran between the tick and this call: nothing rotates any more, so let the next start retry.
            loading = false;
        }
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and lets the previous one go once nothing draws it. */
    private void show(Bitmap bitmap) {
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        image.setImageBitmap(bitmap);
    }

    private Bitmap decode(File file) {
        final boolean trueColor = bitmapConfig == Bitmap.Config.ARGB_8888;
        Bitmap cached = cache.load(file, width, height, trueColor, dimColor);
        if (cached != null) return cached;
        try {
            Bitmap decoded = WallpaperDecode.decode(file, width, height, bitmapConfig, dimColor != 0);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // Keep the pixels for the next start: it reads them back instead of decoding this JPEG again.
            final int decodedWidth = decoded.getWidth(), decodedHeight = decoded.getHeight();
            // Reading the pixels back is a full-screen copy (8 MB at 1080p). It runs on the decoder thread, not on
            // the caller's: the first picture is decoded on the main thread, before Home's first frame.
            try {
                decoder.execute(() -> {
                    try {
                        if (decoded.isRecycled()) return;   // let go by the caller before this copy started
                        ByteBuffer pixels = DiskBitmapCache.snapshot(decoded);
                        cache.save(file, this.width, this.height, trueColor, dimColor,
                                pixels, decodedWidth, decodedHeight);
                    } catch (Exception | OutOfMemoryError ignored) {
                        // A lost cache entry only costs the next start one more decode.
                    }
                });
            } catch (RejectedExecutionException closing) {
                // Home is going away; the next start decodes this picture again.
            }
            // Kept in software. copy(HARDWARE) blocks its caller for about 350 ms on this class of TV
            // (measured), and Home decodes the first picture on the main thread: the texture the renderer
            // makes of a software bitmap instead uploads off the UI thread, once, while the tiles are laid out.
            return decoded;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}

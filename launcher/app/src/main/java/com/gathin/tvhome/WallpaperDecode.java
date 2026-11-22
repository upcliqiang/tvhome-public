package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * The one wallpaper decode both the rotation player and the screensaver go through: bounds first,
 * then WallpaperSize picks the sample size and the cover scale, so the picture comes off the disk
 * already the right size to cover the target — sampled, never bigger than the cap.
 */
final class WallpaperDecode {

    private WallpaperDecode() { }

    /** The decoded picture, or null for an unreadable file or a size the cap refuses. */
    static Bitmap decode(File file, int targetW, int targetH, Bitmap.Config config, boolean mutable) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, targetW, targetH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, targetW, targetH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = config;
            options.inMutable = mutable;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}

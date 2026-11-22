package com.gathin.tvhome;

import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

/**
 * The picture Home opens with, kept as its already decoded pixels.
 *
 * A 1920x1080 JPEG takes about half a second to decode on a TV's CPU, and Home decodes the first
 * wallpaper on the main thread so its first frame already has the picture (#166). Reading those same
 * pixels back from a raw file is a memcpy, so every start after the first one skips the decode.
 *
 * The file is keyed by everything the pixels depend on — the source's name, size and timestamp, the
 * screen size, the colour config and the dim — so a changed picture, theme or display simply misses.
 */
final class WallpaperCache extends DiskBitmapCache {
    private static final int MAGIC = 0x54565043;   // "TVPC"
    private static final int RGB_565 = 0, ARGB_8888 = 1;

    WallpaperCache(File folder) {
        super(folder);
    }

    /** Where the pixels of this source would be kept; also the key a save and a load have to agree on. */
    private File file(File source, int width, int height, boolean trueColor, int dim) {
        String key = source.getName() + '-' + source.length() + '-' + source.lastModified()
                + '-' + width + 'x' + height + '-' + (trueColor ? ARGB_8888 : RGB_565) + '-' + dim;
        return new File(folder, key + ".raw");
    }

    /** The cached pixels, or null when there are none (or they cannot be read: the caller decodes instead). */
    Bitmap load(File source, int width, int height, boolean trueColor, int dim) {
        File cache = file(source, width, height, trueColor, dim);
        if (!cache.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cache), 1 << 16))) {
            if (!headerMatches(in, cache, MAGIC)) return null;
            int w = in.readInt(), h = in.readInt(), config = in.readInt();
            if (w <= 0 || h <= 0 || (config != RGB_565 && config != ARGB_8888)) {
                cache.delete();
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h,
                    config == ARGB_8888 ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
            byte[] pixels = new byte[bitmap.getRowBytes() * h];
            in.readFully(pixels);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            return bitmap;
        } catch (Exception | OutOfMemoryError failure) {
            cache.delete();
            return null;
        }
    }

    /** Writes the pixels for this source, replacing any earlier entry. Runs off the UI thread. */
    void save(File source, int width, int height, boolean trueColor, int dim, ByteBuffer pixels,
              int bitmapWidth, int bitmapHeight) {
        File cache = file(source, width, height, trueColor, dim);
        writeEntry(cache, MAGIC, out -> {
            out.writeInt(bitmapWidth);
            out.writeInt(bitmapHeight);
            out.writeInt(trueColor ? ARGB_8888 : RGB_565);
            out.write(pixels.array(), 0, pixels.limit());
        });
        // One picture is what Home needs: drop the ones from other wallpapers or settings.
        File[] stale = folder.listFiles();
        if (stale == null) return;
        for (File other : stale) {
            if (!other.getName().equals(cache.getName())) other.delete();
        }
    }
}

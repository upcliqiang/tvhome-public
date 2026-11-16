package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

/**
 * A tile's generated artwork, kept on disk so a start does not build it again.
 *
 * The uniform tile is drawn from two bitmaps made out of the app's icon: the fill strip the card stretches
 * behind the mark, and the mark itself widened to the tile (see AppAdapter's backdrop and wideArt). Drawing
 * them costs 7-9 ms per tile, but an icon that has to be re-rasterised on every draw (a vector or adaptive
 * one) costs far more — one app on this TV took 93 ms for a single tile.
 *
 * The file is keyed by the app's package, version and update time plus the tile size, so an updated app
 * misses and gets drawn afresh; the colour and the edge flag the drawing sampled are stored with it, so a
 * hit skips the icon even for those. Only a bounded number of entries is kept (a rotated set of tiles, or a
 * theme's different tile size, would otherwise pile up) and the oldest go first.
 */
final class TileArtCache {
    private static final int MAGIC = 0x54564152;   // "TVAR"
    private static final int VERSION = 1;
    /** Room for two sets of tiles: the current one, plus what a change of tile size or theme left behind. */
    private static final int MAX_FILES = 32;

    /** What a tile needs to draw itself: the sampled colour and edge flag, and the two bitmaps. */
    static final class Art {
        final int ambient;
        final boolean plainEdge;
        final Bitmap fill;
        final Drawable wide;

        Art(int ambient, boolean plainEdge, Bitmap fill, Drawable wide) {
            this.ambient = ambient;
            this.plainEdge = plainEdge;
            this.fill = fill;
            this.wide = wide;
        }

        /** Lets go of an entry that turned out to be unreadable part-way through. */
        void recycle() {
            if (fill != null && !fill.isRecycled()) fill.recycle();
        }
    }

    private final File folder;

    TileArtCache(File folder) {
        this.folder = folder;
    }

    private File file(String key) {
        return new File(folder, key + ".art");
    }

    /** Whether this key's artwork is on disk. reload() asks this before loading a tile's icon at all, so it only
     *  pays one stat per tile rather than the read and decode {@link #load} does. */
    boolean has(String key) {
        return file(key).isFile();
    }

    /** The cached artwork for this key, or null when there is none (or it cannot be read: the caller draws). */
    Art load(String key) {
        File cache = file(key);
        if (!cache.isFile()) return null;
        Bitmap fill = null, wide = null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cache), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                cache.delete();
                return null;
            }
            int ambient = in.readInt();
            boolean plain = in.readInt() != 0;
            int fillW = in.readInt(), fillH = in.readInt();
            int wideW = in.readInt(), wideH = in.readInt();
            if (fillW <= 0 || fillH <= 0 || wideW < 0 || wideH < 0) {
                cache.delete();
                return null;
            }
            fill = read(in, fillW, fillH);
            if (wideW > 0 && wideH > 0) wide = read(in, wideW, wideH);
            return new Art(ambient, plain, fill, wide == null ? null : new BitmapDrawable(null, wide));
        } catch (Exception | OutOfMemoryError failure) {
            if (fill != null) fill.recycle();
            if (wide != null) wide.recycle();
            cache.delete();
            return null;
        }
    }

    private static Bitmap read(DataInputStream in, int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        byte[] pixels = new byte[bitmap.getRowBytes() * height];
        in.readFully(pixels);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        return bitmap;
    }

    /** The bitmap's pixels, to hand to {@link #save} on another thread: copying them here keeps the write's
     *  cost off the UI thread while the bitmap is still ours to read. */
    static ByteBuffer snapshot(Bitmap bitmap) {
        ByteBuffer pixels = ByteBuffer.allocate(bitmap.getRowBytes() * bitmap.getHeight());
        bitmap.copyPixelsToBuffer(pixels);
        pixels.rewind();
        return pixels;
    }

    /** Writes this tile's artwork, replacing any earlier entry. Runs off the UI thread. */
    void save(String key, int ambient, boolean plain, ByteBuffer fill, int fillW, int fillH,
              ByteBuffer wide, int wideW, int wideH) {
        File cache = file(key);
        File temp = new File(folder, cache.getName() + ".tmp");
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) return;
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(ambient);
                out.writeInt(plain ? 1 : 0);
                out.writeInt(fillW);
                out.writeInt(fillH);
                out.writeInt(wide == null ? 0 : wideW);
                out.writeInt(wide == null ? 0 : wideH);
                out.write(fill.array(), 0, fill.limit());
                if (wide != null) out.write(wide.array(), 0, wide.limit());
            }
            // Rename over the old entry: a half-written file is never read back as artwork.
            if (!temp.renameTo(cache)) {
                cache.delete();
                temp.renameTo(cache);
            }
        } catch (Exception failure) {
            temp.delete();
            return;
        }
        trim();
    }

    /** Keeps the newest few entries: what a change of tile size or theme no longer asks for goes first, and a
     *  half-written file from a start that was killed mid-save goes too. */
    private void trim() {
        File[] all = folder.listFiles();
        if (all == null) return;
        Arrays.sort(all, Comparator.comparingLong(File::lastModified));
        long oldest = System.currentTimeMillis() - STALE_TEMP_MS;
        int keep = MAX_FILES;
        // Newest first, so the tiles the current Home draws are the ones kept.
        for (int i = all.length - 1; i >= 0; i--) {
            File file = all[i];
            if (file.getName().endsWith(".tmp")) {
                if (file.lastModified() < oldest) file.delete();
            } else if (keep-- <= 0) {
                file.delete();
            }
        }
    }

    /** A temporary file older than this was abandoned by a save that never finished. */
    private static final long STALE_TEMP_MS = 24L * 60 * 60 * 1000;
}

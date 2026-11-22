package com.gathin.tvhome;

import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * The skeleton both on-disk pixel caches share: a magic-and-version header, an atomic write (the
 * body goes to a temp file that is renamed over the old entry, so a half-written file is never
 * read back), and the pixel snapshot that hands a decoded bitmap's bytes to another thread.
 * WallpaperCache (the start-up wallpaper) and TileArtCache (the tiles' artwork) fill in the body;
 * each keeps its own MAGIC so the two file families can never be confused.
 */
abstract class DiskBitmapCache {

    private static final int VERSION = 1;

    protected final File folder;

    DiskBitmapCache(File folder) {
        this.folder = folder;
    }

    /** The bitmap's pixels, to hand to a save on another thread: copying them here keeps the copy's
     *  cost off that thread while the bitmap is still ours to read. */
    static ByteBuffer snapshot(Bitmap bitmap) {
        ByteBuffer pixels = ByteBuffer.allocate(bitmap.getRowBytes() * bitmap.getHeight());
        bitmap.copyPixelsToBuffer(pixels);
        pixels.rewind();
        return pixels;
    }

    /** The entry's body, written after the cache's own magic and the shared version. */
    interface Body {
        void write(DataOutputStream out) throws Exception;
    }

    /** Writes one entry atomically: magic, version, then the body, temp file renamed over the old one. */
    final void writeEntry(File cache, int magic, Body body) {
        File temp = new File(folder, cache.getName() + ".tmp");
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) return;
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp), 1 << 16))) {
                out.writeInt(magic);
                out.writeInt(VERSION);
                body.write(out);
            }
            // Rename over the old entry: a half-written file is never read back.
            if (!temp.renameTo(cache)) {
                cache.delete();
                temp.renameTo(cache);
            }
        } catch (Exception failure) {
            temp.delete();
        }
    }

    /** Whether the stream starts with this cache's magic and version; a mismatched file is deleted. */
    final boolean headerMatches(DataInput in, File cache, int magic) throws Exception {
        if (in.readInt() != magic || in.readInt() != VERSION) {
            cache.delete();
            return false;
        }
        return true;
    }
}

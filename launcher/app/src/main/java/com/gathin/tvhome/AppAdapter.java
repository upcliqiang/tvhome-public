package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order,
 * then empty slots up to a full row, see {@link DockSlots}), then the module cards the owner chose to show
 * (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        void onLibraryRequested();
        void onLibraryFocused();
        /** Hold OK on a tile: the activity shows the tile menu (move, remove from Home, app info). */
        void onTileLongPress(int position, View card);
        void onLibraryUp();
        /** A tile whose launch_actions entry is "input:<id>": switch to that TV input instead of opening an app. */
        void onOpenInput(String inputId);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, EMPTY = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        /** The app's icon: loaded by reload(), or left null there and fetched on first use when the artwork drawn
         *  from it is already on disk (see {@link AppAdapter#iconOf}). */
        Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** uniform_tiles card fill, built from the icon; null until sampled from it. */
        Bitmap fill;
        /** The icon extended sideways to the tile's width (see {@link #wideArt}); null until built. */
        Drawable wide;
        /** True when the icon's left/right edges are plain colour or a slow gradient, so the artwork can be carried
         *  out into the tile's spare width. False for a mark with a rim, a badge or a picture reaching its edge. */
        boolean plainEdge;
        /** The app's package, version and update time: what its generated artwork is keyed by (see
         *  {@link TileArtCache}). Null for a tile that is not an installed app, which is drawn every time. */
        String artStamp;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return itemHeightPx(tileHeightPx, true, density);
    }

    /** The same for either layout: the plain grid (dock_row false) uses the compact spacing. */
    static int itemHeightPx(int tileHeightPx, boolean dockRow, float density) {
        return tileHeightPx + Math.round((dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density);
    }

    /**
     * dock_row false: 4dp padding above and below, a 4dp gap and a 20dp name label, and 16dp kept under the last
     * row. The plain grid fits more rows on the screen, and with item_app.xml's roomier spacing the tiles came
     * out small with wide gaps between them.
     */
    static final float COMPACT_ITEM_EXTRA_DP = 4 + 4 + 4 + 20;
    static final float COMPACT_BOTTOM_ROOM_DP = 16;

    /** item_app.xml: 8dp padding above and below, a 7dp gap and the 22dp name label under the tile. */
    static final float ITEM_EXTRA_DP = 45;
    /** DockDecoration's offsets above and below the dock row. */
    static final float DOCK_EXTRA_DP = 6 + 12;
    /** Room kept under the last row rows_below_dock asks for (MainActivity places the dock with the same). */
    static final float BOTTOM_ROOM_DP = 30;
    /** Room kept above the dock for the dock title (48sp hero name plus its 40dp gap) when dock_title is on. */
    static final float TITLE_ROOM_DP = 104;
    /** dock_row false: room above the first row for its zoom and glass (MainActivity places the grid with it). */
    static final float TOP_ROOM_DP = 12;

    /**
     * Tile width that fits the columns across and, with rows_below_dock > 0, the dock (with its title above it,
     * if shown) plus that many rows down the grid's height; the smaller wins. With rows_below_dock = 0, or before
     * the grid has a height, only the width counts, as before. Without this, 2 or 3 rows below the dock did not
     * fit: the dock was pushed to the top and its title drew over the clock. With dock_row false there is no band
     * or title to make room for: the first row plus rows_below_dock rows fit under a small top margin.
     */
    static int tileWidthPx(int innerWidthPx, int gridHeightPx, int columns, int rowsBelowDock, boolean dockTitle,
                           boolean dockRow, float density) {
        int w = tileWidthPx(innerWidthPx, columns, density);
        if (gridHeightPx <= 0 || rowsBelowDock <= 0) return w;
        float reserved = (dockRow ? DOCK_EXTRA_DP + BOTTOM_ROOM_DP + (dockTitle ? TITLE_ROOM_DP : 0)
                : TOP_ROOM_DP + COMPACT_BOTTOM_ROOM_DP) * density;
        float perItem = (gridHeightPx - reserved) / (rowsBelowDock + 1);
        float tileH = perItem - (dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density;
        int byHeight = Math.round(tileH * DESIGN_W_DP / DESIGN_H_DP);
        // Shrink at most to MIN_HEIGHT_FIT of the width-only size. Below that the tiles look lost in their wide
        // columns (2 rows under a titled dock came out at 80dp); the rows that still don't fit run off the bottom
        // and scroll, and MainActivity keeps the title's room above the dock.
        return Math.max(Math.max(Math.round(80 * density), Math.round(w * MIN_HEIGHT_FIT)), Math.min(w, byHeight));
    }

    /** The smallest share of the width-only tile size that fitting rows_below_dock into the height may go to. */
    static final float MIN_HEIGHT_FIT = 0.85f;

    private final Context ctx;
    private final int rowsBelowDock;
    private final boolean dockTitle;
    /** The owner's saved order (reorder button, Order): key -> rank. Empty when there is none or it no longer applies. */
    private Map<String, Integer> savedRanks = Collections.emptyMap();
    /** Dock size recorded with the saved order (Order.docked), or -1: then dock_apps says which tiles are docked. */
    private int savedDocked = -1;
    /** Reorder mode: no empty dock slots, so every position holds a tile that can move. */
    private boolean reordering;
    private final TileListener listener;
    private final int dockSize;
    /** config dock_row: false makes the dock apps the first tiles of one plain grid (names shown, no empty slots). */
    private final boolean dockRow;
    /** config single_row: tiles sit in one sideways row, each as wide as a grid column would be. */
    private final boolean singleRow;
    /** config always_show_names: the alpha of an unfocused tile's name (0 hides it). */
    private final float restNameAlpha;
    /** cinematic_home: only then does a launch record the last-opened app the shelf uses. */
    private final boolean cinematic;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock). */
    private final boolean fillDock;
    /** Dock apps found at the last reload, and the empty slots after them. */
    private int docked, emptySlots;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Apps pre-seeded into the App library's "added" set (config.xml library_apps); empty by default. */
    private final Set<String> libraryPreload;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showLibrary;
    /** Packages with a leanback entry at the last reload(): their tiles launch with the leanback category. */
    private Set<String> leanbackPackages = new HashSet<>();
    private final boolean showHomeSettings;
    /** The generated tile artwork (fill strip and widened mark) kept on disk, so a start does not draw it again. */
    private final TileArtCache artCache;
    /** Writes cached artwork off the UI thread; one at a time, in the order the tiles were bound. */
    private static final ExecutorService ART_SAVER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tile-art");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** The tile size this Home drew at last start (see {@link #lastGeometry}); its own file, because writing to the
     *  config's shared preferences would fire MainActivity's settings listener. */
    private static final String GEOMETRY_PREFS = "tile_geometry";
    private static final String GEOMETRY_W = "tile_w", GEOMETRY_H = "tile_h";
    private static final String GEOMETRY_ICON_W = "icon_w", GEOMETRY_ICON_H = "icon_h";
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    /** The focus zoom may overshoot (config.xml, focus_overshoot). */
    private final boolean focusOvershoot;
    /** Package to the action its tile sends instead of the start screen (config.xml, launch_actions). */
    private final Map<String, String> launchActions;
    /** Hold OK opens the activity's tile menu (config.xml, tile_menu); false opens App info, as before. */
    private final boolean tileMenu;
    /** Tile names and bundled banners that replace the app's own (config.xml, app_labels and app_banners). */
    private final Map<String, String> appLabels, appBanners;
    /** Every app tile is its icon on a card tinted from that icon, never its banner (config.xml, uniform_tiles). */
    private final boolean uniformTiles;
    /** Packages drawn from their icon and name even when they have a banner (config.xml icon_tiles). */
    private final Set<String> iconTiles;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.fillDock = cfg.fillDock;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.libraryPreload = new HashSet<>(cfg.libraryApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.showLibrary = LibraryApps.showPill(cfg.appLibrary, cfg.libraryButton, cfg.showLibraryPill);
        this.reducedMotion = cfg.reducedMotion;
        this.focusOvershoot = cfg.focusOvershoot;
        this.rowsBelowDock = cfg.rowsBelowDock;
        this.dockTitle = cfg.dockTitle;
        this.dockRow = cfg.dockRow;
        this.singleRow = cfg.singleRow;
        // compact_header puts a photo behind the dock: 75% white names sink into a bright wallpaper, so the
        // Aerial-style layout keeps them nearer full white.
        this.restNameAlpha = cfg.alwaysShowNames ? (cfg.compactHeader ? 0.9f : 0.75f) : 0f;
        this.cinematic = cfg.cinematicHome;
        this.launchActions = cfg.launchActions;
        this.iconTiles = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.icon_tiles)));
        this.tileMenu = cfg.tileMenu;
        this.appLabels = cfg.appLabels;
        this.appBanners = cfg.appBanners;
        this.uniformTiles = cfg.uniformTiles;
        // Only the uniform tile draws generated artwork; every other layout skips the cache entirely.
        this.artCache = cfg.uniformTiles ? new TileArtCache(new File(ctx.getCacheDir(), "tile-art")) : null;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards, List<ResolveInfo> leanback) {
        PackageManager pm = ctx.getPackageManager();
        // The LEANBACK query is shared with the module-card check (MainActivity); copy it before adding
        // library apps so the caller's list stays as it was.
        List<ResolveInfo> found = new ArrayList<>(leanback);
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        leanbackPackages = tvPackages;
        // Copy before merging: LibraryApps.load() may return an immutable empty set (#419).
        Set<String> added = new HashSet<>(LibraryApps.load(ctx));
        added.addAll(libraryPreload);
        Set<String> removed = LibraryApps.loadRemoved(ctx);
        if (!added.isEmpty()) {
            Set<String> seen = new HashSet<>(tvPackages);
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (ri.activityInfo != null && added.contains(ri.activityInfo.packageName)
                        && seen.add(ri.activityInfo.packageName)) found.add(ri);
            }
            // Some preinstalled apps (Mi Home among them) declare their start activity with MAIN but no
            // launcher category, so the query above never returns one and the app could not be put on Home
            // at all. An explicit Home choice still gets its tile: resolve that package's own MAIN activity.
            for (String pkg : added) {
                if (seen.contains(pkg)) continue;
                for (ResolveInfo ri : pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)) {
                    if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                    found.add(ri);
                    seen.add(pkg);
                    break;
                }
            }
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        // The size the last start drew tiles at: lets a tile whose artwork is already on disk skip its icon (below).
        int[] geometry = lastGeometry();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !ri.activityInfo.exported
                    || !LibraryApps.visible(tvPackages.contains(pkg), hidden.contains(pkg), added.contains(pkg), removed.contains(pkg))) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
            // redraw the tile name as something else (#117).
            String label = card != null ? card.label : Cards.cleanLabel(
                    appLabels.containsKey(pkg) ? appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg);
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = null;
            // uniform_tiles draws the icon, so an app's banner is never loaded. A module card keeps its banner.
            if (!(uniformTiles && card == null)) {
                banner = bundledBanner(appBanners.get(pkg));
                if (banner == null) banner = iconTiles.contains(pkg) ? iconBanner(ri.loadIcon(pm)) : loadBanner(pm, ri);
            }
            // The tile's generated artwork is keyed by the app itself: an update replaces its APK, so the file's
            // timestamp and size change and the tile is drawn again rather than keeping the mark it had before.
            ApplicationInfo app = ri.activityInfo.applicationInfo;
            String stamp = null;
            if (app != null && app.sourceDir != null) {
                File apk = new File(app.sourceDir);
                stamp = pkg + '-' + apk.length() + '-' + apk.lastModified();
            }
            // The icon is only drawn (and sampled for the glow) when there is no banner, so a tile with a
            // banner skips loading and decoding it. Nor is it loaded when the artwork drawn from it is already
            // on disk at the size this tile will be bound at: reload() is where a start spent ~150 ms decoding
            // 15 icons that the cached artwork then replaced anyway (iconOf fetches one only if that misses).
            Drawable icon = null;
            if (banner == null) {
                String cached = geometry == null ? null
                        : artKey(stamp, geometry[0], geometry[1], geometry[2], geometry[3]);
                if (cached == null || artCache == null || !artCache.has(cached)) icon = ri.loadIcon(pm);
            }
            Entry entry = card != null
                    ? new Entry(Entry.CARD, label, component, banner, icon, card.caption, card.accent)
                    : new Entry(label, component, banner, icon);
            entry.artStamp = stamp;
            fresh.add(entry);
        }
        Collections.sort(fresh, (a, b) -> {
            // The owner's own order first; tiles it does not know (new installs) follow by the rule below.
            int saved = Order.compare(savedRanks, a.component.flattenToShortString(), b.component.flattenToShortString());
            if (saved != 0) return saved;
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        docked = 0;
        while (docked < fresh.size() && isDocked(fresh.get(docked))) docked++;
        emptySlots = dockRow && !reordering ? DockSlots.empty(docked, dockSize, fresh.size(), fillDock) : 0;
        for (int i = 0; i < emptySlots; i++) {
            fresh.add(docked + i, new Entry(Entry.EMPTY, "", new ComponentName(ctx, "empty-dock-slot-" + i),
                    null, null, null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** icon_tiles: the app's icon centred on a dark banner-shaped tile, for apps whose own banner crops badly. */
    private Drawable iconBanner(Drawable icon) {
        int w = 320, h = 180, side = 104;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(0xFF15202B);
        icon.setBounds((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        icon.draw(canvas);
        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
    }

    /** A banner_* drawable bundled with the launcher, or null for none or a name that is not one. */
    private Drawable bundledBanner(String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    /** Lets go of every tile and its artwork (Home is in the background and memory is short). */
    void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    /**
     * Lets go of every tile's artwork and frees its pixels, for a Home that is going away.
     *
     * A relaunch on this TV keeps the old Home reachable from the renderer, so a bitmap merely dropped here waits
     * for a collection that never comes: the pixels stay in the native heap for the life of the process. Measured
     * at 9.7 MB per settings change before this was called, growing without bound. The icons and banners come from
     * the package manager and are only released, not recycled; the fills are this adapter's own.
     */
    void releaseArtwork() {
        for (Entry e : items) {
            if (e.fill != null) {
                if (!e.fill.isRecycled()) e.fill.recycle();
                e.fill = null;
            }
            e.wide = null;
            e.icon = null;
        }
        items.clear();
    }

    /** Where this tile's artwork is cached: the app's identity plus the size it was drawn at. Null for a tile
     *  that is not an installed app, which has no version to invalidate it by. */
    private static String artKey(Entry e, int fillW, int fillH, int wideW, int wideH) {
        return artKey(e.artStamp, fillW, fillH, wideW, wideH);
    }

    private static String artKey(String stamp, int fillW, int fillH, int wideW, int wideH) {
        if (stamp == null) return null;
        return stamp + '-' + fillW + 'x' + fillH + '-' + wideW + 'x' + wideH;
    }

    /**
     * The tile size the last start drew at, as {tile w, tile h, icon w, icon h}, or null when there is none yet.
     * reload() runs in onCreate, before the grid has been laid out, so this is the only way to know which artwork
     * key the next bind will ask for: with it reload() can tell that the icon is already on disk as artwork.
     */
    private int[] lastGeometry() {
        if (!uniformTiles) return null;
        SharedPreferences prefs = ctx.getSharedPreferences(GEOMETRY_PREFS, Context.MODE_PRIVATE);
        int w = prefs.getInt(GEOMETRY_W, 0), h = prefs.getInt(GEOMETRY_H, 0);
        int iconW = prefs.getInt(GEOMETRY_ICON_W, 0), iconH = prefs.getInt(GEOMETRY_ICON_H, 0);
        return w > 0 && h > 0 && iconW > 0 && iconH > 0 ? new int[]{w, h, iconW, iconH} : null;
    }

    /** Records the size this start draws tiles at, for the next start's {@link #lastGeometry}. */
    private void rememberGeometry(int tileW, int tileH, int iconW, int iconH) {
        if (!uniformTiles || tileW <= 0 || tileH <= 0) return;
        SharedPreferences prefs = ctx.getSharedPreferences(GEOMETRY_PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt(GEOMETRY_W, 0) == tileW && prefs.getInt(GEOMETRY_H, 0) == tileH
                && prefs.getInt(GEOMETRY_ICON_W, 0) == iconW && prefs.getInt(GEOMETRY_ICON_H, 0) == iconH) return;
        prefs.edit().putInt(GEOMETRY_W, tileW).putInt(GEOMETRY_H, tileH)
                .putInt(GEOMETRY_ICON_W, iconW).putInt(GEOMETRY_ICON_H, iconH).apply();
    }

    /**
     * The app's icon. reload() leaves it unset when the artwork drawn from it is already on disk, so a start does
     * not decode 15 icons for nothing; it is fetched here the first time something actually needs it — which is
     * only the tile that had to be drawn again (an updated app, a new tile size, an unreadable cache entry).
     */
    private Drawable iconOf(Entry e) {
        if (e.icon == null && e.kind == Entry.APP) {
            try {
                PackageManager pm = ctx.getPackageManager();
                e.icon = pm.getActivityInfo(e.component, 0).loadIcon(pm);
            } catch (Exception ignored) {
                // Uninstalled since reload(): the tile keeps no icon, as it did before.
            }
        }
        return e.icon;
    }

    /** Copies the two drawn bitmaps and hands them to a background write. A failure only costs the next start. */
    private void saveArt(String key, Entry e) {
        try {
            if (artCache == null || e.fill == null) return;
            Bitmap wide = e.wide instanceof BitmapDrawable ? ((BitmapDrawable) e.wide).getBitmap() : null;
            int fillW = e.fill.getWidth(), fillH = e.fill.getHeight();
            int wideW = wide == null ? 0 : wide.getWidth(), wideH = wide == null ? 0 : wide.getHeight();
            ByteBuffer fillPixels = DiskBitmapCache.snapshot(e.fill);
            ByteBuffer widePixels = wide == null ? null : DiskBitmapCache.snapshot(wide);
            int ambient = e.ambient;
            boolean plain = e.plainEdge;
            ART_SAVER.execute(() -> artCache.save(key, ambient, plain, fillPixels, fillW, fillH,
                    widePixels, wideW, wideH));
        } catch (Exception | OutOfMemoryError ignored) {
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 1) {
            View row = LayoutInflater.from(ctx).inflate(R.layout.item_app_library, parent, false);
            View button = row.findViewById(R.id.btnLibrary);
            button.setOnClickListener(v -> listener.onLibraryRequested());
            button.setOnFocusChangeListener((v, focused) -> { if (focused) listener.onLibraryFocused(); });
            button.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) listener.onLibraryUp();
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            return new RecyclerView.ViewHolder(row) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, parent.getHeight(), dockSize, rowsBelowDock, dockTitle, dockRow, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round((dockRow ? 7 : 4) * density);
        if (!dockRow) {
            // Compact spacing (COMPACT_ITEM_EXTRA_DP): 4dp item padding and a 20dp name instead of 8dp and 22dp.
            nameLp.height = Math.round(20 * density);
            int pad = Math.round(4 * density);
            v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), pad);
        }
        name.setLayoutParams(nameLp);
        // A sideways row would stretch a match_parent item across the whole screen: give it a grid column's width.
        if (singleRow) v.getLayoutParams().width = inner / dockSize;
        if (uniformTiles) {
            // tvOS-style tile: one large mark, no caption inside the card. The mark is drawn over the whole tile
            // (wideArt places it), so that a wide icon can reach the tile's edges.
            View icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            iconLp.width = w;
            iconLp.height = h;
            iconLp.bottomMargin = 0;
            icon.setLayoutParams(iconLp);
            // What the next start's reload() needs to recognise this artwork on disk (lastGeometry).
            rememberGeometry(w, h, iconLp.width, iconLp.height);
        } else if (h < Math.round(DESIGN_H_DP * density)) {
            // Shrunk tiles (e.g. 5 or 6 columns): scale icon and bottom margin proportionally with tile height so the label does not overlap the icon.
            View icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            float scale = (float) h / (DESIGN_H_DP * density);
            iconLp.width = iconLp.height = Math.round(48f * density * scale);
            iconLp.bottomMargin = Math.round(14f * density * scale);
            icon.setLayoutParams(iconLp);
            View label = v.findViewById(R.id.label);
            label.setPadding(label.getPaddingLeft(), label.getPaddingTop(), label.getPaddingRight(), Math.round(4f * density));
        }
        return new Holder(v, listener, animations, advancedEffects, reducedMotion, restNameAlpha, focusOvershoot);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (isLibraryAt(position)) return;
        Holder h = (Holder) holder;
        Entry e = items.get(position);
        if (e.kind == Entry.EMPTY) {
            // A free dock slot keeps its place in the row and the band, but shows nothing and takes no focus.
            h.card.setVisibility(View.INVISIBLE);
            h.card.setFocusable(false);
            h.name.setVisibility(View.INVISIBLE);
            h.banner.setImageDrawable(null);
            h.icon.setImageDrawable(null);
            h.card.setOnClickListener(null);
            h.card.setOnLongClickListener(null);
            return;
        }
        h.card.setVisibility(View.VISIBLE);
        h.card.setFocusable(true);
        h.name.setText(e.name);
        // The dock has no labels, unless always_show_names asks for them (DockDecoration then makes room in the band).
        // In a single row every tile is the dock's kind: the big title above names it.
        boolean dockKind = DockSlots.inDockRow(dockRow, singleRow, position, dockSize);
        h.name.setVisibility(dockKind && restNameAlpha == 0f ? View.INVISIBLE : View.VISIBLE);
        h.inDock = dockKind;
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : restNameAlpha);
        h.card.setScaleX(focused && animations ? focusScale() : 1f);
        h.card.setScaleY(focused && animations ? focusScale() : 1f);
        h.card.setTranslationY(focused && advancedEffects && !dockKind ? -4f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.card.setTranslationZ(focused && advancedEffects ? 8f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
        h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        // A fully opaque banner covers the whole card (centerCrop), so the card's own fill under it is drawn for
        // nothing on every frame; keep it under banners with transparent parts and under icon tiles.
        h.card.setBackground(e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? null : h.cardBackground);
        // The uniform tile's artwork: drawn from the icon once, then read back from disk on later starts. An icon
        // that is re-rasterised on every draw makes a single tile cost 90 ms, so drawing it again each start is
        // what kept Home's first frame late (see TileArtCache).
        if (uniformTiles && e.banner == null) {
            int tileW = h.card.getLayoutParams().width, tileH = h.card.getLayoutParams().height;
            ViewGroup.LayoutParams iconLp = h.icon.getLayoutParams();
            if (e.fill == null) {
                String key = artKey(e, tileW, tileH, iconLp.width, iconLp.height);
                TileArtCache.Art art = key == null ? null : artCache.load(key);
                if (art != null) {
                    e.plainEdge = art.plainEdge;
                    e.ambient = art.ambient;
                    e.fill = art.fill;
                    e.wide = art.wide;
                } else {
                    // A miss is the only place the icon is needed: reload() left it unloaded when it expected
                    // this hit, and an updated app or a new tile size really does have to be drawn again.
                    Drawable icon = iconOf(e);
                    if (icon != null) {
                        e.plainEdge = plainEdge(icon);
                        e.fill = backdrop(icon, ambientColorAt(position), tileW, tileH, e.plainEdge);
                        e.wide = wideArt(icon, iconLp.width, iconLp.height, e.plainEdge);
                        if (key != null) saveArt(key, e);
                    }
                }
            }
            if (e.fill != null) h.card.setBackground(new StripFill(e.fill));
        }
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            // uniform_tiles: the mark is drawn at the tile's full width, its own edges mirrored out into the
            // spare room, so a square icon reads as a wide tile rather than a square sitting on one.
            if (uniformTiles) {
                h.icon.setImageDrawable(e.wide != null ? e.wide : iconOf(e));
            } else {
                h.icon.setImageDrawable(iconOf(e));
            }
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(uniformTiles ? View.GONE : View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (tileMenu) {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onTileLongPress(pos, v);
                return true;
            }
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    @SuppressWarnings("deprecation")
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // TV apps launch with the same leanback intent as from the stock launcher or the remote, so an app
        // already running resumes instead of restarting; only phone apps added from the library use plain MAIN.
        if (leanbackPackages.contains(e.pkg)) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String action = launchActions.get(e.pkg);
        String inputId = LaunchActions.inputId(action);
        if (inputId != null) {
            listener.onOpenInput(inputId);
            return;
        }
        if (action != null) {
            // NEW_TASK only: with RESET_TASK_IF_NEEDED, Android just brings a running task of the app to the front
            // ("Activity not started"), so the screen never opens while the app is up, e.g. MagentaTV's boot-time task.
            Intent byAction = LaunchActions.intent(e.pkg, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
        }
        try {
            ctx.startActivity(i);
            if (cinematic) LastOpened.record(ctx, e.pkg);
            if (advancedEffects && !reducedMotion && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + (showLibrary ? 1 : 0);
    }

    int appCount() { return items.size(); }

    boolean isLibraryAt(int position) { return showLibrary && position == items.size(); }

    @Override public int getItemViewType(int position) { return isLibraryAt(position) ? 1 : 0; }

    boolean containsPackage(String pkg) {
        for (Entry entry : items) if (entry.pkg.equals(pkg)) return true;
        return false;
    }

    @Override
    public long getItemId(int position) {
        return isLibraryAt(position) ? Long.MIN_VALUE : items.get(position).component.flattenToShortString().hashCode();
    }

    /** Uses this saved order and dock size from the next reload() (Order.ranks, Order.docked). */
    void setSavedOrder(Map<String, Integer> ranks, int docked) {
        savedRanks = ranks;
        savedDocked = docked;
    }

    /**
     * A tile in the dock: one of the first savedDocked tiles of the saved order when one was recorded, so an app
     * the owner moved into the dock stays there (#499); else a dock_apps entry.
     */
    private boolean isDocked(Entry e) {
        if (savedDocked < 0) return favourites.contains(e.pkg);
        Integer rank = savedRanks.get(e.component.flattenToShortString());
        return rank != null && rank < savedDocked;
    }

    /** Reorder mode on or off; takes effect at the next reload() (empty dock slots are left out while on). */
    void setReordering(boolean on) {
        reordering = on;
    }

    boolean hasEmptySlots() {
        return emptySlots > 0;
    }

    /** Tiles that can be moved: everything but the "Default home" tile, which always stays last. */
    int movableCount() {
        int n = items.size();
        return n > 0 && items.get(n - 1).kind == Entry.HOME_SETTINGS ? n - 1 : n;
    }

    /** Keys of the movable tiles in their current order, for Order (empty dock slots are not tiles). */
    List<String> movableKeys() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < movableCount(); i++) {
            Entry e = items.get(i);
            if (e.kind != Entry.EMPTY) keys.add(e.component.flattenToShortString());
        }
        return keys;
    }

    /** Moves one tile; the others shift by one. Stable ids keep the moved tile's view (and its focus). */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= movableCount() || to >= movableCount()) return;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        // The dock row hides names by position: rebind the tiles whose side of the dock edge changed.
        if (from < dockSize || to < dockSize) {
            int lo = Math.min(from, to), hi = Math.max(from, to);
            notifyItemRangeChanged(lo, hi - lo + 1);
        }
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** Dock slots that hold an app: the whole dock unless some slots are empty. */
    int dockFilled() {
        return emptySlots > 0 ? docked : dockSize;
    }

    /** A position focus can land on: an empty dock slot gives the last dock app. */
    int focusablePosition(int position) {
        return DockSlots.focusable(position, docked, emptySlots);
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** The tile's artwork, its banner or else its icon, for the cinematic shelf's backdrop (HeroArt). */
    Drawable artworkAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.banner != null ? e.banner : iconOf(e);
    }

    float focusScale() {
        return !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            e.ambient = uniformTiles && e.banner == null ? dominantColor(iconOf(e)) : vividColor(e.banner != null ? e.banner : iconOf(e));
        }
        return e.ambient;
    }

    /** Share of the tile height the icon takes in uniform_tiles mode (onCreateViewHolder). */
    private static final float ICON_SHARE = 0.62f;

    /**
     * The colour covering most of an icon, for a card that should read as that app: a small saturated badge
     * (a red "new" corner on a blue icon) must not win, as it can with {@link #vividColor}. Grey if none.
     */
    private static int dominantColor(Drawable live) {
        if (live == null) return 0xFF6E6E73;
        int n = 24;
        Bitmap bmp = rasterise(live, n, n);
        float[] weight = new float[12];
        float[][] sum = new float[12][3];
        float[] hsv = new float[3];
        float totalWeight = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;   // white, grey and black carry no hue
                int bin = (int) (hsv[0] / 30f) % 12;
                float w = hsv[1] * hsv[2];
                weight[bin] += w;
                totalWeight += w;
                sum[bin][0] += Color.red(p) * w;
                sum[bin][1] += Color.green(p) * w;
                sum[bin][2] += Color.blue(p) * w;
            }
        }
        bmp.recycle();
        if (totalWeight < n * n * 0.03f) return 0xFF6E6E73;   // a monochrome icon, like a grey gear
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < n * n * 0.03f) {
            // Multicolored icon without a single dominant hue: pick its most vivid colour
            return vividColor(live);
        }
        float w = weight[best];
        return Color.rgb((int) (sum[best][0] / w), (int) (sum[best][1] / w), (int) (sum[best][2] / w));
    }

    /**
     * The uniform tile's fill: a strip the card stretches to its size. Where the icon sits on a plate of its own
     * (a white or coloured rounded square), the card takes that plate's colour row for row and side for side: what
     * the mark shows at a given height on its left edge is what the card shows to the left of it at that height, and
     * likewise on the right. When the icon is a picture that runs off its own square (sky, cloud, hillside) its edge
     * is also carried out sideways, see {@link #carryOut}, so what the square cuts off keeps going instead of ending
     * in a flat band. Otherwise a gradient of the icon's main colour, deepened so the mark stands out.
     */
    private static Bitmap backdrop(Drawable live, int fallback, int w, int h, boolean plain) {
        int[][] plate = iconPlate(live);
        if (plate == null) return strip(fallbackFill(fallback));
        int[] left = plate[0], right = plate[1];
        int first = plate[2][0], last = plate[2][1];
        int[] box = artBox(live, w, h);
        int x0 = Math.max(0, (w - box[0]) / 2), x1 = Math.min(w, x0 + box[0]);
        // Beyond the mark the card eases towards a smoothed reading of its edges: where the artwork is cut off
        // by its own square (a cloud bank reaching the edge), carrying that row's colour straight out would draw
        // a hard band across the tile. Easing it away keeps the edge itself exact.
        int fade = Math.max(1, Math.round(box[0] * 0.15f));
        int[] leftSoft = boxBlur(left, first, last, 10), rightSoft = boxBlur(right, first, last, 10);
        float margin = (1f - ICON_SHARE) / 2f;
        float yTop = margin + ICON_SHARE * first / (float) PLATE_N;
        float yBottom = margin + ICON_SHARE * (last + 1) / (float) PLATE_N;
        float span = Math.max(0.02f, yBottom - yTop);
        Bitmap bmp = Bitmap.createBitmap(w, FILL_ROWS, Bitmap.Config.ARGB_8888);
        int[] row = new int[w];
        for (int i = 0; i < FILL_ROWS; i++) {
            // Where this row of the card falls inside the mark: 0 at its first row, 1 at its last, clamped
            // outside so the card keeps the mark's edge colour right where the two meet.
            float t = (i / (float) (FILL_ROWS - 1) - yTop) / span;
            t = Math.max(0f, Math.min(1f, t));
            float f = first + t * (last - first);
            int i0 = (int) f, i1 = Math.min(last, i0 + 1);
            int lc = mix(left[i0], left[i1], f - i0), rc = mix(right[i0], right[i1], f - i0);
            int ls = mix(leftSoft[i0], leftSoft[i1], f - i0), rs = mix(rightSoft[i0], rightSoft[i1], f - i0);
            for (int x = 0; x < w; x++) {
                if (x < x0) {
                    row[x] = mix(lc, ls, Math.min(1f, (x0 - x) / (float) fade));
                } else if (x > x1) {
                    row[x] = mix(rc, rs, Math.min(1f, (x - x1) / (float) fade));
                } else {
                    // Across the mark's own width the card grades from its left edge colour to its right one:
                    // the mark covers this strip, so it only has to keep both edges exact.
                    row[x] = mix(lc, rc, (x - x0) / (float) Math.max(1, x1 - x0));
                }
            }
            bmp.setPixels(row, 0, w, 0, i, w, 1);
        }
        if (plain) carryOut(bmp, live, box, w, h);
        return bmp;
    }

    /** Share of the spare width each carried-out band takes from the mark's own edge: more on the left, where the
     *  artwork is usually free, less on the right, where a badge or a sun tends to sit. */
    private static final float CARRY_SHARE_LEFT = 0.80f;
    private static final float CARRY_SHARE_RIGHT = 0.40f;

    /**
     * Carries the artwork out past its own square: a narrow strip of the mark's edge, magnified sideways, fills the
     * room between the mark and the tile's sides. The strip's seam-side column is the mark's edge column itself, so
     * a cloud that leaves the square keeps going instead of stopping dead and leaving a flat band, and the join is
     * exact. The strip is kept narrow enough not to reach a sun or a badge.
     */
    private static void carryOut(Bitmap fill, Drawable live, int[] box, int w, int h) {
        int x0 = (w - box[0]) / 2, x1 = x0 + box[0];
        int left = Math.max(0, x0), right = Math.max(0, w - Math.min(w, x1));
        if (left == 0 && right == 0) return;
        Bitmap art = Bitmap.createBitmap(box[0], box[1], Bitmap.Config.ARGB_8888);
        drawAt(new Canvas(art), live, 0, 0, box[0], box[1]);
        Canvas c = new Canvas(fill);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (left > 0) {
            int cap = Math.max(6, Math.round(box[0] * 0.35f));
            int src = Math.max(6, Math.min(Math.round(left * CARRY_SHARE_LEFT), cap));
            mirrorInto(c, art, new Rect(0, 0, src, box[1]), x0 - left, x0, paint);
        }
        if (right > 0) {
            int cap = Math.max(6, Math.round(box[0] * 0.17f));
            int src = Math.max(6, Math.min(Math.round(right * CARRY_SHARE_RIGHT), cap));
            mirrorInto(c, art, new Rect(box[0] - src, 0, box[0], box[1]), x1, x1 + right, paint);
        }
        art.recycle();
    }

    /** Draws a slice of the artwork mirrored into [x0, x1), its outer column against the mark's edge. */
    private static void mirrorInto(Canvas c, Bitmap art, Rect src, int x0, int x1, Paint paint) {
        c.save();
        c.translate(x0 + x1, 0);
        c.scale(-1, 1);
        c.drawBitmap(art, src, new Rect(x0, 0, x1, FILL_ROWS), paint);
        c.restore();
    }

    /** Rows the card's fill is built from before it is stretched to the tile's height. */
    private static final int FILL_ROWS = 64;

    /** The fill for an icon with no plate: its main colour, deepened so a white mark still reads. */
    private static int[] fallbackFill(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // Yellow darkened turns olive: lean it to amber instead.
        if (hsv[0] > 42f && hsv[0] < 70f && hsv[1] > 0.3f) hsv[0] = 36f;
        hsv[2] = Math.min(hsv[2], 0.62f);
        int top = Color.HSVToColor(hsv);
        hsv[2] *= 0.6f;
        int bottom = Color.HSVToColor(hsv);
        int[] fill = new int[FILL_ROWS];
        for (int i = 0; i < FILL_ROWS; i++) fill[i] = mix(top, bottom, i / (float) (FILL_ROWS - 1));
        return fill;
    }

    private static Bitmap strip(int[] rows) {
        Bitmap bmp = Bitmap.createBitmap(1, rows.length, Bitmap.Config.ARGB_8888);
        bmp.setPixels(rows, 0, 1, 0, 0, 1, rows.length);
        return bmp;
    }

    /**
     * Where the mark sits inside a tile of w by h, keeping its own shape: {width, height}. An icon about as wide as
     * the tile (the player's film strip) is drawn large enough to cover it and the overflow is cropped, so its
     * plate, holes and border run to the tile's edges as one wide picture. A square icon is drawn at the tile's
     * height and the card's fill carries its plate out to the sides.
     */
    private static int[] artBox(Drawable icon, int w, int h) {
        int iw = icon == null ? 0 : icon.getIntrinsicWidth();
        int ih = icon == null ? 0 : icon.getIntrinsicHeight();
        float aspect = iw > 0 && ih > 0 ? iw / (float) ih : 1f;
        if (aspect >= w / (float) h * 0.8f) {
            return new int[]{Math.max(w, Math.round(h * aspect)), Math.max(h, Math.round(w / aspect))};
        }
        return new int[]{Math.max(1, Math.round(h * aspect)), h};
    }

    /**
     * The mark drawn across the tile. It keeps the shape it has in its own app — a square icon stays a square, a
     * wide one stays wide (they used to be squeezed into a square, which is what made the wide ones look wrong) —
     * and the card's fill continues its artwork out to the tile's sides (see {@link #carryOut}). A mark on a plain
     * plate has no artwork to continue, so its edge is feathered into the fill instead.
     */
    private static Drawable wideArt(Drawable icon, int w, int h, boolean plain) {
        if (icon == null || w <= 0 || h <= 0) return null;
        int[] box = artBox(icon, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        int x0 = (w - box[0]) / 2, y0 = (h - box[1]) / 2;
        if (box[0] < w && !plain) {
            // The mark stops short of the tile's sides, and the plate it cuts off at its own edge would end in a
            // hard line: feather that edge into the fill so the plate simply carries on. A picture needs no feather:
            // the fill continues its own artwork, and the seam column is the mark's edge column, so there is no edge.
            int fade = Math.max(1, Math.round(box[0] * 0.10f));
            Bitmap art = Bitmap.createBitmap(box[0], box[1], Bitmap.Config.ARGB_8888);
            Canvas ac = new Canvas(art);
            drawAt(ac, icon, 0, 0, box[0], box[1]);
            Paint paint = new Paint();
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            paint.setShader(new LinearGradient(0, 0, fade, 0, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
            ac.drawRect(0, 0, fade, box[1], paint);
            paint.setShader(new LinearGradient(box[0], 0, box[0] - fade, 0, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
            ac.drawRect(box[0] - fade, 0, box[0], box[1], paint);
            canvas.drawBitmap(art, x0, y0, null);
            art.recycle();
        } else {
            drawAt(canvas, icon, x0, y0, box[0], box[1]);
        }
        return new BitmapDrawable(null, bmp);
    }

    /** Draws a private copy of the drawable into the given box, leaving the tile's own copy untouched. */
    private static void drawAt(Canvas canvas, Drawable icon, int x, int y, int w, int h) {
        Drawable d = privateCopy(icon);
        Rect saved = new Rect(d.getBounds());
        d.setBounds(x, y, x + w, y + h);
        d.draw(canvas);
        d.setBounds(saved);
    }

    /** A private copy of the drawable: mutating or rebinding it never touches the tile's own instance. */
    static Drawable privateCopy(Drawable live) {
        return live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
    }

    /** The drawable rasterised fresh into a w×h ARGB bitmap, the tile's own copy and bounds untouched. */
    static Bitmap rasterise(Drawable live, int w, int h) {
        Drawable d = privateCopy(live);
        Rect saved = new Rect(d.getBounds());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        return bmp;
    }

    /**
     * Paints the strip stretched over the card. A BitmapDrawable tiles a strip this narrow instead of stretching
     * it, which showed as bands across every tile.
     */
    private static final class StripFill extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src;
        private final Rect dst = new Rect();

        StripFill(Bitmap bitmap) {
            this.bitmap = bitmap;
            this.src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        @Override public void draw(Canvas canvas) {
            dst.set(getBounds());
            canvas.drawBitmap(bitmap, src, dst, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }

        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** Rows the icon is sampled at, and the square it is drawn into. */
    private static final int PLATE_N = 64;

    /** The same rows averaged over a window: the plate's drift kept, its abrupt steps (a cut cloud) eased. */
    private static int[] boxBlur(int[] rows, int first, int last, int radius) {
        int[] out = rows.clone();
        for (int y = first; y <= last; y++) {
            int r = 0, g = 0, b = 0, count = 0;
            for (int yy = Math.max(first, y - radius); yy <= Math.min(last, y + radius); yy++) {
                r += Color.red(rows[yy]);
                g += Color.green(rows[yy]);
                b += Color.blue(rows[yy]);
                count++;
            }
            out[y] = Color.rgb(r / count, g / count, b / count);
        }
        return out;
    }

    /**
     * The icon's plate, row by row, read just inside its left and right edges: {left colour per row, right colour
     * per row, {first row, last row} that is opaque}. The edges rather than the middle, because the middle is the
     * artwork, and the average of a whole row would drag the mark's own white bands across the card. Null when the
     * icon is mostly transparency (a bare mark), or when its plate is so small that the card would be filled with
     * the mark's colours instead.
     */
    private static int[][] iconPlate(Drawable live) {
        if (live == null) return null;
        int n = PLATE_N;
        Bitmap bmp = rasterise(live, n, n);
        int[] left = new int[n], right = new int[n];
        int known = 0, first = -1, last = -1;
        for (int y = 0; y < n; y++) {
            int from = -1, to = -1;
            for (int x = 0; x < n; x++) if (Color.alpha(bmp.getPixel(x, y)) >= 200) { from = x; break; }
            if (from < 0) continue;
            for (int x = n - 1; x >= 0; x--) if (Color.alpha(bmp.getPixel(x, y)) >= 200) { to = x; break; }
            // A rounded corner, or a row that only clips a passing mark: no plate to read there.
            if (to - from < n * 0.35f) continue;
            int a = edgeColor(bmp, from, y, n, 1), b = edgeColor(bmp, to, y, n, -1);
            if (a == 0 || b == 0) continue;
            left[y] = a;
            right[y] = b;
            known++;
            if (first < 0) first = y;
            last = y;
        }
        bmp.recycle();
        if (first < 0 || known < n * 0.6f) return null;
        // Rows next to a rounded corner are unreadable: lend them the nearest row that was, so the fill never
        // breaks colour where the mark starts.
        for (int y = first + 1; y <= last; y++) {
            if (left[y] == 0) left[y] = left[y - 1];
            if (right[y] == 0) right[y] = right[y - 1];
        }
        return new int[][]{smoothRows(left, first, last), smoothRows(right, first, last), {first, last}};
    }

    /**
     * Whether the artwork can be carried out into the tile's spare width: true for a photo-like icon whose sides are
     * sky, cloud or hillside, false for a mark on a plain plate, which usually has a rim or a badge at its edge that
     * magnified sideways would draw a stripe. Measured as how hard the colour down each side steps: a boundary
     * crossing the edge steps some forty levels, a gradient or a mark floating on a plate a handful.
     */
    private static boolean plainEdge(Drawable icon) {
        int[] step = edgeStep(icon);
        return (step[0] + step[1]) / 2 >= 25;
    }

    /**
     * How sharply the colour down each side steps, as the largest jump between neighbouring rows. A picture cut off
     * by its own square (a cloud bank, a hillside meeting the edge) steps hard; a gradient across a plate, or a mark
     * floating on it, drifts by a few levels at a time and never steps.
     */
    private static int[] edgeStep(Drawable live) {
        if (live == null) return new int[]{0, 0};
        int n = PLATE_N;
        Bitmap bmp = rasterise(live, n, n);
        int[] left = new int[n], right = new int[n];
        for (int y = 0; y < n; y++) {
            int from = -1, to = -1;
            for (int x = 0; x < n; x++) if (Color.alpha(bmp.getPixel(x, y)) >= 200) { from = x; break; }
            if (from < 0) continue;
            for (int x = n - 1; x >= 0; x--) if (Color.alpha(bmp.getPixel(x, y)) >= 200) { to = x; break; }
            if (to - from < n * 0.35f) continue;
            left[y] = edgeColor(bmp, from, y, n, 1);
            right[y] = edgeColor(bmp, to, y, n, -1);
        }
        bmp.recycle();
        return new int[]{step(left), step(right)};
    }

    /** The largest colour distance between two rows that touch, so a soft boundary still counts as one step. */
    private static int step(int[] rows) {
        int best = 0;
        for (int i = 1; i < rows.length; i++) {
            if (rows[i] == 0 || rows[i - 1] == 0) continue;
            int d = Math.abs(Color.red(rows[i]) - Color.red(rows[i - 1]))
                    + Math.abs(Color.green(rows[i]) - Color.green(rows[i - 1]))
                    + Math.abs(Color.blue(rows[i]) - Color.blue(rows[i - 1]));
            best = Math.max(best, d);
        }
        return best;
    }

    /** The plate colour just inside an edge: a few pixels inward, so a rim or an anti-aliased corner is skipped. */
    private static int edgeColor(Bitmap bmp, int x, int y, int n, int step) {
        int r = 0, g = 0, b = 0, count = 0;
        for (int i = 0; i < 3; i++) {
            int xx = x + step * i;
            if (xx < 0 || xx >= n) break;
            int p = bmp.getPixel(xx, y);
            if (Color.alpha(p) < 200) continue;
            r += Color.red(p);
            g += Color.green(p);
            b += Color.blue(p);
            count++;
        }
        return count == 0 ? 0 : Color.rgb(r / count, g / count, b / count);
    }

    /**
     * A five-row median over the sampled rows: wide enough to rub out a badge or a film hole that reaches the
     * plate's edge, narrow enough to keep the plate's own gradient.
     */
    private static int[] smoothRows(int[] rows, int first, int last) {
        int[] out = rows.clone();
        int[] window = new int[5];
        for (int y = first; y <= last; y++) {
            int count = 0;
            for (int dy = -2; dy <= 2; dy++) {
                int yy = y + dy;
                if (yy < first || yy > last) continue;
                window[count++] = rows[yy];
            }
            out[y] = median(window, count);
        }
        return out;
    }

    private static int median(int[] values, int count) {        for (int i = 1; i < count; i++) {
            int v = values[i], j = i - 1;
            while (j >= 0 && luma(values[j]) > luma(v)) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = v;
        }
        return values[count / 2];
    }

    private static int luma(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }

    /** a + (b - a) * t per channel, clamped; t outside 0..1 extrapolates. */
    private static int mix(int a, int b, float t) {
        return Color.rgb(clamp(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                clamp(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                clamp(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        int n = 12;
        Bitmap bmp = rasterise(live, n, n);
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        bmp.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            int max = Math.max(pr, Math.max(pg, pb));
            int min = Math.min(pr, Math.min(pg, pb));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            float val = max / 255f;
            float score = sat * Math.min(1f, val * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: the dark card behind an icon tile, or behind a banner that has transparent parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;
        /** Alpha of the name while the tile is not focused (always_show_names). */
        final float restNameAlpha;
        final boolean overshoot;
        /** A dock tile: it grows in place without advanced_effects' lift, which reads as off-centre in the band (#2135). */
        boolean inDock;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion, float restNameAlpha, boolean overshoot) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;
            this.restNameAlpha = restNameAlpha;
            this.overshoot = overshoot;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
                float s = focused ? scale : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : restNameAlpha);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    // A short zoom with no hardware layer: building a layer for so short an animation costs more
                    // frames than it saves. The name just appears instead of fading.
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects && !inDock ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(focused ? Motion.REDUCED_MS : 60)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    name.setAlpha(focused ? 1f : restNameAlpha);
                    // Same place as the full motion's slide ends: the layout's -6dp is only its starting point, and
                    // left there the name overlaps the zoomed tile.
                    // Only on focus: snapping it back to -6dp while it fades out would jump it up over the tile.
                    if (focused) name.setTranslationY(density * 4f);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects && !inDock ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                            .setInterpolator(focused && !overshoot ? new DecelerateInterpolator(1.5f)
                                    : new OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                            .withLayer().start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(280).start();
                    }
                    name.animate().alpha(focused ? 1f : restNameAlpha)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}

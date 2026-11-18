package com.firecasa.home;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.os.Build;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.WindowManager;

import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The whole home screen as one view. MainActivity shows it as an app; HomeService shows the same view
// as an overlay the instant Amazon's home screen opens, because Fire OS holds app launches back for
// 5 seconds after the Home button.
public class HomeScreen extends FrameLayout {
    // The dock when nothing is saved yet: these, in order, if installed, topped up to six with other apps.
    static final String[] DEFAULT_DOCK = {
        "com.netflix.ninja",
        "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
        "com.disney.disneyplus",
        "com.hulu.plus", "com.hulu.livingroomplus",
        "com.crunchyroll.crunchyroid",
        "com.amazon.firebat", "com.amazon.amazonvideo.livingroom",
        "com.wbd.stream", "com.hbo.hbonow",
        "com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv",
        "com.cbs.ott", "com.plexapp.android", "com.spotify.tv.android",
    };

    // White logos in assets/logos. Apps without one show their own icon in grey.
    static final Map<String, String> LOGOS = new HashMap<>();
    static {
        String[][] m = {
            {"com.netflix.ninja", "netflix"},
            {"com.amazon.firebat", "primevideo"}, {"com.amazon.amazonvideo.livingroom", "primevideo"},
            {"com.amazon.firetv.youtube", "youtube"}, {"com.google.android.youtube.tv", "youtube"},
            {"com.amazon.firetv.youtube.tv", "youtube"}, {"com.google.android.youtube.tvunplugged", "youtube"},
            {"com.crunchyroll.crunchyroid", "crunchyroll"},
            {"com.wbd.stream", "max"}, {"com.hbo.hbonow", "hbo"},
            {"com.apple.atve.amazon.appletv", "appletv"}, {"com.apple.atve.androidtv.appletv", "appletv"},
            {"com.cbs.ott", "paramountplus"}, {"com.plexapp.android", "plex"},
            {"com.spotify.tv.android", "spotify"},
            {"tv.twitch.android.viewer", "twitch"}, {"tv.twitch.android.app", "twitch"},
            {"com.tubitv", "tubi"}, {"com.tubitv.ott", "tubi"},
            {"org.jellyfin.androidtv", "jellyfin"}, {"org.xbmc.kodi", "kodi"},
        };
        for (String[] e : m) LOGOS.put(e[0], e[1]);
    }

    // Other home screens, and Fire OS internals that list themselves as apps, never show up.
    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
        HomeService.AMAZON_HOME, "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver", "com.amazon.ssm",
        "com.amazon.vizzini.ftvcds", "com.android.providers.downloads.ui"));

    // Bundled wallpapers (assets/wallpapers) and the credit shown when one is picked.
    static final String[][] WALLPAPERS = {
        {"aurora", "Aurora over Antarctica  ·  ISS Expedition 40, NASA"},
        {"citylights", "City lights under an aurora  ·  ISS Expedition 58, NASA"},
        {"earthrise", "Earthrise  ·  Apollo 17, NASA"},
        {"southernlights", "Aurora over the South Pacific  ·  ISS Expedition 67, NASA"},
        {"sunrise", "Sunrise over the horizon  ·  ISS Expedition 40, NASA"},
    };
    static final String CUSTOM = "custom";

    final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    final Map<String, Bitmap> logoCache = new HashMap<>();
    final List<ViewGroup> layers = new ArrayList<>();

    SharedPreferences prefs;
    PackageManager pm;
    int screenW, screenH;
    float s;
    Typeface medium, semibold;
    FrameLayout root, base;
    ImageView wallView;
    ScrollView rows;
    LinearLayout rowsPage, dockRow, appsRow, shelfBox;
    final Map<LinearLayout, View> rowTops = new HashMap<>(); // each row's title, or its scroller if untitled
    final List<LinearLayout> shelfRows = new ArrayList<>();
    TextView caption, clock, dateLine, hero, credit;
    LinearLayout clockBox; // the clock with the date under it, moved as one
    boolean newPhoto; // the wallpaper button asked for another Unsplash photo
    int seenGen = -1; // the row set this screen last showed (see shelvesGen)
    String seenRecent = "";
    String seenFolders = "";
    String seenActivity = "";
    final Map<LinearLayout, Shelf> loopShelves = new HashMap<>(); // the rows that loop, and the titles in each
    String movingPkg; // a dock app being moved: Left and Right shift it, OK places it, Back puts it back
    int moveFrom; // where the moved app started, to put focus back on it if the move is cancelled
    ObjectAnimator drift; // the wallpaper's slow pan and zoom

    // TextClock stops ticking in an overlay window, so the time is kept here: updated on the minute.
    final Runnable tick = new Runnable() {
        @Override public void run() {
            java.util.Date now = new java.util.Date();
            boolean h24 = prefs.getBoolean("clock24", android.text.format.DateFormat.is24HourFormat(ctx));
            if (h24) clock.setText(android.text.format.DateFormat.format("H:mm", now));
            else { // "AM" or "PM" at 40% of the time's size, on the same baseline
                String t = android.text.format.DateFormat.format("h:mm", now).toString();
                String ap = android.text.format.DateFormat.format("a", now).toString();
                android.text.SpannableString text = new android.text.SpannableString(t + " " + ap);
                text.setSpan(new android.text.style.RelativeSizeSpan(0.4f), t.length() + 1, text.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                clock.setText(text);
            }
            dateLine.setText(android.text.format.DateFormat.format("EEEE, MMM d", now));
            dateLine.setVisibility(prefs.getBoolean("clockDate", true) ? View.VISIBLE : View.GONE);
            removeCallbacks(this);
            postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    Bitmap blur;
    List<String> dock = new ArrayList<>();
    boolean appsChanged;
    String shownWallpaper;
    static int dockVersion;
    static int lastDock; // the dock app last focused, in either copy
    int seenDockVersion = -1;
    static int photoVersion; // bumped when firecasa.sh wallpaper sends a new photo
    int seenPhotoVersion;

    // The wallpaper and its blur, shared by the app and the overlay so they're decoded once.
    static String cachedKey;
    static Bitmap cachedPhoto, cachedBlur;

    final BroadcastReceiver timeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { tick.run(); }
    };

    final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsChanged = true;
            if (hasWindowFocus() && layers.isEmpty()) buildDock();
        }
    };

    final Context ctx;

    HomeScreen(Context c) {
        super(c);
        ctx = c;
        prefs = ctx.getSharedPreferences("firecasa", Context.MODE_PRIVATE);
        pm = ctx.getPackageManager();
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        s = screenW / 1920f; // laid out at 1920x1080, scaled to the real screen
        medium = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-Medium.ttf");
        semibold = Typeface.createFromAsset(ctx.getAssets(), "fonts/Inter-SemiBold.ttf");

        root = this;
        root.setBackgroundColor(0xFF000000);
        wallView = new ImageView(ctx);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallView, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x66000000, 0}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, px(300)));

        // The moving background: a slow zoom and drift, looping. Paused while menus are open or the overlay is hidden.
        drift = ObjectAnimator.ofPropertyValuesHolder(wallView,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -px(24)));
        drift.setDuration(40000);
        drift.setInterpolator(new LinearInterpolator());
        drift.setRepeatCount(ObjectAnimator.INFINITE);
        drift.setRepeatMode(ObjectAnimator.REVERSE);

        base = new FrameLayout(ctx);
        root.addView(base, new FrameLayout.LayoutParams(-1, -1));

        clockBox = new LinearLayout(ctx);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clock = new TextView(ctx);
        clock.setShadowLayer(px(16), 0, px(2), 0x40000000);
        clockBox.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        dateLine = new TextView(ctx);
        dateLine.setAlpha(0.85f);
        dateLine.setShadowLayer(px(10), 0, px(1), 0x80000000);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-1, -2);
        dateLp.topMargin = px(8);
        clockBox.addView(dateLine, dateLp);
        base.addView(clockBox, new FrameLayout.LayoutParams(-2, -2));

        // Top right: wallpaper, all apps, settings.
        View first = addIcon("image", 1920 - 140 - 76 * 4 - 24 * 3, new View.OnClickListener() {
            @Override public void onClick(View v) { nextWallpaper(); }
        });
        addIcon("grid_view", 1920 - 140 - 76 * 3 - 24 * 2, new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(v); }
        });
        addIcon("settings", 1920 - 140 - 76 * 2 - 24, new View.OnClickListener() {
            @Override public void onClick(View v) { openSettingsMenu(v); }
        });
        View last = addIconBitmap(powerIcon(), 1920 - 140 - 76, v -> turnOff());
        // Left and Right stay in this row instead of dropping down to the dock.
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());

        caption = new TextView(ctx);
        style(caption, 22, medium);
        caption.setAlpha(0);
        caption.setShadowLayer(px(10), 0, px(1), 0x80000000);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        clp.topMargin = px(158);
        clp.rightMargin = px(140);
        base.addView(caption, clp);

        // Apple TV style: the wallpaper is the top shelf, with the focused app's name over it. Below,
        // rows of app cards slide sideways. Moving down to another row slides the whole page up.
        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xB3000000, 0}));
        root.addView(shade, 1, new FrameLayout.LayoutParams(-1, px(640), Gravity.BOTTOM));

        rows = new ScrollView(ctx) {
            // Rows scroll into place in rowFocused(), not by the minimum ScrollView would pick.
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
        };
        rows.setFocusable(false);
        rows.setVerticalScrollBarEnabled(false);
        rows.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rows.setClipChildren(false);
        LinearLayout page = rowsPage = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        rows.addView(page, new FrameLayout.LayoutParams(-1, -2));

        hero = new TextView(ctx);
        style(hero, 72, semibold);
        hero.setSingleLine();
        hero.setEllipsize(TextUtils.TruncateAt.END);
        hero.setShadowLayer(px(20), 0, px(2), 0x66000000);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(px(1640), -2);
        hlp.leftMargin = px(140);
        hlp.topMargin = px(462);
        page.addView(hero, hlp);

        dockRow = addRow(page, null);
        appsRow = addRow(page, "Apps");
        shelfBox = new LinearLayout(ctx); // TMDB poster rows, filled in when they've loaded
        shelfBox.setOrientation(LinearLayout.VERTICAL);
        shelfBox.setClipChildren(false);
        page.addView(shelfBox, new LinearLayout.LayoutParams(-1, -2));
        page.addView(new View(ctx), new LinearLayout.LayoutParams(-1, px(720))); // lets the last row scroll up
        base.addView(rows, 0, new FrameLayout.LayoutParams(-1, -1));

        // Unsplash asks for the photographer's name wherever a photo is shown.
        credit = new TextView(ctx);
        style(credit, 20, medium);
        credit.setAlpha(0.7f);
        credit.setShadowLayer(px(8), 0, px(1), 0x80000000);
        LinearLayout.LayoutParams creditLp = new LinearLayout.LayoutParams(-1, -2);
        creditLp.topMargin = px(6);
        clockBox.addView(credit, creditLp); // under the date, so it moves with the clock
        placeClock(); // needs every view in the clock box, so it runs after them

        showWallpaper(wallpaperKey());
        buildDock();
        loadShelves();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        ctx.registerReceiver(packagesChanged, f);
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_CHANGED); // the TV syncs its clock after boot
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        ctx.registerReceiver(timeChanged, time);
        tick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        ctx.unregisterReceiver(packagesChanged);
        ctx.unregisterReceiver(timeChanged);
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    // firecasa.sh wallpaper starts the app with --es wallpaper custom (or a bundled name), and
    // --es from <photo in /data/local/tmp>: since Android 11 adb can't write into the app's folder.
    void applyExtras(Intent intent) {
        String w = intent == null ? null : intent.getStringExtra("wallpaper");
        String from = intent == null ? null : intent.getStringExtra("from");
        String keys = intent == null ? null : intent.getStringExtra("keys");
        if (keys != null) importKeys(keys);
        if (from != null && customWallpaper() != null) {
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(customWallpaper())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (Exception e) {
                return;
            }
        }
        if (w == null || !choices().contains(w)) return;
        if (CUSTOM.equals(w)) { cachedKey = null; photoVersion++; } // a new photo under the same name
        prefs.edit().putString("wallpaper", w).apply();
    }

    // Called whenever this screen comes to the front: catch up on changes made by the other copy
    // (the app or the overlay) or by installs and uninstalls. reset: Home was pressed, so close any
    // menus and go back to the dock.
    void onShown(boolean reset) {
        Updater.check(ctx, () -> showCaption("FireCasa " + Updater.available(ctx) + " is out. Update it from Settings, top right."));
        if (reset) {
            while (!layers.isEmpty()) closeLayer();
            rows.scrollTo(0, 0);
            if (movingPkg != null) cancelMove();
        }
        if (!drift.isStarted()) drift.start();
        drift(layers.isEmpty());
        // A fresh Unsplash photo every hour.
        if (UNSPLASH.equals(wallpaperKey()) && UNSPLASH.equals(cachedKey)
                && System.currentTimeMillis() - unsplashFile().lastModified() > photoEvery()) {
            cachedKey = null;
            photoVersion++;
        }
        if (!wallpaperKey().equals(shownWallpaper) || seenPhotoVersion != photoVersion) showWallpaper(wallpaperKey());
        loadShelves();
        if ((appsChanged || seenDockVersion != dockVersion) && layers.isEmpty()) buildDock();
        if (!layers.isEmpty() || dockRow.getChildCount() == 0) return;
        View f = findFocus();
        // Both copies keep focus on the same dock app, so swapping one for the other can't be seen.
        if (reset || f == null || f.getParent() == dockRow) dockRow.getChildAt(Math.min(lastDock, dockRow.getChildCount() - 1)).requestFocus();
    }

    String wallpaperKey() {
        List<String> c = choices();
        String w = prefs.getString("wallpaper", c.get(0));
        return c.contains(w) ? w : c.get(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (movingPkg != null) return moveKey(e);
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !layers.isEmpty()) closeLayer();
            return true; // home screen: nothing behind it
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- Apps ----

    // Every app that can be opened, by name.
    Map<String, String> installed() {
        final Map<String, String> labels = new HashMap<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = r.activityInfo.packageName;
                if (!p.equals(ctx.getPackageName()) && !HIDDEN.contains(p) && !labels.containsKey(p))
                    labels.put(p, r.activityInfo.applicationInfo.loadLabel(pm).toString());
            }
        }
        List<String> names = new ArrayList<>(labels.keySet());
        Collections.sort(names, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String p : names) sorted.put(p, labels.get(p));
        return sorted;
    }

    List<String> dockApps(Map<String, String> apps) {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString("dock", null);
        if (saved != null) {
            for (String p : saved.split(",")) if (apps.containsKey(p)) out.add(p);
            return out;
        }
        for (String p : DEFAULT_DOCK) if (apps.containsKey(p) && !out.contains(p)) out.add(p);
        for (String p : apps.keySet()) {
            if (out.size() >= 6) break;
            if (!out.contains(p) && !isSystem(p)) out.add(p);
        }
        return out;
    }

    boolean isSystem(String p) {
        try {
            return (pm.getApplicationInfo(p, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    void saveDock() {
        prefs.edit().putString("dock", TextUtils.join(",", dock)).apply();
        dockVersion++;
    }

    // Apps row order: the most recently opened app first. Saved in prefs as {"package": time opened}.
    Map<String, Long> lastOpened() {
        Map<String, Long> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("lastOpened", "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String p = it.next();
                out.put(p, o.optLong(p, 0));
            }
        } catch (Exception ignored) {}
        return out;
    }

    void markOpened(String p) {
        try {
            JSONObject o = new JSONObject(prefs.getString("lastOpened", "{}"));
            o.put(p, System.currentTimeMillis());
            prefs.edit().putString("lastOpened", o.toString()).apply();
            dockVersion++; // the other copy, and this one on its next show, re-sort the Apps row
        } catch (Exception ignored) {}
    }

    // Keeps the wallpaper's drift running only while the home screen is in front with no menus open.
    void drift(boolean on) {
        on = on && prefs.getBoolean("motion", true);
        if (on) {
            if (drift.isStarted()) drift.resume();
        } else if (drift.isStarted()) drift.pause();
    }

    // ---- Moving a dock app ----

    void startMove(String p) {
        movingPkg = p;
        moveFrom = dock.indexOf(p);
        showCaption("Left and right to move it, OK to place it, Back to cancel");
    }

    // The remote only moves the app while this is on: Left and Right shift it, OK places it, Back cancels.
    // Every key is swallowed, so focus can't wander off mid-move. OK acts on the key down, so the
    // release doesn't also open the app.
    boolean moveKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();
        int i = dock.indexOf(movingPkg);
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int j = k == KeyEvent.KEYCODE_DPAD_LEFT ? i - 1 : i + 1;
            if (i >= 0 && j >= 0 && j < dock.size()) {
                Collections.swap(dock, i, j);
                View card = dockRow.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                dockRow.removeViewAt(i);
                dockRow.addView(card, j, lp);
                spaceCards(dockRow);
                lastDock = j;
                card.requestFocus();
            }
        } else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            movingPkg = null;
            saveDock();
            showCaption("Moved");
        } else if (k == KeyEvent.KEYCODE_BACK) {
            cancelMove();
        }
        return true;
    }

    // Even spacing after a move: the first card has no left gap, every other card has CARD_GAP.
    void spaceCards(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
            lp.leftMargin = i > 0 ? px(CARD_GAP) : 0;
            c.setLayoutParams(lp);
        }
    }

    // Puts the dock back the way it was saved: nothing is saved until OK.
    void cancelMove() {
        movingPkg = null;
        buildDock();
        if (moveFrom >= 0 && moveFrom < dockRow.getChildCount()) dockRow.getChildAt(moveFrom).requestFocus();
    }

    // Builds all the rows: Home (the dock), then every other app, then system apps.
    void buildDock() {
        appsChanged = false;
        seenDockVersion = dockVersion;
        Map<String, String> apps = installed();
        View focused = findFocus();
        LinearLayout inRow = focused != null && rowTops.containsKey(focused.getParent()) ? (LinearLayout) focused.getParent() : dockRow;
        int keep = focused != null && focused.getParent() == inRow ? inRow.indexOfChild(focused) : 0;
        dock = dockApps(apps);
        List<String> others = new ArrayList<>(), system = new ArrayList<>();
        for (String p : apps.keySet()) if (!dock.contains(p)) (isSystem(p) ? system : others).add(p);
        // Most recently opened first; apps never opened keep the alphabetical order (the sort is stable).
        final Map<String, Long> opened = lastOpened();
        Collections.sort(others, (a, b) -> Long.compare(opened.containsKey(b) ? opened.get(b) : 0,
                opened.containsKey(a) ? opened.get(a) : 0));
        fill(dockRow, dock, apps, true);
        if (dock.isEmpty()) {
            View add = textTile("Add apps", CARD_W, CARD_H);
            add.setOnClickListener(this::openDrawer);
            dockRow.addView(add, new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H)));
        }
        fill(appsRow, others, apps, false);
        // System apps stay out of sight in a stacked "More" folder at the end of the Apps row.
        if (!system.isEmpty()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H + 64));
            if (appsRow.getChildCount() > 0) lp.leftMargin = px(CARD_GAP);
            appsRow.addView(folderCard(system, apps), lp);
        }
        int vis = appsRow.getChildCount() > 0 ? View.VISIBLE : View.GONE;
        ((View) appsRow.getParent()).setVisibility(vis);
        ((View) appsRow.getTag()).setVisibility(vis);
        if (inRow.getChildCount() == 0) inRow = dockRow;
        if (layers.isEmpty()) inRow.getChildAt(Math.min(keep, inRow.getChildCount() - 1)).requestFocus();
    }

    static final int CARD_W = 320, CARD_H = 180, CARD_GAP = 40;

    // A titled row whose cards slide sideways. Returns the row the cards go in.
    LinearLayout addRow(LinearLayout page, String title) {
        TextView label = null;
        if (title != null) {
            label = new TextView(ctx);
            style(label, 30, semibold);
            label.setAlpha(0.85f);
            label.setText(title);
            label.setShadowLayer(px(10), 0, px(1), 0x80000000);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
            llp.leftMargin = px(140);
            llp.topMargin = px(16);
            page.addView(label, llp);
        }
        HorizontalScrollView sc = new HorizontalScrollView(ctx);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sc.setClipChildren(false);
        sc.setHorizontalFadingEdgeEnabled(true); // also keeps the focused card off the screen's edge
        sc.setFadingEdgeLength(px(140));
        LinearLayout row = new LinearLayout(ctx);
        row.setClipChildren(false); // focused cards grow past the row
        row.setPadding(px(140), px(title == null ? 28 : 20), px(140), px(12));
        sc.addView(row, new FrameLayout.LayoutParams(-2, -2));
        page.addView(sc, new LinearLayout.LayoutParams(-1, -2));
        rowTops.put(row, label != null ? label : sc);
        row.setTag(label);
        return row;
    }

    void fill(LinearLayout row, List<String> pkgs, Map<String, String> apps, boolean inDock) {
        row.removeAllViews();
        for (int i = 0; i < pkgs.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H + 64));
            if (i > 0) lp.leftMargin = px(CARD_GAP);
            row.addView(card(pkgs.get(i), apps.get(pkgs.get(i)), inDock), lp);
        }
        if (row == dockRow) return;
        int vis = pkgs.isEmpty() ? View.GONE : View.VISIBLE;
        ((View) row.getParent()).setVisibility(vis);
        if (row.getTag() instanceof View) ((View) row.getTag()).setVisibility(vis);
    }

    // Slides the page so the focused row sits near the top. The Home row brings the top shelf back.
    void rowFocused(View card) {
        Object row = card.getParent();
        Shelf looping = loopShelves.get(row);
        if (looping != null) extendRow((LinearLayout) row, looping, card);
        View top = rowTops.get(row);
        if (top == null) return;
        int y = row == dockRow ? 0 : Math.max(0, topInPage(top) - px(160));
        if (rows.getScrollY() != y) rows.smoothScrollTo(0, y);
    }

    int topInPage(View v) {
        int y = 0;
        while (v != null && v != rowsPage) {
            y += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return y;
    }

    interface Focused { void on(View card); }

    // An Apple TV style card: a rounded face that lifts and casts a shadow when focused, with its name
    // fading in underneath. stack > 0 shows that many cards peeking out behind it, for a folder.
    FrameLayout shell(final int w, final int h, int color, View art, FrameLayout.LayoutParams artLp,
                      String nameText, int stack, final Focused focused) {
        final FrameLayout t = new FrameLayout(ctx);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setClipChildren(false);

        final List<View> backs = new ArrayList<>();
        for (int i = stack; i >= 1; i--) {
            View back = new View(ctx);
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(px(18));
            bb.setColor(i == 1 ? 0x59FFFFFF : 0x33FFFFFF);
            back.setBackground(bb);
            back.setScaleX(1 - 0.06f * i);
            back.setScaleY(1 - 0.06f * i);
            back.setTranslationY(-px(14 * i));
            back.setTag(i);
            t.addView(back, new FrameLayout.LayoutParams(px(w), px(h)));
            backs.add(back);
        }

        final FrameLayout face = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(18));
        bg.setColor(color);
        face.setBackground(bg);
        rounded(face, 18);
        face.setElevation(px(2));
        face.addView(art, artLp);
        t.addView(face, new FrameLayout.LayoutParams(px(w), px(h)));

        final TextView name = new TextView(ctx);
        style(name, 26, medium);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setText(nameText);
        name.setAlpha(0);
        name.setShadowLayer(px(8), 0, px(1), 0x80000000);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(px(w), -2);
        nlp.topMargin = px(h + 16);
        t.addView(name, nlp);

        t.setOnFocusChangeListener((v, has) -> {
            face.animate().scaleX(has ? 1.12f : 1).scaleY(has ? 1.12f : 1).translationZ(has ? px(28) : 0)
                    .setDuration(200).setInterpolator(ease).start();
            // The cards behind a folder rise with it, so they stay visible above the grown face.
            for (View b : backs) b.animate().translationY(-px(14 * (Integer) b.getTag()) - (has ? h * 0.06f * s : 0))
                    .setDuration(200).setInterpolator(ease).start();
            name.animate().alpha(has ? 1 : 0).translationY(has ? px(12) : 0).setDuration(200).setInterpolator(ease).start();
            if (!has) return;
            if (focused != null) focused.on(t);
            rowFocused(t);
        });
        return t;
    }

    // An app's card: its white logo on its brand colour, its TV banner, or its icon on a tinted card.
    View card(final String p, final String label, final boolean inDock) {
        ImageView art = new ImageView(ctx);
        FrameLayout.LayoutParams alp;
        String logo = LOGOS.get(p);
        Drawable banner = logo == null ? banner(p) : null;
        if (logo != null) {
            art.setImageBitmap(logo(logo));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            alp = new FrameLayout.LayoutParams(px(170), px(90), Gravity.CENTER);
        } else if (banner != null) {
            art.setImageDrawable(banner);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            alp = new FrameLayout.LayoutParams(-1, -1);
        } else {
            try { art.setImageDrawable(pm.getApplicationIcon(p)); } catch (PackageManager.NameNotFoundException ignored) {}
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            rounded(art, 20);
            alp = new FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER);
        }
        final FrameLayout t = shell(CARD_W, CARD_H, cardColor(p), art, alp, label, 0, c -> {
            if (c.getParent() == dockRow) {
                lastDock = dockRow.indexOfChild(c);
                hero.setText(label);
            }
        });
        t.setOnClickListener(v -> launch(p, label));
        t.setOnLongClickListener(v -> { appMenu(v, p, inDock); return true; });
        t.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_MENU) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) appMenu(v, p, inDock);
            return true;
        });
        return t;
    }

    // The TV banner an app ships for Android TV style launchers, if it has one.
    Drawable banner(String p) {
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(p);
            Drawable d = i != null ? pm.getActivityBanner(i) : null;
            return d != null ? d : pm.getApplicationBanner(p);
        } catch (Exception e) {
            return null;
        }
    }

    // Card colours for the apps with white logos; anything else takes a darkened average of its icon.
    static final Map<String, Integer> BRAND = new HashMap<>();
    static {
        Object[][] c = {
            {"netflix", 0xFF141414}, {"primevideo", 0xFF0F79AF}, {"youtube", 0xFFE62117},
            {"crunchyroll", 0xFFF47521}, {"max", 0xFF002BE7}, {"hbo", 0xFF1A1A1A},
            {"appletv", 0xFF1C1C1E}, {"paramountplus", 0xFF0064FF}, {"plex", 0xFF282A2D},
            {"spotify", 0xFF1DB954}, {"twitch", 0xFF9146FF}, {"tubi", 0xFF7408FF},
            {"jellyfin", 0xFF101828}, {"kodi", 0xFF17B2E7},
        };
        for (Object[] e : c) BRAND.put((String) e[0], (Integer) e[1]);
    }

    int cardColor(String p) {
        String logo = LOGOS.get(p);
        if (logo != null && BRAND.containsKey(logo)) return BRAND.get(logo);
        try {
            Drawable d = pm.getApplicationIcon(p);
            Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 8, 8);
            d.draw(new Canvas(b));
            long r = 0, g = 0, bl = 0, n = 0;
            for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) {
                int c = b.getPixel(x, y);
                int a = c >>> 24;
                if (a < 128) continue;
                r += (c >> 16) & 255; g += (c >> 8) & 255; bl += c & 255; n++;
            }
            if (n == 0) return 0xFF2A2A2E;
            return 0xFF000000 | (int) (r / n * 0.6) << 16 | (int) (g / n * 0.6) << 8 | (int) (bl / n * 0.6);
        } catch (Exception e) {
            return 0xFF2A2A2E;
        }
    }

    // ---- The More folder ----

    // A stacked card showing a few of the apps inside. OK opens the folder.
    View folderCard(final List<String> pkgs, final Map<String, String> apps) {
        GridLayout mini = new GridLayout(ctx);
        mini.setColumnCount(3);
        for (int i = 0; i < Math.min(6, pkgs.size()); i++) {
            ImageView ic = new ImageView(ctx);
            try { ic.setImageDrawable(pm.getApplicationIcon(pkgs.get(i))); } catch (PackageManager.NameNotFoundException ignored) {}
            ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            rounded(ic, 10);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = px(48);
            lp.height = px(48);
            lp.setMargins(px(8), px(6), px(8), px(6));
            mini.addView(ic, lp);
        }
        View t = shell(CARD_W, CARD_H, 0xFF2C2C30, mini, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
                "More  ·  " + pkgs.size() + " apps", 2, null);
        t.setOnClickListener(v -> openFolder(v, "More", pkgs, apps));
        return t;
    }

    void openFolder(View anchor, String name, List<String> pkgs, Map<String, String> apps) {
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xA6000000), new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(ctx);
        style(title, 48, semibold);
        title.setText(name);
        layer.addView(title, at(140, 64, -2, -2));

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        sv.setPadding(px(110), px(20), px(110), px(80));
        GridLayout grid = new GridLayout(ctx);
        grid.setClipChildren(false);
        grid.setColumnCount(6);
        View first = null;
        for (String p : pkgs) {
            View t = tile(p, apps.get(p), 270, 196, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = px(270);
            lp.height = px(196);
            lp.setMargins(px(5), px(5), px(5), px(5));
            grid.addView(t, lp);
            if (first == null) first = t;
        }
        sv.addView(grid);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = px(150);
        layer.addView(sv, slp);
        openLayer(layer, anchor, first);
    }

    // ---- TMDB poster rows ----
    // Needs app/assets/tmdb_token.txt (a TMDB API Read Access Token). Without it these rows don't show.

    static final String TMDB = "https://api.themoviedb.org/3/";
    static final String STREAMING = "&watch_region=US&with_watch_monetization_types=flatrate&sort_by=popularity.desc";
    static final int POSTER_W = 230, POSTER_H = 345, POSTER_GAP = 30;

    static class Item { int id; boolean tv; String title, poster, year; }
    static class Shelf { String title; boolean ranked; final List<Item> items = new ArrayList<>(); }

    // Shared by the app and the overlay so both show the same rows, fetched once every 6 hours.
    static List<Shelf> shelves;
    static long shelvesAt;
    static boolean loadingShelves;
    static final List<HomeScreen> waitingForShelves = new ArrayList<>();
    static final ExecutorService pool = Executors.newFixedThreadPool(3);
    static final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    // Rows fetched once every 6 hours; shelvesGen bumps on each load, so screens know to rebuild.
    static int shelvesGen;
    static String shelvesSig = "";

    // TMDB's ids for the streaming services in PROVIDERS, for the "On <app>" rows.
    static final Map<String, Integer> PROVIDER_IDS = new HashMap<>();
    static {
        Object[][] ids = {{"netflix", 8}, {"primevideo", 9}, {"disneyplus", 337}, {"hulu", 15}, {"hbomax", 1899},
            {"max", 1899}, {"appletv", 350}, {"paramount", 531}, {"peacock", 386}, {"crunchyroll", 283},
            {"tubi", 73}, {"plutotv", 300}, {"youtube", 192}};
        for (Object[] e : ids) PROVIDER_IDS.put((String) e[0], (Integer) e[1]);
    }

    // The rows under Apps: Because you like, Movies, Shows. Tops is added after them. Reads the recent titles,
    // so it runs on the UI thread.
    List<String[]> shelfDefs() {
        List<String[]> defs = new ArrayList<>();
        List<Item> recent = recentItems();
        if (!recent.isEmpty()) {
            Item last = recent.get(0);
            defs.add(new String[] {"Because you like " + last.title, (last.tv ? "tv/" : "movie/") + last.id + "/recommendations?", ""});
        } else {
            defs.add(new String[] {"Because you like highly rated movies", "movie/top_rated?", ""});
        }
        defs.add(new String[] {"Movies", "movie/popular?", ""});
        defs.add(new String[] {"Shows", "tv/popular?", ""});
        return defs;
    }

    static String shelfSig(List<String[]> defs) {
        StringBuilder sb = new StringBuilder();
        for (String[] d : defs) sb.append(d[0]).append('|').append(d[1]).append('\n');
        return sb.toString();
    }

    // One row from a definition: page 1. Null when TMDB has no titles with posters.
    Shelf fetchShelf(String[] def, String token, int page) throws Exception {
        boolean tv = def[1].contains("/tv") || def[1].startsWith("tv/");
        JSONArray results = new JSONObject(get(TMDB + def[1] + "&language=en-US" + (page > 1 ? "&page=" + page : ""),
                "Bearer " + token)).getJSONArray("results");
        Shelf sh = new Shelf();
        sh.title = def[0];
        sh.ranked = def[2].equals("rank");
        for (int i = 0; i < results.length() && sh.items.size() < 20; i++) {
            JSONObject r = results.getJSONObject(i);
            String poster = r.optString("poster_path", "");
            if (poster.isEmpty() || "null".equals(poster)) continue;
            Item it = new Item();
            it.id = r.getInt("id");
            it.tv = tv;
            it.title = r.optString(tv ? "name" : "title");
            it.poster = poster;
            String date = r.optString(tv ? "first_air_date" : "release_date", "");
            it.year = date.length() >= 4 ? date.substring(0, 4) : "";
            sh.items.add(it);
        }
        return sh.items.isEmpty() ? null : sh;
    }

    // Anime (Japanese animation) and Cartoons (US animation), the rows after Tops.
    static final String[][] LATE_ROWS = {
        {"Anime", "discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&watch_region=US", ""},
        {"Cartoons", "discover/tv?with_genres=16&with_origin_country=US&sort_by=popularity.desc&watch_region=US", ""},
    };

    // TOPS: four trending movies, three trending shows, and three anime, numbered 1 to 10.
    Shelf fetchTops(String token) throws Exception {
        Shelf sh = new Shelf();
        sh.title = "Tops";
        sh.ranked = true;
        sh.items.addAll(topItems("trending/movie/day?", false, 4, token));
        sh.items.addAll(topItems("trending/tv/day?", true, 3, token));
        sh.items.addAll(topItems("discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&watch_region=US", true, 3, token));
        return sh.items.isEmpty() ? null : sh;
    }

    List<Item> topItems(String endpoint, boolean tv, int max, String token) throws Exception {
        List<Item> out = new ArrayList<>();
        JSONArray results = new JSONObject(get(TMDB + endpoint + "&language=en-US", "Bearer " + token)).getJSONArray("results");
        for (int i = 0; i < results.length() && out.size() < max; i++) {
            JSONObject r = results.getJSONObject(i);
            String poster = r.optString("poster_path", "");
            if (poster.isEmpty() || "null".equals(poster)) continue;
            Item it = new Item();
            it.id = r.getInt("id");
            it.tv = tv;
            it.title = r.optString(tv ? "name" : "title");
            it.poster = poster;
            String date = r.optString(tv ? "first_air_date" : "release_date", "");
            it.year = date.length() >= 4 ? date.substring(0, 4) : "";
            out.add(it);
        }
        return out;
    }

    void loadShelves() {
        final String token = asset("tmdb_token.txt");
        if (token == null) return;
        final List<String[]> defs = shelfDefs();
        final String sig = shelfSig(defs) + "late:" + LATE_ROWS.length;
        synchronized (HomeScreen.class) {
            if (shelves != null && sig.equals(shelvesSig) && System.currentTimeMillis() - shelvesAt < 6 * 3600_000L) {
                buildShelves();
                return;
            }
            if (!waitingForShelves.contains(this)) waitingForShelves.add(this);
            if (loadingShelves) return;
            loadingShelves = true;
        }
        final File posterDir = new File(ctx.getCacheDir(), "posters");
        new Thread(() -> {
            List<Shelf> out = new ArrayList<>();
            for (String[] def : defs) {
                try {
                    Shelf sh = fetchShelf(def, token, 1);
                    if (sh != null) out.add(sh);
                } catch (Exception ignored) {}
            }
            try {
                Shelf tops = fetchTops(token);
                if (tops != null) out.add(tops);
            } catch (Exception ignored) {}
            for (String[] late : LATE_ROWS) {
                try {
                    Shelf sh = fetchShelf(late, token, 1);
                    if (sh != null) out.add(sh);
                } catch (Exception ignored) {}
            }
            // Posters not used for a week are cleared out.
            File[] old = posterDir.listFiles();
            if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 7 * 86400_000L) f.delete();
            synchronized (HomeScreen.class) {
                loadingShelves = false;
                if (!out.isEmpty()) {
                    shelves = out;
                    shelvesSig = sig;
                    shelvesAt = System.currentTimeMillis();
                    shelvesGen++;
                }
                for (final HomeScreen h : waitingForShelves) h.post(h::buildShelves);
                waitingForShelves.clear();
            }
        }).start();
    }

    // Titles opened from FireCasa, most recent first, kept in prefs as JSON.
    List<Item> recentItems() {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recentTitles", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getInt("id");
                it.tv = o.optBoolean("tv");
                it.title = o.optString("title");
                it.poster = o.optString("poster");
                it.year = o.optString("year");
                out.add(it);
            }
        } catch (Exception ignored) {}
        return out;
    }

    void rememberTitle(Item it) {
        try {
            JSONArray out = new JSONArray();
            out.put(titleJson(it));
            for (Item old : recentItems()) {
                if (out.length() >= 20) break;
                if (old.id == it.id && old.tv == it.tv) continue;
                out.put(titleJson(old));
            }
            prefs.edit().putString("recentTitles", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    static JSONObject titleJson(Item it) throws org.json.JSONException {
        return new JSONObject().put("id", it.id).put("tv", it.tv).put("title", it.title).put("poster", it.poster).put("year", it.year);
    }

    // The rows under Apps, then the streaming folders. Rebuilt only when the rows, the recent titles or the
    // installed streaming apps change.
    void buildShelves() {
        List<Shelf> list = shelves == null ? new ArrayList<Shelf>() : shelves;
        String recentSig = prefs.getString("recentTitles", "");
        String folderSig = folderSig();
        String activity = prefs.getString("activity", "{}");
        if (seenGen == shelvesGen && seenRecent.equals(recentSig) && seenFolders.equals(folderSig) && seenActivity.equals(activity)) return;
        View f = findFocus();
        boolean hadFocus = f != null && shelfRows.contains(f.getParent());
        if (hadFocus && dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
        for (LinearLayout r : shelfRows) rowTops.remove(r);
        shelfRows.clear();
        loopShelves.clear();
        shelfBox.removeAllViews();
        for (Shelf sh : orderRows(list)) addShelfRow(sh);
        addFolderRow();
        seenGen = shelvesGen;
        seenRecent = recentSig;
        seenFolders = folderSig;
        seenActivity = activity;
    }

    // The streaming services installed here, as {TMDB id, app label}, one per service.
    List<String[]> installedServices() {
        List<String[]> out = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (String[] e : PROVIDERS) {
            Integer id = PROVIDER_IDS.get(e[0]);
            if (id == null || ids.contains(id)) continue;
            String pkg = null;
            for (int i = 1; i < e.length && pkg == null; i++) if (isInstalled(e[i])) pkg = e[i];
            if (pkg == null) continue;
            ids.add(id);
            out.add(new String[] {String.valueOf(id), appLabel(pkg), pkg});
        }
        return out;
    }

    String folderSig() {
        StringBuilder sb = new StringBuilder();
        for (String[] s : installedServices()) sb.append(s[0]).append(',');
        return sb.toString();
    }

    // The Folders row: one card per installed streaming service. OK opens that service's page.
    void addFolderRow() {
        List<String[]> services = installedServices();
        if (services.isEmpty()) return;
        LinearLayout row = addRow(shelfBox, "Folders");
        for (String[] s : services) {
            final int id = Integer.parseInt(s[0]);
            final String label = s[1];
            View t = serviceCard(s[2], label);
            t.setOnClickListener(v -> openService(id, label, v));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H));
            if (row.getChildCount() > 0) lp.leftMargin = px(CARD_GAP);
            row.addView(t, lp);
        }
        loopRow(row);
        shelfRows.add(row);
    }

    // A streaming service's folder: its brand colour, the app's icon faint behind, and its logo in front.
    View serviceCard(String pkg, String label) {
        FrameLayout t = focusTile(32, 1.04f);
        FrameLayout face = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor(pkg));
        bg.setCornerRadius(px(20));
        face.setBackground(bg);
        rounded(face, 20);
        Drawable appIcon = null;
        try { appIcon = pm.getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException ignored) {}
        String logoName = LOGOS.get(pkg);
        Bitmap logoBitmap = logoName != null ? logo(logoName) : null;
        Drawable banner = logoBitmap == null ? banner(pkg) : null;
        ImageView front = new ImageView(ctx);
        if (logoBitmap != null) {
            front.setImageBitmap(logoBitmap);
            front.setScaleType(ImageView.ScaleType.FIT_CENTER);
            face.addView(front, new FrameLayout.LayoutParams(px(150), px(80), Gravity.CENTER));
        } else if (banner != null) {
            front.setImageDrawable(banner);
            front.setScaleType(ImageView.ScaleType.CENTER_CROP);
            face.addView(front, new FrameLayout.LayoutParams(-1, -1));
        } else if (appIcon != null) {
            front.setImageDrawable(appIcon);
            front.setScaleType(ImageView.ScaleType.FIT_CENTER);
            face.addView(front, new FrameLayout.LayoutParams(px(110), px(110), Gravity.CENTER));
        }
        t.addView(face, new FrameLayout.LayoutParams(-1, -1));
        return t;
    }

    // A streaming service's own page: its movies and shows, most popular first.
    void openService(final int providerId, final String label, View anchor) {
        final String token = asset("tmdb_token.txt");
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xD9000000), new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(ctx);
        style(title, 56, semibold);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(label);
        final TextView status = new TextView(ctx);
        style(status, 30, semibold);
        status.setAlpha(0.85f);
        status.setText("Loading…");
        final View close = actionTile("Close", v -> closeLayer());
        final LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipChildren(false);
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(140), px(120), px(140), px(60));
        page.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = px(12);
        page.addView(status, stp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(24);
        page.addView(close, clp);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        layer.addView(page, new FrameLayout.LayoutParams(-1, -1));
        openLayer(layer, anchor, close);

        final String filter = "&watch_region=US&with_watch_monetization_types=flatrate&sort_by=popularity.desc";
        new Thread(() -> {
            Shelf m = null, s = null;
            try {
                m = fetchShelf(new String[] {"Movies", "discover/movie?with_watch_providers=" + providerId + filter, ""}, token, 1);
            } catch (Exception ignored) {}
            try {
                s = fetchShelf(new String[] {"Shows", "discover/tv?with_watch_providers=" + providerId + filter, ""}, token, 1);
            } catch (Exception ignored) {}
            final Shelf movies = m, shows = s;
            post(() -> {
                if (!layers.contains(layer)) return;
                View first = null;
                if (movies != null) first = worksRow(content, "Movies", movies.items);
                View f2 = shows == null ? null : worksRow(content, "Shows", shows.items);
                if (first == null) first = f2;
                status.setText(first == null ? "Nothing listed in the US yet" : "");
                if (close.hasFocus() && first != null) first.requestFocus();
            });
        }).start();
    }

    // The category a title belongs to, for the row it counts toward: movies, shows, cartoons (other animation), or
    // anime (Japanese animation).
    static String categoryOf(JSONObject d, boolean tv) {
        if (!tv) return "movies";
        boolean animated = false;
        JSONArray genres = d.optJSONArray("genres");
        if (genres != null) for (int i = 0; i < genres.length(); i++) {
            JSONObject g = genres.optJSONObject(i);
            if (g != null && g.optInt("id") == 16) animated = true;
        }
        if (!animated) return "shows";
        return "ja".equals(d.optString("original_language")) ? "anime" : "cartoons";
    }

    void recordActivity(String category) {
        try {
            JSONObject o = new JSONObject(prefs.getString("activity", "{}"));
            o.put(category, o.optInt(category, 0) + 1);
            prefs.edit().putString("activity", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    Map<String, Integer> activityCounts() {
        Map<String, Integer> out = new HashMap<>();
        for (String c : new String[] {"movies", "shows", "cartoons", "anime"}) out.put(c, 0);
        try {
            JSONObject o = new JSONObject(prefs.getString("activity", "{}"));
            for (String c : out.keySet()) out.put(c, o.optInt(c, 0));
        } catch (Exception ignored) {}
        return out;
    }

    // Matches a row to its key from its title.
    static String keyOf(String title) {
        if (title.startsWith("Because")) return "because";
        switch (title) {
            case "Movies": return "movies";
            case "Shows": return "shows";
            case "Cartoons": return "cartoons";
            case "Anime": return "anime";
            case "Tops": return "tops";
            default: return title;
        }
    }

    // The rows in the order you use them: Because you like first, then Movies, Shows, Cartoons and Anime (the one
    // you open most first, ties in that order), then Tops.
    List<Shelf> orderRows(List<Shelf> list) {
        Map<String, Shelf> byKey = new LinkedHashMap<>();
        for (Shelf s : list) byKey.put(keyOf(s.title), s);
        List<Shelf> out = new ArrayList<>();
        Set<Shelf> placed = new HashSet<>();
        if (byKey.containsKey("because")) out.add(byKey.get("because"));
        final Map<String, Integer> counts = activityCounts();
        List<String> cats = new ArrayList<>(Arrays.asList("movies", "shows", "cartoons", "anime"));
        Collections.sort(cats, (a, b) -> counts.get(b) - counts.get(a));
        for (String c : cats) if (byKey.containsKey(c)) out.add(byKey.get(c));
        if (byKey.containsKey("tops")) out.add(byKey.get("tops"));
        for (Shelf s : out) placed.add(s);
        for (Shelf s : list) if (!placed.contains(s)) out.add(s);
        return out;
    }

    // Makes a row wrap around: right from its last card goes to the first, and left from the first goes to the last.
    void loopRow(LinearLayout row) {
        int n = row.getChildCount();
        if (n < 2) return;
        View first = row.getChildAt(0), last = row.getChildAt(n - 1);
        if (first.getId() == View.NO_ID) first.setId(View.generateViewId());
        if (last.getId() == View.NO_ID) last.setId(View.generateViewId());
        first.setNextFocusLeftId(last.getId());
        last.setNextFocusRightId(first.getId());
    }

    void addShelfRow(Shelf sh) {
        LinearLayout row = addRow(shelfBox, sh.title);
        if (sh.ranked) {
            for (int i = 0; i < sh.items.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(POSTER_W), px(POSTER_H + 64));
                if (i > 0) lp.leftMargin = px(POSTER_GAP);
                row.addView(posterCard(sh.items.get(i), i + 1), lp);
            }
        } else {
            loopShelves.put(row, sh);
            appendCopies(row, sh, sh.items.size());
        }
        shelfRows.add(row);
    }

    // Adds the next cards of a looping row: the titles again, carrying on from where the row is.
    void appendCopies(LinearLayout row, Shelf sh, int count) {
        int n = sh.items.size();
        for (int k = 0; k < count; k++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(POSTER_W), px(POSTER_H + 64));
            if (row.getChildCount() > 0) lp.leftMargin = px(POSTER_GAP);
            row.addView(posterCard(sh.items.get(row.getChildCount() % n), 0), lp);
        }
    }

    // A looping row never runs out: near its end it adds the titles again, and it drops the same number of cards
    // from its far start, moving the scroll with them, so the row carries on without a visible jump.
    void extendRow(LinearLayout row, Shelf sh, View focused) {
        int n = sh.items.size();
        if (row.indexOfChild(focused) >= row.getChildCount() - 4) appendCopies(row, sh, n);
        if (row.getChildCount() > 3 * n) {
            for (int i = 0; i < n; i++) row.removeViewAt(0);
            if (row.getParent() instanceof HorizontalScrollView) {
                HorizontalScrollView sc = (HorizontalScrollView) row.getParent();
                sc.scrollTo(Math.max(0, sc.getScrollX() - n * (px(POSTER_W) + px(POSTER_GAP))), 0);
            }
        }
    }

    // A poster card: the title until the poster arrives, and a big rank number on Top 10 rows.
    View posterCard(final Item it, int rank) {
        FrameLayout art = new FrameLayout(ctx);
        TextView fallback = new TextView(ctx);
        style(fallback, 28, semibold);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(px(20), 0, px(20), 0);
        fallback.setText(it.title);
        art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(img, new FrameLayout.LayoutParams(-1, -1));
        loadPoster(it.poster, img);
        if (rank > 0) {
            View fade = new View(ctx);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0x99000000, 0, 0}));
            art.addView(fade, new FrameLayout.LayoutParams(-1, -1));
            TextView num = new TextView(ctx);
            style(num, 96, semibold);
            num.setText(String.valueOf(rank));
            num.setShadowLayer(px(16), 0, px(2), 0x99000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            lp.leftMargin = px(18);
            lp.topMargin = px(10);
            art.addView(num, lp);
        }
        View t = shell(POSTER_W, POSTER_H, 0xFF1E1E22, art, new FrameLayout.LayoutParams(-1, -1),
                it.year.isEmpty() ? it.title : it.title + "  ·  " + it.year, 0, null);
        t.setOnClickListener(v -> openTitle(it, t));
        return t;
    }

    void loadPoster(final String path, final ImageView img) {
        img.setTag(path);
        Bitmap cached = posters.get(path);
        if (cached != null) {
            img.setImageBitmap(cached);
            return;
        }
        final int w = px(POSTER_W), h = px(POSTER_H);
        final File dir = new File(ctx.getCacheDir(), "posters");
        pool.execute(() -> {
            try {
                dir.mkdirs();
                File f = new File(dir, path.replace("/", ""));
                if (!f.exists()) download("https://image.tmdb.org/t/p/w342" + path, null, f);
                else f.setLastModified(System.currentTimeMillis());
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; posters don't need alpha
                Bitmap raw = BitmapFactory.decodeFile(f.getPath(), o);
                if (raw == null) {
                    f.delete();
                    return;
                }
                final Bitmap sized = Bitmap.createScaledBitmap(raw, w, h, true);
                if (sized != raw) raw.recycle();
                posters.put(path, sized);
                img.post(() -> { if (path.equals(img.getTag())) img.setImageBitmap(sized); });
            } catch (Exception ignored) {}
        });
    }

    // OK on a poster opens its title page: ratings and quick facts, the backdrop and stills, seasons (TV), cast,
    // crew, and the apps it plays in.
    void openTitle(final Item it, View anchor) {
        final String token = asset("tmdb_token.txt");
        final String type = it.tv ? "tv/" : "movie/";
        FrameLayout layer = new FrameLayout(ctx);
        final GlassView glass = new GlassView(ctx, 0, 0xD9000000);
        layer.addView(glass, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        ImageView poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded(poster, 18);
        loadPoster(it.poster, poster);
        body.addView(poster, new LinearLayout.LayoutParams(px(POSTER_W), px(POSTER_H)));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(1100), -2);
        tlp.leftMargin = px(60);
        body.addView(text, tlp);

        TextView title = new TextView(ctx);
        style(title, 56, semibold);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(it.title);
        text.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(ctx);
        style(meta, 30, medium);
        meta.setAlpha(0.7f);
        meta.setText((it.tv ? "TV show" : "Movie") + (it.year.isEmpty() ? "" : "  ·  " + it.year));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = px(8);
        text.addView(meta, mlp);

        final TextView freeLine = new TextView(ctx);
        style(freeLine, 28, medium);
        freeLine.setAlpha(0.85f);
        LinearLayout.LayoutParams freelp = new LinearLayout.LayoutParams(-1, -2);
        freelp.topMargin = px(14);
        text.addView(freeLine, freelp);

        // Full details, hidden until opened: the ratings and the quick facts.
        final LinearLayout detailBox = new LinearLayout(ctx);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.setVisibility(View.GONE);

        // Ratings: a badge and score for each one found. The row stays hidden when neither is.
        final LinearLayout ratingsRow = new LinearLayout(ctx);
        ratingsRow.setOrientation(LinearLayout.HORIZONTAL);
        ratingsRow.setGravity(Gravity.CENTER_VERTICAL);
        ratingsRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = px(14);
        text.addView(ratingsRow, rlp);


        final TextView overview = new TextView(ctx);
        style(overview, 32, medium);
        overview.setMaxLines(5);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setText("Loading…");
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = px(20);
        text.addView(overview, olp);

        final TextView facts = new TextView(ctx);
        style(facts, 26, medium);
        facts.setAlpha(0.85f);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = px(18);
        detailBox.addView(facts, flp);
        text.addView(detailBox, new LinearLayout.LayoutParams(-1, -2));

        // Playing in: the service's mini logo and its name, highlighted in its colour when focused.
        final LinearLayout playRow = new LinearLayout(ctx);
        playRow.setOrientation(LinearLayout.HORIZONTAL);
        playRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(-2, -2);
        plp2.topMargin = px(20);
        text.addView(playRow, plp2);

        final TextView where = new TextView(ctx);
        style(where, 30, semibold);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = px(20);
        text.addView(where, wlp);

        // Play, the trailer and the alternatives go in front of Close. Focus starts on Close until the data is in.
        final LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final View close = actionTile("Close", v -> closeLayer());
        View details = actionTile("Full details", v -> {
            boolean open = detailBox.getVisibility() != View.VISIBLE;
            detailBox.setVisibility(open ? View.VISIBLE : View.GONE);
            ((TextView) ((ViewGroup) v).getChildAt(0)).setText(open ? "Hide details" : "Full details");
        });
        actions.addView(details, new LinearLayout.LayoutParams(-2, -2));
        actions.addView(close, new LinearLayout.LayoutParams(-2, -2));
        HorizontalScrollView actionsScroll = new HorizontalScrollView(ctx);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setClipChildren(false);
        actionsScroll.addView(actions, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = px(36);
        text.addView(actionsScroll, alp);

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(140), px(130), px(140), px(60));
        page.addView(body, new LinearLayout.LayoutParams(-2, -2));
        // Tabs: Media (stills and seasons) or Social (reviews). Cast and crew are always shown.
        final List<LinearLayout> mediaRows = new ArrayList<>(), socialRows = new ArrayList<>();
        final LinearLayout tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(actionTile("Media  ●", v -> showTabs(true, tabs, mediaRows, socialRows)), new LinearLayout.LayoutParams(-2, -2));
        tabs.addView(actionTile("Social", v -> showTabs(false, tabs, mediaRows, socialRows)), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(-2, -2);
        tabLp.topMargin = px(36);
        page.addView(tabs, tabLp);
        final LinearLayout stillsRow = addStrip(page, "Stills", 40);
        final LinearLayout seasonsRow = addStrip(page, "Seasons", 40);
        final LinearLayout reviewsRow = addStrip(page, "Reviews", 40);
        final LinearLayout castRow = addStrip(page, "Cast", 40);
        mediaRows.add(stillsRow);
        mediaRows.add(seasonsRow);
        socialRows.add(reviewsRow);
        final LinearLayout crewRow = addStrip(page, "Crew", 40);

        ScrollView pageScroll = new ScrollView(ctx);
        pageScroll.setVerticalScrollBarEnabled(false);
        pageScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pageScroll.setClipChildren(false);
        pageScroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        layer.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
        openLayer(layer, anchor, close);

        new Thread(() -> {
            JSONObject found = new JSONObject();
            try {
                found = new JSONObject(get(TMDB + type + it.id + "?language=en-US", "Bearer " + token));
            } catch (Exception ignored) {}
            final JSONObject det = found;
            if (det.length() > 0) recordActivity(categoryOf(det, it.tv));
            String imdbId = det.optString("imdb_id", "");
            try {
                String ext = new JSONObject(get(TMDB + type + it.id + "/external_ids", "Bearer " + token)).optString("imdb_id", "");
                if (!ext.isEmpty() && !"null".equals(ext)) imdbId = ext;
            } catch (Exception ignored) {}
            final String omdbKey = asset("omdb_key.txt");
            final String[] scores = ratingsFor(imdbId, omdbKey);
            final String ov = det.optString("overview", "");
            final String bgPath = det.optString("backdrop_path", "");

            final List<String> stills = new ArrayList<>();
            try {
                JSONArray bd = new JSONObject(get(TMDB + type + it.id + "/images?include_image_language=en,null", "Bearer " + token))
                        .getJSONArray("backdrops");
                for (int i = 0; i < bd.length() && stills.size() < 12; i++) stills.add(bd.getJSONObject(i).getString("file_path"));
            } catch (Exception ignored) {}

            final List<Person> cast = new ArrayList<>();
            final List<Person> crew = new ArrayList<>();
            try {
                JSONObject credits = new JSONObject(get(TMDB + type + it.id + "/credits?language=en-US", "Bearer " + token));
                JSONArray c = credits.optJSONArray("cast");
                if (c != null) for (int i = 0; i < c.length() && cast.size() < 12; i++) {
                    Person p = personFrom(c.getJSONObject(i), null);
                    if (p != null) cast.add(p);
                }
                JSONArray creators = det.optJSONArray("created_by");
                if (creators != null) for (int i = 0; i < creators.length(); i++) {
                    Person p = personFrom(creators.getJSONObject(i), "Creator");
                    if (p != null) crew.add(p);
                }
                for (Person p : crewPeople(credits.optJSONArray("crew"))) {
                    boolean dup = false;
                    for (Person q : crew) if (q.id == p.id) dup = true;
                    if (!dup) crew.add(p);
                }
            } catch (Exception ignored) {}

            String trailer = null; // the official trailer on YouTube, if TMDB has one
            try {
                JSONArray vids = new JSONObject(get(TMDB + type + it.id + "/videos?language=en-US", "Bearer " + token)).getJSONArray("results");
                String any = null;
                for (int i = 0; i < vids.length(); i++) {
                    JSONObject v = vids.getJSONObject(i);
                    if (!"YouTube".equals(v.optString("site")) || !"Trailer".equals(v.optString("type"))) continue;
                    if (v.optBoolean("official")) { trailer = v.getString("key"); break; }
                    if (any == null) any = v.getString("key");
                }
                if (trailer == null) trailer = any;
            } catch (Exception ignored) {}
            final String clip = trailer;
            final int userScore = (int) Math.round(det.optDouble("vote_average", 0) * 10);
            final int userVotes = det.optInt("vote_count", 0);
            final List<String[]> reviews = new ArrayList<>(); // author, their rating, the text
            try {
                JSONArray rv = new JSONObject(get(TMDB + type + it.id + "/reviews?language=en-US", "Bearer " + token)).optJSONArray("results");
                if (rv != null) for (int i = 0; i < rv.length() && reviews.size() < 6; i++) {
                    JSONObject r = rv.getJSONObject(i);
                    JSONObject ad = r.optJSONObject("author_details");
                    String rating = ad == null || ad.isNull("rating") ? "" : ad.optInt("rating") + "/10";
                    reviews.add(new String[] {r.optString("author", "Anonymous"), rating, r.optString("content", "")});
                }
            } catch (Exception ignored) {}

            Where w;
            try {
                w = whereToWatch(it, token);
            } catch (Exception e) {
                w = null;
            }
            final Where found2 = w;
            final Map<String, String> apps = installed(); // off the UI thread: it reads every app's label
            final boolean hasKey = omdbKey != null;
            post(() -> {
                if (!layers.contains(layer)) return; // closed before the data came in
                if (userVotes > 0) {
                    LinearLayout us = new LinearLayout(ctx);
                    us.setOrientation(LinearLayout.HORIZONTAL);
                    us.setGravity(Gravity.CENTER_VERTICAL);
                    us.addView(scoreRing(userScore), new LinearLayout.LayoutParams(px(84), px(84)));
                    TextView ut = new TextView(ctx);
                    style(ut, 26, semibold);
                    ut.setText("User\nScore");
                    LinearLayout.LayoutParams utlp = new LinearLayout.LayoutParams(-2, -2);
                    utlp.leftMargin = px(12);
                    us.addView(ut, utlp);
                    LinearLayout.LayoutParams uslp = new LinearLayout.LayoutParams(-2, -2);
                    uslp.rightMargin = px(36);
                    ratingsRow.addView(us, uslp);
                }
                if (hasKey) {
                    if (!scores[0].isEmpty()) ratingsRow.addView(highlightRow(0xFFF5C518, badge("IMDb", 0xFFF5C518, 0xFF000000),
                            valueText(scores[0] + "/10", 0xFFF5C518), false), new LinearLayout.LayoutParams(-2, -2));
                    if (!scores[1].isEmpty()) {
                        LinearLayout.LayoutParams rtlp = new LinearLayout.LayoutParams(-2, -2);
                        if (ratingsRow.getChildCount() > 0) rtlp.leftMargin = px(24);
                        ratingsRow.addView(highlightRow(0xFFFA320A, rtLogo(), valueText(scores[1], 0xFFFA320A), false), rtlp);
                    }
                }
                if (ratingsRow.getChildCount() > 0) ratingsRow.setVisibility(View.VISIBLE);
                overview.setText(ov.isEmpty() ? "No description yet." : ov);
                String factsOut = factsText(det, it.tv);
                if (!scores[3].isEmpty()) factsOut = (factsOut.isEmpty() ? "" : factsOut + "\n") + "Awards: " + scores[3];
                facts.setText(factsOut);
                freeLine.setText(freeText(det, it.tv));
                if (!bgPath.isEmpty() && !"null".equals(bgPath)) {
                    ImageView bg = new ImageView(ctx);
                    bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    loadStill(bgPath, "w780", bg);
                    glass.setVisibility(View.GONE);
                    View dim = new View(ctx);
                    dim.setBackgroundColor(0xB3000000);
                    layer.addView(bg, 0, new FrameLayout.LayoutParams(-1, -1));
                    layer.addView(dim, 1, new FrameLayout.LayoutParams(-1, -1));
                }
                for (String still : stills) {
                    ImageView img = new ImageView(ctx);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    loadStill(still, "w500", img);
                    FrameLayout tile = focusTile(18, 1.06f);
                    tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
                    rounded(img, 18);
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(480), px(270));
                    if (stillsRow.getChildCount() > 0) slp.leftMargin = px(24);
                    stillsRow.addView(tile, slp);
                }
                if (stillsRow.getChildCount() == 0) hideStrip(stillsRow);
                JSONArray seasons = det.optJSONArray("seasons");
                if (it.tv && seasons != null) for (int i = 0; i < seasons.length(); i++) {
                    JSONObject sj = seasons.optJSONObject(i);
                    if (sj == null || sj.optInt("season_number") < 1) continue;
                    int n = sj.optInt("season_number");
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(POSTER_W), px(POSTER_H + 64));
                    if (seasonsRow.getChildCount() > 0) lp.leftMargin = px(POSTER_GAP);
                    seasonsRow.addView(seasonCard(it, sj, n, sj.optString("name", "Season " + n)), lp);
                }
                if (seasonsRow.getChildCount() == 0) hideStrip(seasonsRow);
                for (Person p : cast) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.rightMargin = px(24);
                    castRow.addView(castTile(p), lp);
                }
                if (castRow.getChildCount() == 0) hideStrip(castRow);
                for (Person p : crew) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.rightMargin = px(24);
                    crewRow.addView(castTile(p), lp);
                }
                if (crewRow.getChildCount() == 0) hideStrip(crewRow);
                if (reviews.isEmpty()) {
                    TextView none = new TextView(ctx);
                    style(none, 28, medium);
                    none.setAlpha(0.8f);
                    none.setText("No reviews yet");
                    reviewsRow.addView(none, new LinearLayout.LayoutParams(-2, -2));
                }
                for (String[] rv : reviews) {
                    FrameLayout tile = focusTile(18, 1.03f);
                    LinearLayout col = new LinearLayout(ctx);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setPadding(px(22), px(18), px(22), px(18));
                    TextView who = new TextView(ctx);
                    style(who, 28, semibold);
                    who.setSingleLine();
                    who.setEllipsize(TextUtils.TruncateAt.END);
                    who.setText(rv[0] + (rv[1].isEmpty() ? "" : "   " + rv[1]));
                    col.addView(who, new LinearLayout.LayoutParams(-1, -2));
                    TextView snippet = new TextView(ctx);
                    style(snippet, 24, medium);
                    snippet.setAlpha(0.85f);
                    snippet.setMaxLines(5);
                    snippet.setEllipsize(TextUtils.TruncateAt.END);
                    snippet.setText(rv[2]);
                    LinearLayout.LayoutParams snlp = new LinearLayout.LayoutParams(-1, -2);
                    snlp.topMargin = px(8);
                    col.addView(snippet, snlp);
                    tile.addView(col, new FrameLayout.LayoutParams(px(640), -2));
                    LinearLayout.LayoutParams rvlp = new LinearLayout.LayoutParams(-2, -2);
                    if (reviewsRow.getChildCount() > 0) rvlp.leftMargin = px(24);
                    reviewsRow.addView(tile, rvlp);
                }
                showTabs(true, tabs, mediaRows, socialRows);
                if (clip != null) {
                    actions.addView(actionTile("Open trailer", v -> openTrailer(clip)),
                            actions.getChildCount() - 1, new LinearLayout.LayoutParams(-2, -2));
                }
                if (found2 == null) {
                    where.setText("Couldn't reach TMDB. Check the Wi-Fi.");
                    return;
                }
                String playing = found2.pkg;
                if (playing != null) {
                    final String pkg = playing;
                    final String plabel = appLabel(pkg);
                    TextView pt = new TextView(ctx);
                    style(pt, 30, semibold);
                    pt.setText("Playing in " + plabel);
                    LinearLayout row = highlightRow(highlightFor(pkg), pkgLogo(pkg), pt, true);
                    row.setOnClickListener(v -> { rememberTitle(it); closeLayer(); launch(pkg, plabel); });
                    playRow.addView(row, new LinearLayout.LayoutParams(-2, -2));
                    playRow.setVisibility(View.VISIBLE);
                    where.setVisibility(View.GONE);
                } else {
                    where.setVisibility(View.VISIBLE);
                    where.setText(found2.names.isEmpty() ? "Not streaming in the US right now"
                            : "On " + TextUtils.join(", ", found2.names.subList(0, Math.min(3, found2.names.size()))) + ", not installed here");
                }
                // The alternatives are always offered, whatever app the title is on: the installed ones among these.
                for (String want : new String[] {"streamflix", "anilab", "anilili"}) {
                    for (Map.Entry<String, String> e : apps.entrySet()) {
                        if (!e.getValue().toLowerCase().contains(want) || e.getKey().equals(playing)) continue;
                        final String pkg = e.getKey(), label = e.getValue();
                        actions.addView(actionTile("Try " + label, v -> { rememberTitle(it); closeLayer(); launch(pkg, label); }),
                                actions.getChildCount() - 1, new LinearLayout.LayoutParams(-2, -2));
                        break;
                    }
                }
                if (close.hasFocus() && actions.getChildCount() > 1) actions.getChildAt(0).requestFocus();
            });
        }).start();
    }

    // A titled horizontal strip on a page. Returns its row; the label and strip can be hidden with hideStrip().
    LinearLayout addStrip(LinearLayout page, String title, int topMargin) {
        TextView label = new TextView(ctx);
        style(label, 30, semibold);
        label.setAlpha(0.85f);
        label.setText(title);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = px(topMargin);
        page.addView(label, llp);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        HorizontalScrollView sc = new HorizontalScrollView(ctx);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipChildren(false);
        sc.addView(row, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(14);
        page.addView(sc, slp);
        row.setTag(label);
        return row;
    }

    void hideStrip(LinearLayout row) {
        ((View) row.getParent()).setVisibility(View.GONE);
        ((View) row.getTag()).setVisibility(View.GONE);
    }

    // The crew and creators, from a person record in a cast or crew list. Null when there's no name.
    static Person personFrom(JSONObject o, String job) {
        String name = o.optString("name", "");
        if (name.isEmpty()) return null;
        Person p = new Person();
        p.id = o.optInt("id");
        p.name = name;
        p.character = o.optString("character", "");
        p.photo = o.optString("profile_path", "");
        p.job = job;
        return p;
    }

    // Crew for the title, the jobs that matter most first (director, writers, composers, producers), one per person.
    static List<Person> crewPeople(JSONArray a) {
        List<Person> out = new ArrayList<>();
        if (a == null) return out;
        String[] order = {"Director", "Writer", "Screenplay", "Story", "Director of Photography", "Original Music Composer", "Producer", "Executive Producer"};
        Set<Integer> seen = new HashSet<>();
        for (String want : order) for (int i = 0; i < a.length() && out.size() < 12; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !want.equals(o.optString("job")) || !seen.add(o.optInt("id"))) continue;
            Person p = personFrom(o, want);
            if (p != null) out.add(p);
        }
        return out;
    }

    // IMDb and Rotten Tomatoes scores from OMDb, by IMDb id. out[0] IMDb, out[1] Rotten Tomatoes, out[2] the
    // reason when a score is missing (empty when all went well).
    String[] ratingsFor(String imdbId, String key) {
        String[] out = {"", "", "", ""}; // IMDb, Rotten Tomatoes, the reason when missing, awards
        if (key == null) { out[2] = "no key"; return out; }
        if (imdbId == null || imdbId.isEmpty()) { out[2] = "no IMDb id"; return out; }
        try {
            String body = get("https://www.omdbapi.com/?i=" + imdbId + "&apikey=" + key, null);
            JSONObject o = new JSONObject(body);
            if ("False".equals(o.optString("Response"))) {
                out[2] = "OMDb: " + o.optString("Error", "no data");
            } else {
                String imdb = o.optString("imdbRating", "");
                out[0] = "N/A".equals(imdb) ? "" : imdb;
                String awards = o.optString("Awards", "");
                out[3] = "N/A".equals(awards) ? "" : awards;
                JSONArray rs = o.optJSONArray("Ratings");
                if (rs != null) for (int i = 0; i < rs.length(); i++) {
                    JSONObject r = rs.getJSONObject(i);
                    if ("Rotten Tomatoes".equals(r.optString("Source"))) out[1] = r.optString("Value");
                }
            }
        } catch (Exception e) {
            out[2] = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        Log.d("FireCasa", "OMDb " + imdbId + " imdb=" + out[0] + " rt=" + out[1] + " err=" + out[2]);
        return out;
    }

    // Full details: the first and latest episode, episode length, creators, network, release date, language.
    static String factsText(JSONObject d, boolean tv) {
        List<String> lines = new ArrayList<>();
        if (tv) {
            String first = d.optString("first_air_date", "");
            if (!first.isEmpty()) lines.add("First episode: " + prettyDate(first));
            String last = d.optString("last_air_date", "");
            if (!last.isEmpty() && !"null".equals(last)) lines.add("Latest episode: " + prettyDate(last));
            JSONArray runs = d.optJSONArray("episode_run_time");
            if (runs != null && runs.length() > 0 && runs.optInt(0) > 0) lines.add("Episode length: " + runs.optInt(0) + " min");
            String creators = namesOf(d.optJSONArray("created_by"));
            if (!creators.isEmpty()) lines.add("Created by: " + creators);
            String network = namesOf(d.optJSONArray("networks"));
            if (!network.isEmpty()) lines.add("Network: " + network);
        } else {
            String released = d.optString("release_date", "");
            if (!released.isEmpty()) lines.add("Released: " + prettyDate(released));
            long budget = d.optLong("budget"), revenue = d.optLong("revenue");
            if (budget > 0) lines.add("Budget: " + money(budget));
            if (revenue > 0) lines.add("Revenue: " + money(revenue));
        }
        String lang = d.optString("original_language", "");
        if (!lang.isEmpty()) lines.add("Original language: " + lang.toUpperCase(Locale.US));
        return TextUtils.join("\n", lines);
    }

    // The free part under the title: seasons and episodes (or runtime), genres, and the status tag.
    static String freeText(JSONObject d, boolean tv) {
        List<String> lines = new ArrayList<>();
        if (tv) {
            int seasons = d.optInt("number_of_seasons"), eps = d.optInt("number_of_episodes");
            lines.add(seasons + (seasons == 1 ? " season" : " seasons") + "  ·  " + eps + (eps == 1 ? " episode" : " episodes"));
        } else {
            int mins = d.optInt("runtime");
            if (mins > 0) lines.add(mins + " min");
        }
        String genres = namesOf(d.optJSONArray("genres"));
        if (!genres.isEmpty()) lines.add("Genres: " + genres);
        String status = d.optString("status", "");
        if (!status.isEmpty()) lines.add("Status: " + status);
        return TextUtils.join("\n", lines);
    }

    static String money(long v) {
        return String.format(Locale.US, "$%,d", v);
    }

    static String namesOf(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length() && out.size() < 4; i++) {
            JSONObject o = a.optJSONObject(i);
            String n = o == null ? "" : o.optString("name", "");
            if (!n.isEmpty()) out.add(n);
        }
        return TextUtils.join(", ", out);
    }

    static String prettyDate(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    // One season, as a poster. OK opens its episodes.
    View seasonCard(final Item show, JSONObject s, final int n, final String label) {
        FrameLayout t = focusTile(18, 1.06f);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded(img, 18);
        loadStill(s.optString("poster_path", ""), "w342", img);
        col.addView(img, new LinearLayout.LayoutParams(px(POSTER_W), px(POSTER_H)));
        TextView name = new TextView(ctx);
        style(name, 26, semibold);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(px(POSTER_W));
        name.setText(label + "  ·  " + s.optInt("episode_count") + " eps");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(10);
        col.addView(name, nlp);
        t.addView(col, new FrameLayout.LayoutParams(-2, -2));
        t.setOnClickListener(v -> openSeason(show, n, label, t));
        return t;
    }

    // A season's episodes, each with its still, number, name, air date and a line of description.
    void openSeason(final Item show, final int number, final String label, View anchor) {
        final String token = asset("tmdb_token.txt");
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xD9000000), new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(ctx);
        style(title, 56, semibold);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(show.title + "  ·  " + label);
        final TextView status = new TextView(ctx);
        style(status, 28, medium);
        status.setAlpha(0.8f);
        status.setText("Loading episodes…");
        final LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        HorizontalScrollView sc = new HorizontalScrollView(ctx);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipChildren(false);
        sc.addView(strip, new FrameLayout.LayoutParams(-2, -2));
        final View close = actionTile("Close", v -> closeLayer());
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(140), px(130), px(140), px(60));
        page.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = px(12);
        page.addView(status, stp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(24);
        page.addView(close, clp);
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(-1, -2);
        sslp.topMargin = px(30);
        page.addView(sc, sslp);
        ScrollView pageScroll = new ScrollView(ctx);
        pageScroll.setVerticalScrollBarEnabled(false);
        pageScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pageScroll.setClipChildren(false);
        pageScroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        layer.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
        openLayer(layer, anchor, close);

        new Thread(() -> {
            final List<JSONObject> eps = new ArrayList<>();
            String err = null;
            try {
                JSONArray a = new JSONObject(get(TMDB + "tv/" + show.id + "/season/" + number + "?language=en-US", "Bearer " + token))
                        .optJSONArray("episodes");
                if (a != null) for (int i = 0; i < a.length(); i++) eps.add(a.getJSONObject(i));
            } catch (Exception ignored) {
                err = "Couldn't load the episodes.";
            }
            final String error = err;
            post(() -> {
                if (!layers.contains(layer)) return;
                status.setText(error != null ? error : eps.isEmpty() ? "No episodes listed yet." : eps.size() + " episodes");
                for (JSONObject ep : eps) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(480), -2);
                    if (strip.getChildCount() > 0) lp.leftMargin = px(24);
                    strip.addView(episodeCard(ep), lp);
                }
                if (close.hasFocus() && strip.getChildCount() > 0) strip.getChildAt(0).requestFocus();
            });
        }).start();
    }

    // One episode: its still, "E3  ·  Name", the air date and the start of its description.
    View episodeCard(JSONObject ep) {
        FrameLayout t = focusTile(18, 1.06f);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded(img, 18);
        loadStill(ep.optString("still_path", ""), "w500", img);
        col.addView(img, new LinearLayout.LayoutParams(px(480), px(270)));
        TextView name = new TextView(ctx);
        style(name, 26, semibold);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(px(480));
        name.setText("E" + ep.optInt("episode_number") + "  ·  " + ep.optString("name", ""));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(10);
        col.addView(name, nlp);
        String air = ep.optString("air_date", "");
        if (!air.isEmpty() && !"null".equals(air)) {
            TextView when = new TextView(ctx);
            style(when, 22, medium);
            when.setAlpha(0.7f);
            when.setText(prettyDate(air));
            col.addView(when, new LinearLayout.LayoutParams(-1, -2));
        }
        String over = ep.optString("overview", "");
        if (!over.isEmpty()) {
            TextView desc = new TextView(ctx);
            style(desc, 22, medium);
            desc.setAlpha(0.85f);
            desc.setMaxLines(3);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            desc.setText(over);
            col.addView(desc, new LinearLayout.LayoutParams(-1, -2));
        }
        t.addView(col, new FrameLayout.LayoutParams(px(480), -2));
        return t;
    }

    // A person from a cast list: their TMDB id, name, character, and profile photo path.
    static class Person { int id; String name, character, photo, job; }

    // A cast member's face and name. OK opens their movies and shows.
    View castTile(final Person p) {
        final FrameLayout t = focusTile(28, 1.06f);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(px(14), px(12), px(14), px(14));
        ImageView face = new ImageView(ctx);
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        face.setBackgroundColor(0xFF2A2A2E); // shows until the photo arrives
        rounded(face, 90);
        loadProfile(p.photo, face, 180);
        col.addView(face, new LinearLayout.LayoutParams(px(180), px(180)));
        TextView name = new TextView(ctx);
        style(name, 26, semibold);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setMaxWidth(px(200));
        name.setText(p.name);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(10);
        col.addView(name, nlp);
        if (p.job != null) {
            TextView role = new TextView(ctx);
            style(role, 22, medium);
            role.setAlpha(0.7f);
            role.setGravity(Gravity.CENTER);
            role.setSingleLine();
            role.setMaxWidth(px(200));
            role.setText(p.job);
            col.addView(role, new LinearLayout.LayoutParams(-2, -2));
        }
        t.addView(col, new FrameLayout.LayoutParams(-2, -2));
        t.setOnClickListener(v -> openPerson(p, t));
        return t;
    }

    // A still or backdrop from TMDB, cached in the stills folder. The ImageView crops it to fit.
    void loadStill(final String path, final String size, final ImageView img) {
        if (path == null || path.isEmpty() || "null".equals(path)) return;
        img.setTag(path);
        final File dir = new File(ctx.getCacheDir(), "stills");
        pool.execute(() -> {
            try {
                dir.mkdirs();
                File f = new File(dir, size + path.replace("/", ""));
                if (!f.exists()) download("https://image.tmdb.org/t/p/" + size + path, null, f);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b == null) {
                    f.delete();
                    return;
                }
                img.post(() -> { if (path.equals(img.getTag())) img.setImageBitmap(b); });
            } catch (Exception ignored) {}
        });
    }

    // A cast member's face: TMDB's profile photo, cropped to a square and cached like the posters.
    void loadProfile(final String path, final ImageView img, final int size) {
        if (path == null || path.isEmpty() || "null".equals(path)) return;
        img.setTag(path);
        final String key = "face" + size + path;
        Bitmap cached = posters.get(key);
        if (cached != null) {
            img.setImageBitmap(cached);
            return;
        }
        final int dim = px(size);
        final String sizeSeg = size > 200 ? "w342" : "w185";
        final File dir = new File(ctx.getCacheDir(), "faces");
        pool.execute(() -> {
            try {
                dir.mkdirs();
                File f = new File(dir, path.replace("/", ""));
                if (!f.exists()) download("https://image.tmdb.org/t/p/" + sizeSeg + path, null, f);
                else f.setLastModified(System.currentTimeMillis());
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap raw = BitmapFactory.decodeFile(f.getPath(), o);
                if (raw == null) {
                    f.delete();
                    return;
                }
                float k = (float) dim / Math.min(raw.getWidth(), raw.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(raw, Math.max(dim, Math.round(raw.getWidth() * k)),
                        Math.max(dim, Math.round(raw.getHeight() * k)), true);
                raw.recycle();
                Bitmap sized = Bitmap.createBitmap(scaled, (scaled.getWidth() - dim) / 2, (scaled.getHeight() - dim) / 2, dim, dim);
                if (sized != scaled) scaled.recycle();
                posters.put(key, sized);
                img.post(() -> { if (path.equals(img.getTag())) img.setImageBitmap(sized); });
            } catch (Exception ignored) {}
        });
    }

    // A cast member's page: their face, a short biography, then their movies, shows and crew work, each on its own row.
    void openPerson(final Person p, View anchor) {
        final String token = asset("tmdb_token.txt");
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xD9000000), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        ImageView face = new ImageView(ctx);
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        face.setBackgroundColor(0xFF2A2A2E);
        rounded(face, 130);
        loadProfile(p.photo, face, 260);
        head.addView(face, new LinearLayout.LayoutParams(px(260), px(260)));

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(1100), -2);
        ilp.leftMargin = px(60);
        head.addView(info, ilp);

        TextView name = new TextView(ctx);
        style(name, 56, semibold);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(p.name);
        info.addView(name, new LinearLayout.LayoutParams(-1, -2));

        if (!p.character.isEmpty()) {
            TextView role = new TextView(ctx);
            style(role, 30, medium);
            role.setAlpha(0.7f);
            role.setText("as " + p.character);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = px(8);
            info.addView(role, rlp);
        }

        final TextView bio = new TextView(ctx);
        style(bio, 32, medium);
        bio.setMaxLines(6);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        bio.setText("Loading…");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = px(20);
        info.addView(bio, blp);

        final View close = actionTile("Close", v -> closeLayer());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(24);
        info.addView(close, clp);

        final TextView status = new TextView(ctx);
        style(status, 30, semibold);
        status.setAlpha(0.85f);
        status.setText("Loading movies, shows and crew…");
        final LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(head, new LinearLayout.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -1);
        plp.leftMargin = px(140);
        plp.rightMargin = px(140);
        plp.topMargin = px(120);
        plp.bottomMargin = px(40);
        layer.addView(page, plp);
        openLayer(layer, anchor, close);

        new Thread(() -> {
            String bioText = "";
            final List<Item> movies = new ArrayList<>(), shows = new ArrayList<>(), anime = new ArrayList<>(),
                    cartoons = new ArrayList<>(), crew = new ArrayList<>();
            try {
                bioText = new JSONObject(get(TMDB + "person/" + p.id + "?language=en-US", "Bearer " + token)).optString("biography", "");
            } catch (Exception ignored) {}
            try {
                JSONObject credits = new JSONObject(get(TMDB + "person/" + p.id + "/combined_credits?language=en-US", "Bearer " + token));
                JSONArray cast = credits.optJSONArray("cast");
                movies.addAll(works(cast, "movie"));
                shows.addAll(works(cast, "tv"));
                anime.addAll(works(cast, "anime"));
                cartoons.addAll(works(cast, "cartoon"));
                crew.addAll(works(credits.optJSONArray("crew"), null));
            } catch (Exception ignored) {}
            final String b = bioText;
            post(() -> {
                if (!layers.contains(layer)) return;
                bio.setText(b.isEmpty() ? "No biography yet." : b);
                content.removeView(status);
                if (movies.isEmpty() && shows.isEmpty() && anime.isEmpty() && cartoons.isEmpty() && crew.isEmpty()) {
                    TextView none = new TextView(ctx);
                    style(none, 30, semibold);
                    none.setAlpha(0.85f);
                    none.setText("No movies or shows found.");
                    content.addView(none, new LinearLayout.LayoutParams(-1, -2));
                }
                View first = null;
                List<String> titles = Arrays.asList("Movies", "Shows", "Anime", "Cartoons", "Crew");
                List<List<Item>> rowsItems = Arrays.asList(movies, shows, anime, cartoons, crew);
                for (int r = 0; r < titles.size(); r++) {
                    View f = worksRow(content, titles.get(r), rowsItems.get(r));
                    if (first == null) first = f;
                }
                if (close.hasFocus() && first != null) first.requestFocus();
            });
        }).start();
    }

    // Their titles from a combined credits list, most popular first, one entry per title. kind: "movie" or "tv"
    // (animation left out), "anime" (Japanese animation), "cartoon" (other animation), or null for everything.
    static List<Item> works(JSONArray a, String kind) {
        List<Item> out = new ArrayList<>();
        if (a == null) return out;
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String m = o.optString("media_type", "");
            String poster = o.optString("poster_path", "");
            if (!m.equals("movie") && !m.equals("tv")) continue;
            if (poster.isEmpty() || "null".equals(poster)) continue;
            boolean animated = false;
            JSONArray genres = o.optJSONArray("genre_ids");
            if (genres != null) for (int g = 0; g < genres.length(); g++) if (genres.optInt(g) == 16) animated = true;
            boolean japanese = "ja".equals(o.optString("original_language"));
            boolean keep;
            if (kind == null) keep = true;
            else if (kind.equals("anime")) keep = animated && japanese;
            else if (kind.equals("cartoon")) keep = animated && !japanese;
            else keep = m.equals(kind) && !animated;
            if (keep) rows.add(o);
        }
        Collections.sort(rows, (x, y) -> Double.compare(y.optDouble("popularity", 0), x.optDouble("popularity", 0)));
        Set<String> seen = new HashSet<>();
        for (JSONObject o : rows) {
            boolean tv = "tv".equals(o.optString("media_type"));
            if (!seen.add((tv ? "tv" : "movie") + o.optInt("id"))) continue;
            Item item = new Item();
            item.id = o.optInt("id");
            item.tv = tv;
            item.title = o.optString(tv ? "name" : "title");
            item.poster = o.optString("poster_path");
            String date = o.optString(tv ? "first_air_date" : "release_date", "");
            item.year = date.length() >= 4 ? date.substring(0, 4) : "";
            out.add(item);
            if (out.size() >= 40) break;
        }
        return out;
    }

    // A titled row of posters that slides sideways. Returns its first poster, or null when there are none.
    View worksRow(LinearLayout into, String title, List<Item> items) {
        if (items.isEmpty()) return null;
        TextView label = new TextView(ctx);
        style(label, 30, semibold);
        label.setAlpha(0.85f);
        label.setText(title);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = px(36);
        into.addView(label, llp);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        View first = null;
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(POSTER_W), px(POSTER_H + 64));
            if (i > 0) lp.leftMargin = px(POSTER_GAP);
            View card = posterCard(items.get(i), 0);
            if (first == null) first = card;
            row.addView(card, lp);
        }
        HorizontalScrollView sc = new HorizontalScrollView(ctx);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sc.setClipChildren(false);
        sc.addView(row, new FrameLayout.LayoutParams(-2, -2));
        into.addView(sc, new LinearLayout.LayoutParams(-1, -2));
        loopRow(row);
        return first;
    }

    // "Open trailer": the YouTube app, or the web page when it isn't installed.
    void openTrailer(String key) {
        if (!start(new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + key))))
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key)));
    }

    // A focusable row. When focused, its text takes the highlight colour, and a white logo takes it too.
    LinearLayout highlightRow(final int highlight, final View leading, final TextView text, final boolean tint) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setPadding(px(16), px(8), px(24), px(8));
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(px(20));
        pill.setColor(0x00000000);
        row.setBackground(pill);
        if (leading != null) row.addView(leading);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        if (leading != null) tlp.leftMargin = px(14);
        row.addView(text, tlp);
        row.setOnFocusChangeListener((v, has) -> {
            text.setTextColor(has ? highlight : 0xFFFFFFFF);
            if (tint && leading instanceof ImageView)
                ((ImageView) leading).setColorFilter(has ? new PorterDuffColorFilter(highlight, PorterDuff.Mode.SRC_IN) : null);
            pill.setColor(has ? 0x26FFFFFF : 0x00000000);
        });
        return row;
    }

    // A rating's badge: a solid colour with its name in it, like the service's own logo.
    TextView badge(String text, int bg, int fg) {
        TextView b = new TextView(ctx);
        style(b, 26, semibold);
        b.setTextColor(fg);
        b.setText(text);
        b.setPadding(px(12), px(4), px(12), px(4));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(8));
        g.setColor(bg);
        b.setBackground(g);
        return b;
    }

    // TMDB's user score as a ring: a green arc for the percentage, with the number in the middle.
    View scoreRing(final int percent) {
        return new View(ctx) {
            final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF box = new RectF();
            {
                track.setStyle(Paint.Style.STROKE);
                track.setStrokeWidth(px(6));
                track.setColor(0x33FFFFFF);
                arc.setStyle(Paint.Style.STROKE);
                arc.setStrokeWidth(px(6));
                arc.setStrokeCap(Paint.Cap.ROUND);
                arc.setColor(0xFF21D07A);
                num.setColor(0xFFFFFFFF);
                num.setTextSize(px(26));
                num.setTextAlign(Paint.Align.CENTER);
                num.setTypeface(semibold);
            }
            @Override protected void onDraw(Canvas c) {
                float pad = px(4);
                box.set(pad, pad, getWidth() - pad, getHeight() - pad);
                c.drawArc(box, 0, 360, false, track);
                c.drawArc(box, -90, 360f * percent / 100f, false, arc);
                c.drawText(percent + "%", getWidth() / 2f, getHeight() / 2f + px(9), num);
            }
        };
    }

    // Shows the media or the social rows (only those with something in them) and marks the chosen tab.
    void showTabs(boolean media, LinearLayout tabs, List<LinearLayout> mediaRows, List<LinearLayout> socialRows) {
        for (LinearLayout r : mediaRows) setRowShown(r, media);
        for (LinearLayout r : socialRows) setRowShown(r, !media);
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) ((ViewGroup) tabs.getChildAt(i)).getChildAt(0);
            String base = i == 0 ? "Media" : "Social";
            t.setText((i == 0) == media ? base + "  ●" : base);
        }
    }

    void setRowShown(LinearLayout row, boolean on) {
        boolean show = on && row.getChildCount() > 0;
        int vis = show ? View.VISIBLE : View.GONE;
        ((View) row.getParent()).setVisibility(vis);
        ((View) row.getTag()).setVisibility(vis);
    }

    // The Rotten Tomatoes logo, from assets/logos.
    ImageView rtLogo() {
        ImageView img = new ImageView(ctx);
        Bitmap b = logo("rottentomatoes");
        if (b != null) img.setImageBitmap(b);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setLayoutParams(new LinearLayout.LayoutParams(px(56), px(56)));
        return img;
    }

    TextView valueText(String text, int color) {
        TextView t = new TextView(ctx);
        style(t, 30, semibold);
        t.setTextColor(0xFFFFFFFF);
        t.setText(text);
        return t;
    }

    // The service's white logo as a small mini logo, or its app icon when it has no logo.
    ImageView pkgLogo(String pkg) {
        ImageView img = new ImageView(ctx);
        String name = LOGOS.get(pkg);
        Bitmap b = name != null ? logo(name) : null;
        if (b != null) img.setImageBitmap(b);
        else try { img.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (PackageManager.NameNotFoundException e) { return null; }
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setLayoutParams(new LinearLayout.LayoutParams(px(64), px(40)));
        return img;
    }

    // The highlight colour for a service: its brand colour, white for the ones with no brand colour.
    static int highlightFor(String pkg) {
        String name = LOGOS.get(pkg);
        if (name == null) return 0xFFFFFFFF;
        switch (name) {
            case "netflix": return 0xFFE50914;
            case "primevideo": return 0xFF00A8E1;
            case "youtube": return 0xFFFF0000;
            case "crunchyroll": return 0xFFF47521;
            case "max": return 0xFF1F5CFF;
            case "paramountplus": return 0xFF0064FF;
            case "spotify": return 0xFF1DB954;
            case "twitch": return 0xFF9146FF;
            case "tubi": return 0xFFFA382F;
            case "jellyfin": return 0xFF00A4DC;
            case "kodi": return 0xFF17B2E7;
            default: return 0xFFFFFFFF;
        }
    }

    // A button for the title page, focusable like the rest of the screen.
    View actionTile(String text, View.OnClickListener click) {
        FrameLayout t = focusTile(32, 1.04f);
        TextView label = new TextView(ctx);
        style(label, 30, medium);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setPadding(px(36), px(16), px(36), px(16));
        t.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        t.setOnClickListener(click);
        return t;
    }

    // Where a title streams in the US: the first installed app that plays it, and every provider's name.
    static class Where { String pkg; final List<String> names = new ArrayList<>(); }

    Where whereToWatch(Item it, String token) throws Exception {
        Where w = new Where();
        JSONObject us = new JSONObject(get(TMDB + (it.tv ? "tv/" : "movie/") + it.id + "/watch/providers", "Bearer " + token))
                .getJSONObject("results").optJSONObject("US");
        if (us != null) for (String kind : new String[] {"flatrate", "free", "ads"}) {
            JSONArray a = us.optJSONArray(kind);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                String n = a.getJSONObject(i).getString("provider_name");
                if (!w.names.contains(n)) w.names.add(n);
                if (w.pkg == null) w.pkg = providerApp(n);
            }
        }
        return w;
    }

    // TMDB provider names (lowercased, letters and digits only) to the Fire TV apps that play them.
    static final String[][] PROVIDERS = {
        {"netflix", "com.netflix.ninja"},
        {"primevideo", "com.amazon.firebat", "com.amazon.amazonvideo.livingroom"},
        {"disneyplus", "com.disney.disneyplus"},
        {"hulu", "com.hulu.plus", "com.hulu.livingroomplus"},
        {"hbomax", "com.wbd.stream", "com.hbo.hbonow"},
        {"max", "com.wbd.stream", "com.hbo.hbonow"},
        {"appletv", "com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv"},
        {"paramount", "com.cbs.ott"},
        {"peacock", "com.peacocktv.peacockandroid"},
        {"crunchyroll", "com.crunchyroll.crunchyroid"},
        {"tubi", "com.tubitv", "com.tubitv.ott"},
        {"plutotv", "tv.pluto.android"},
        {"youtube", "com.amazon.firetv.youtube", "com.google.android.youtube.tv"},
    };

    String providerApp(String provider) {
        String n = provider.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String[] e : PROVIDERS) {
            if (!n.contains(e[0])) continue;
            for (int i = 1; i < e.length; i++) if (isInstalled(e[i])) return e[i];
        }
        return null;
    }

    String appLabel(String p) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(); } catch (Exception e) { return p; }
    }

    // ---- Unsplash wallpaper ----
    // Needs app/assets/unsplash_key.txt (an Unsplash Access Key). With it, Unsplash is the default
    // wallpaper: a new random photo every hour, or whenever the wallpaper button is pressed.

    static final String UNSPLASH = "unsplash";
    // The photo categories you can pick from in FireCasa settings: name, and the search it stands for.
    static final String[][] PHOTO_CATEGORIES = {
        {"Landscapes", "landscape"}, {"Mountains", "mountains"}, {"Ocean", "ocean"}, {"City at night", "city night"},
        {"Architecture", "architecture"}, {"Forest", "forest"}, {"Desert", "desert"}, {"Aurora", "aurora"},
        {"Lakes", "lake"}, {"Coast", "coast"}, {"Space", "galaxy"}, {"Animals", "wildlife"}, {"Flowers", "flowers"},
        {"Snow", "snow"},
    };
    static final int[] PHOTO_INTERVALS = {15, 60, 360, 1440, 0}; // minutes between photos; 0 is never
    static final String[] PHOTO_INTERVAL_NAMES = {"Every 15 minutes", "Every hour", "Every 6 hours", "Every day", "Never"};

    // How long a photo stays before the next one, in ms.
    long photoEvery() {
        int m = prefs.getInt("photoEvery", 60);
        return m == 0 ? Long.MAX_VALUE / 2 : m * 60_000L;
    }

    // The search for the next photo: a random one from the categories picked (all of them when none are saved).
    String pickTopic() {
        List<String> picked = new ArrayList<>();
        String saved = prefs.getString("photoTopics", "");
        for (String[] c : PHOTO_CATEGORIES) if (saved.isEmpty() || saved.contains("|" + c[0] + "|")) picked.add(c[1]);
        return picked.get(new Random().nextInt(picked.size()));
    }

    File unsplashFile() {
        return new File(ctx.getFilesDir(), "unsplash.jpg");
    }

    // Runs on the wallpaper thread. Returns whether there's a photo to show.
    boolean fetchUnsplash(boolean force) {
        synchronized (HomeScreen.class) { // the app and the overlay can both ask at once
            File f = unsplashFile();
            if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < photoEvery()) return true;
            String key = asset("unsplash_key.txt");
            if (key == null) return f.exists();
            try {
                String q = pickTopic();
                JSONObject j = new JSONObject(get("https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&query="
                        + Uri.encode(q), "Client-ID " + key));
                String url = j.getJSONObject("urls").getString("raw") + "&w=" + screenW + "&h=" + screenH + "&fit=crop&q=80&fm=jpg";
                File part = new File(f.getPath() + ".part");
                download(url, null, part);
                if (!part.renameTo(f)) return f.exists();
                prefs.edit().putString("unsplash_credit", j.getJSONObject("user").getString("name")).apply();
                // Unsplash's API terms ask apps to report each photo they use.
                try { get(j.getJSONObject("links").getString("download_location"), "Client-ID " + key); } catch (Exception ignored) {}
                return true;
            } catch (Exception e) {
                return f.exists();
            }
        }
    }

    void showCredit(String key) {
        String who = prefs.getString("unsplash_credit", null);
        String text = UNSPLASH.equals(key) && who != null ? "Photo by " + who + " on Unsplash" : "";
        credit.setText(text);
        credit.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE); // GONE leaves no gap under the date
    }

    // ---- Network and assets ----

    static final Map<String, String> assetText = new HashMap<>();

    // A key: the copy sent to the TV by firecasa.sh (see importKeys), else a file in app/assets, else null.
    String asset(String name) {
        synchronized (assetText) {
            if (assetText.containsKey(name)) return assetText.get(name);
            String v = null;
            try (InputStream in = new FileInputStream(new File(new File(ctx.getFilesDir(), "keys"), name))) {
                v = new String(readAll(in), "UTF-8").trim();
            } catch (Exception ignored) {}
            if (v == null || v.isEmpty()) {
                try (InputStream in = ctx.getAssets().open(name)) {
                    v = new String(readAll(in), "UTF-8").trim();
                } catch (Exception ignored) {}
            }
            if (v != null && v.isEmpty()) v = null;
            assetText.put(name, v);
            return v;
        }
    }

    // firecasa.sh keys sends the keys to /data/local/tmp/firecasa-keys and starts the app with --es keys
    // that folder. Since Android 11 adb can't write into the app's folder, so the app copies them in here.
    // An empty file means the key is off, so its copy is deleted.
    void importKeys(String dir) {
        File mine = new File(ctx.getFilesDir(), "keys");
        mine.mkdirs();
        for (String name : new String[] {"tmdb_token.txt", "omdb_key.txt", "unsplash_key.txt"}) {
            File from = new File(dir, name);
            if (!from.exists()) continue;
            try (InputStream in = new FileInputStream(from)) {
                byte[] bytes = readAll(in);
                File to = new File(mine, name);
                if (new String(bytes, "UTF-8").trim().isEmpty()) to.delete();
                else try (OutputStream out = new FileOutputStream(to)) { out.write(bytes); }
            } catch (Exception ignored) {}
        }
        synchronized (assetText) { assetText.clear(); }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static HttpURLConnection open(String url, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        return c;
    }

    static String get(String url, String auth) throws Exception {
        HttpURLConnection c = open(url, auth);
        c.setRequestProperty("Accept", "application/json");
        try (InputStream in = c.getInputStream()) {
            return new String(readAll(in), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static void download(String url, String auth, File to) throws Exception {
        HttpURLConnection c = open(url, auth);
        File part = new File(to.getPath() + ".dl");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(to)) throw new java.io.IOException("rename failed");
    }

    void launch(final String p, String label) {
        markOpened(p);
        // The Appstore's screens are Amazon's launcher on Fire OS 7: leave them alone until Home.
        if (p.equals(HomeService.APPSTORE)) {
            HomeService.pause(false);
            startApp(p);
            return;
        }
        // On the overlay, in the 5 seconds after Home, only Amazon's launcher can open apps right away.
        if (ctx instanceof HomeService) ((HomeService) ctx).launchThroughAmazon(label, () -> startApp(p), 0);
        else startApp(p);
    }

    void startApp(String p) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(p);
        if (i == null) i = pm.getLaunchIntentForPackage(p);
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            showCaption("Couldn't open that app");
        }
    }

    // ---- Tiles ----

    View tile(final String p, String label, int w, int h, final boolean inDock) {
        FrameLayout t = focusTile(32, 1.04f);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(ctx);
        String logo = LOGOS.get(p);
        if (logo != null) {
            icon.setImageBitmap(logo(logo));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            col.addView(icon, new LinearLayout.LayoutParams(px(150), px(78)));
        } else {
            try { icon.setImageDrawable(pm.getApplicationIcon(p)); } catch (PackageManager.NameNotFoundException ignored) {}
            ColorMatrix grey = new ColorMatrix();
            grey.setSaturation(0);
            icon.setColorFilter(new ColorMatrixColorFilter(grey));
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            rounded(icon, 20);
            col.addView(icon, new LinearLayout.LayoutParams(px(82), px(82)));
        }
        TextView name = new TextView(ctx);
        style(name, 32, medium);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(label);
        name.setGravity(Gravity.CENTER);
        name.setMaxWidth(px(w - 36));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(22);
        col.addView(name, nlp);
        t.addView(col, new FrameLayout.LayoutParams(-1, -1));

        t.setOnClickListener(v -> launch(p, label));
        t.setOnLongClickListener(v -> { appMenu(v, p, inDock); return true; });
        t.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_MENU) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) appMenu(v, p, inDock);
            return true;
        });
        return t;
    }

    View textTile(String text, int w, int h) {
        FrameLayout t = focusTile(32, 1.04f);
        TextView label = new TextView(ctx);
        style(label, 32, medium);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        t.addView(label, new FrameLayout.LayoutParams(-1, -1));
        return t;
    }

    // A focusable tile: a soft glass pill fades in behind it and it lifts slightly when focused.
    FrameLayout focusTile(int radius, final float lift) {
        final FrameLayout t = new FrameLayout(ctx);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true); // TVs have no touch; this just lets focus land after adb or a reboot
        t.setClickable(true);
        // The pill is the tile's background, so it never changes the tile's size.
        final GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x33FFFFFF);
        pill.setCornerRadius(px(radius));
        pill.setAlpha(0);
        t.setBackground(pill);
        t.setOnFocusChangeListener((v, has) -> {
            if (has && t.getParent() == dockRow) lastDock = dockRow.indexOfChild(t);
            ObjectAnimator.ofInt(pill, "alpha", pill.getAlpha(), has ? 255 : 0).setDuration(140).start();
            t.animate().scaleX(has ? lift : 1).scaleY(has ? lift : 1).setDuration(180).setInterpolator(ease).start();
        });
        return t;
    }

    // The power symbol, drawn in code: a ring open at the top, with a short line through the gap.
    Bitmap powerIcon() {
        int d = 80;
        Bitmap b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(0xFFFFFFFF);
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(7);
        pt.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(new RectF(14, 14, d - 14, d - 14), -60, 300, false, pt);
        c.drawLine(d / 2f, 6, d / 2f, d / 2f - 2, pt);
        return b;
    }

    View addIconBitmap(Bitmap icon, int x, View.OnClickListener click) {
        FrameLayout b = focusTile(38, 1.1f);
        b.setId(View.generateViewId());
        ImageView img = new ImageView(ctx);
        img.setImageBitmap(icon);
        b.addView(img, new FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER));
        b.setOnClickListener(click);
        base.addView(b, at(x, 56, 76, 76));
        return b;
    }

    View addIcon(String asset, int x, View.OnClickListener click) {
        FrameLayout b = focusTile(38, 1.1f);
        b.setId(View.generateViewId());
        ImageView img = new ImageView(ctx);
        img.setImageBitmap(bitmap("ui/" + asset + ".png"));
        b.addView(img, new FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER));
        b.setOnClickListener(click);
        base.addView(b, at(x, 56, 76, 76));
        return b;
    }

    Bitmap logo(String name) {
        Bitmap b = logoCache.get(name);
        if (b == null) logoCache.put(name, b = bitmap("logos/" + name + ".png"));
        return b;
    }

    // ---- Menus and the app drawer ----

    interface Pick { void pick(int i); }

    void appMenu(View anchor, final String p, boolean inDock) {
        final int i = dock.indexOf(p);
        if (inDock) {
            menu(anchor, new String[] {"Move", "Remove from Home"}, which -> {
                if (which == 0) {
                    startMove(p);
                    return;
                }
                dock.remove(i);
                saveDock();
                buildDock();
                int at = Math.min(i, dock.size() - 1);
                if (at >= 0 && at < dockRow.getChildCount()) dockRow.getChildAt(at).requestFocus();
            });
        } else {
            menu(anchor, new String[] {i >= 0 ? "Remove from Home" : "Add to Home", "App info"}, which -> {
                if (which == 1) {
                    start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p)));
                    return;
                }
                if (i >= 0) dock.remove(p); else dock.add(p);
                saveDock();
                buildDock();
                showCaption(i >= 0 ? "Removed from Home" : "Added to Home");
            });
        }
    }

    // FireCasa's own settings: the clock, the date, the wallpaper motion, and what it remembers.
    void openFireCasaSettings(View anchor) {
        final boolean c24 = prefs.getBoolean("clock24", android.text.format.DateFormat.is24HourFormat(ctx));
        final boolean date = prefs.getBoolean("clockDate", true);
        final int pos = prefs.getInt("clockPos", 0);
        final int size = prefs.getInt("clockSize", 1);
        final boolean motion = prefs.getBoolean("motion", true);
        String[] items = {
            "Clock: " + (c24 ? "24-hour" : "12-hour"),
            "Date under the clock: " + (date ? "On" : "Off"),
            "Clock position: " + new String[] {"Top left", "Top center", "Top right"}[pos],
            "Clock size: " + new String[] {"Small", "Medium", "Big"}[size],
            "Wallpaper motion: " + (motion ? "On" : "Off"),
            "Background photos",
            "Reset row order",
            "Clear recently watched",
            "Back",
        };
        menu(anchor, items, which -> {
            switch (which) {
                case 0: prefs.edit().putBoolean("clock24", !c24).apply(); tick.run(); break;
                case 1: prefs.edit().putBoolean("clockDate", !date).apply(); tick.run(); break;
                case 2: prefs.edit().putInt("clockPos", (pos + 1) % 3).apply(); placeClock(); tick.run(); break;
                case 3: prefs.edit().putInt("clockSize", (size + 1) % 3).apply(); placeClock(); tick.run(); break;
                case 4: prefs.edit().putBoolean("motion", !motion).apply(); drift(!motion); break;
                case 5: openPhotoSettings(anchor); return;
                case 6:
                    prefs.edit().remove("activity").apply();
                    buildShelves();
                    showCaption("Rows back in the usual order");
                    break;
                case 7:
                    prefs.edit().remove("recentTitles").apply();
                    buildShelves();
                    showCaption("Recently watched cleared");
                    break;
                default: return;
            }
            openFireCasaSettings(anchor);
        });
    }

    // Background photos: how often they change, and which categories they come from.
    void openPhotoSettings(View anchor) {
        final int every = prefs.getInt("photoEvery", 60);
        int at = 0;
        for (int i = 0; i < PHOTO_INTERVALS.length; i++) if (PHOTO_INTERVALS[i] == every) at = i;
        final int current = at;
        String[] items = {"Changes: " + PHOTO_INTERVAL_NAMES[current], "Categories", "Back"};
        menu(anchor, items, which -> {
            if (which == 0) {
                prefs.edit().putInt("photoEvery", PHOTO_INTERVALS[(current + 1) % PHOTO_INTERVALS.length]).apply();
                openPhotoSettings(anchor);
            } else if (which == 1) {
                openPhotoCategories(anchor);
            } else {
                openFireCasaSettings(anchor);
            }
        });
    }

    // Whether a background category is on. Nothing saved yet means every category is on.
    boolean categoryOn(String name) {
        String saved = prefs.getString("photoTopics", "");
        return saved.isEmpty() || saved.contains("|" + name + "|");
    }

    int categoriesOn() {
        int n = 0;
        for (String[] c : PHOTO_CATEGORIES) if (categoryOn(c[0])) n++;
        return n;
    }

    void setCategory(String name, boolean on) {
        List<String> picked = new ArrayList<>();
        for (String[] c : PHOTO_CATEGORIES) if (c[0].equals(name) ? on : categoryOn(c[0])) picked.add(c[0]);
        StringBuilder sb = new StringBuilder();
        for (String s : picked) sb.append('|').append(s).append('|');
        prefs.edit().putString("photoTopics", sb.toString()).apply();
    }

    // Background categories, full screen: a grid of tiles, each on or off. Press one to switch it. At least one
    // stays on, so there's always a photo to search for.
    void openPhotoCategories(View anchor) {
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xD9000000), new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(ctx);
        style(title, 56, semibold);
        title.setText("Background categories");
        layer.addView(title, at(140, 64, -2, -2));
        final View back = actionTile("Back", v -> closeLayer());
        layer.addView(back, at(1480, 64, -2, -2));

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        sv.setPadding(px(140), px(20), px(140), px(80));
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        grid.setClipChildren(false);
        View first = null;
        for (String[] c : PHOTO_CATEGORIES) {
            final String name = c[0];
            final FrameLayout tile = focusTile(22, 1.04f);
            final TextView label = new TextView(ctx);
            style(label, 32, medium);
            label.setPadding(px(30), px(22), px(30), px(22));
            label.setText((categoryOn(name) ? "On   " : "Off  ") + name);
            tile.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            tile.setOnClickListener(v -> {
                boolean on = categoryOn(name);
                if (on && categoriesOn() <= 1) {
                    showCaption("Keep at least one category on");
                    return;
                }
                setCategory(name, !on);
                label.setText((!on ? "On   " : "Off  ") + name);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = px(520);
            lp.height = px(120);
            lp.setMargins(px(12), px(12), px(12), px(12));
            grid.addView(tile, lp);
            if (first == null) first = tile;
        }
        sv.addView(grid);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = px(150);
        layer.addView(sv, slp);
        openLayer(layer, anchor, first);
    }

    // The settings menu. FireCasa's own settings come first; each pick is matched by its label, so the order of
    // the items can change without breaking what they do.
    void openSettingsMenu(View anchor) {
        boolean amazon = isInstalled(HomeService.AMAZON_HOME);
        final String update = Updater.available(ctx);
        final List<String> items = new ArrayList<>();
        items.add("FireCasa settings");
        items.addAll(amazon ? Arrays.asList("Fire TV settings", "Amazon home screen") : Arrays.asList("TV settings"));
        items.add("Turn off");
        if (update != null) items.add("Update to FireCasa " + update);
        menu(anchor, items.toArray(new String[0]), which -> {
            String label = items.get(which);
            if (label.equals("FireCasa settings")) {
                openFireCasaSettings(anchor);
            } else if (label.equals("Turn off")) {
                turnOff();
            } else if (label.startsWith("Update to FireCasa")) {
                showCaption("Downloading FireCasa " + update + "…");
                Updater.install(ctx, () -> showCaption("Couldn't download the update. Check the Wi-Fi and try again."));
            } else if (label.equals("Fire TV settings") || label.equals("TV settings")) {
                HomeService.pause(true);
                // Amazon's own settings screen. Settings.ACTION_SETTINGS goes through Amazon's home screen on Fire OS 7 and 8.
                if (!start(new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
                        .setPackage(HomeService.AMAZON_HOME))) start(new Intent(Settings.ACTION_SETTINGS));
            } else {
                HomeService.pause(false);
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(HomeService.AMAZON_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!start(home)) showCaption("Amazon's home screen isn't available");
                else if (ctx instanceof Activity) ((Activity) ctx).finish();
            }
        });
    }

    // A third-party app can't cut the power. Fire OS's own power menu (Turn off, Restart, Sleep) is the
    // closest, and the accessibility service can open it on Android 9 and later.
    void turnOff() {
        if (Build.VERSION.SDK_INT >= 28 && HomeService.running != null
                && HomeService.running.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) return;
        showCaption("Use the power button on the remote to turn it off");
    }

    boolean start(Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean isInstalled(String p) {
        try { pm.getPackageInfo(p, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // A small glass menu by the anchor. Back closes it.
    void menu(View anchor, String[] items, final Pick pick) {
        FrameLayout layer = new FrameLayout(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202024);
        bg.setCornerRadius(px(30));
        box.setBackground(bg);
        box.setPadding(px(10), px(10), px(10), px(10));
        View first = null;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            FrameLayout row = focusTile(22, 1f);
            TextView t = new TextView(ctx);
            style(t, 28, medium);
            t.setText(items[i]);
            t.setPadding(px(28), px(18), px(48), px(18));
            row.addView(t, new FrameLayout.LayoutParams(-2, -2));
            row.setOnClickListener(v -> { closeLayer(); pick.pick(which); });
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (first == null) first = row;
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Math.max(px(40), Math.min(loc[0], screenW - box.getMeasuredWidth() - px(40)));
        int below = loc[1] + anchor.getHeight() + px(16);
        lp.topMargin = below + box.getMeasuredHeight() < screenH - px(40) ? below : loc[1] - box.getMeasuredHeight() - px(16);
        layer.addView(box, lp);
        openLayer(layer, anchor, first);
    }

    void openDrawer(View anchor) {
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xA6000000), new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(ctx);
        style(title, 48, semibold);
        title.setText("Apps");
        layer.addView(title, at(140, 64, -2, -2));
        TextView hint = new TextView(ctx);
        style(hint, 24, medium);
        hint.setAlpha(0.6f);
        hint.setText("Hold OK or press Menu to add an app to Home");
        layer.addView(hint, at(140, 136, -2, -2));

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        sv.setPadding(px(110), px(20), px(110), px(80));
        GridLayout grid = new GridLayout(ctx);
        grid.setClipChildren(false);
        grid.setColumnCount(6);
        Map<String, String> apps = installed();
        View first = null;
        for (Map.Entry<String, String> a : apps.entrySet()) {
            View t = tile(a.getKey(), a.getValue(), 270, 196, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = px(270);
            lp.height = px(196);
            lp.setMargins(px(5), px(5), px(5), px(5));
            grid.addView(t, lp);
            if (first == null) first = t;
        }
        sv.addView(grid);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = px(190);
        layer.addView(sv, slp);
        openLayer(layer, anchor, first);
    }

    // Layers stack over the home screen; only the top one can take focus.
    void openLayer(ViewGroup layer, View returnFocus, View focus) {
        drift(false);
        layer.setTag(returnFocus);
        layer.setClickable(true);
        top().setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        layers.add(layer);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        layer.setAlpha(0);
        layer.animate().alpha(1).setDuration(140).start();
        if (focus != null) focus.requestFocus();
    }

    void closeLayer() {
        ViewGroup layer = layers.remove(layers.size() - 1);
        root.removeView(layer);
        top().setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = (View) layer.getTag();
        if (back != null && back.isAttachedToWindow()) back.requestFocus();
        else if (layers.isEmpty() && dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
        if (layers.isEmpty()) drift(true);
        if (layers.isEmpty() && appsChanged) buildDock();
        if (layers.isEmpty() && (ctx instanceof HomeService || ctx instanceof HomeWatcher)) HomeService.menusClosed();
    }

    ViewGroup top() {
        return layers.isEmpty() ? base : layers.get(layers.size() - 1);
    }

    // ---- Wallpaper ----

    File customWallpaper() {
        File dir = ctx.getExternalFilesDir(null); // wallpaper.sh pushes a photo here
        return dir == null ? null : new File(dir, "wallpaper.jpg");
    }

    List<String> choices() {
        List<String> c = new ArrayList<>();
        if (asset("unsplash_key.txt") != null) c.add(UNSPLASH);
        File f = customWallpaper();
        if (f != null && f.exists()) c.add(CUSTOM);
        for (String[] w : WALLPAPERS) c.add(w[0]);
        return c;
    }

    void nextWallpaper() {
        if (UNSPLASH.equals(wallpaperKey())) { // with Unsplash on, the button gets a new photo
            newPhoto = true;
            cachedKey = null;
            photoVersion++;
            showCaption("Getting a new photo…");
            showWallpaper(UNSPLASH);
            return;
        }
        List<String> c = choices();
        String next = c.get((c.indexOf(prefs.getString("wallpaper", c.get(0))) + 1) % c.size());
        prefs.edit().putString("wallpaper", next).apply();
        showWallpaper(next);
        String credit = "Your photo";
        for (String[] w : WALLPAPERS) if (w[0].equals(next)) credit = w[1];
        showCaption(credit);
    }

    // Decoding a photo takes a moment on a Fire TV Stick, so it happens off the UI thread.
    void showWallpaper(final String key) {
        shownWallpaper = key;
        seenPhotoVersion = photoVersion;
        if (key.equals(cachedKey) && cachedPhoto != null) {
            wallView.setImageBitmap(cachedPhoto);
            blur = cachedBlur;
            invalidateGlass(root);
            showCredit(key);
            return;
        }
        final boolean fresh = newPhoto;
        newPhoto = false;
        new Thread(() -> {
            Bitmap b = UNSPLASH.equals(key) && !fetchUnsplash(fresh) ? null : decode(key);
            if (b == null) b = decode(WALLPAPERS[0][0]);
            final Bitmap photo = b, soft = blurred(b);
            post(() -> {
                cachedKey = key;
                cachedPhoto = photo;
                cachedBlur = soft;
                wallView.setImageBitmap(photo);
                blur = soft;
                showCredit(key);
                root.invalidate();
                invalidateGlass(root);
            });
        }).start();
    }

    void invalidateGlass(View v) {
        if (v instanceof GlassView) v.invalidate();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) invalidateGlass(((ViewGroup) v).getChildAt(i));
    }

    Bitmap decode(String key) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(key)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW && o.outHeight / (o.inSampleSize * 2) >= screenH) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            try (InputStream in = open(key)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Exception e) {
            return null;
        }
    }

    InputStream open(String key) throws Exception {
        if (key.equals(UNSPLASH)) return new FileInputStream(unsplashFile());
        return key.equals(CUSTOM) ? new FileInputStream(customWallpaper()) : ctx.getAssets().open("wallpapers/" + key + ".jpg");
    }

    // The frosted glass: the photo shrunk to 96 pixels wide and box-blurred. Drawn stretched, it reads as blur.
    static Bitmap blurred(Bitmap b) {
        int w = 96, h = Math.max(1, Math.round(96f * b.getHeight() / b.getWidth()));
        Bitmap mid = Bitmap.createScaledBitmap(b, w * 4, h * 4, true);
        Bitmap small = Bitmap.createScaledBitmap(mid, w, h, true);
        int[] a = new int[w * h], t = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            boxPass(a, t, h, w, w, 1, 3);
            boxPass(t, a, w, h, 1, w, 3);
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    static void boxPass(int[] src, int[] dst, int lines, int len, int lineStep, int step, int r) {
        for (int l = 0; l < lines; l++) {
            int start = l * lineStep;
            for (int i = 0; i < len; i++) {
                int red = 0, green = 0, blue = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++, n++) {
                    int c = src[start + k * step];
                    red += (c >> 16) & 255;
                    green += (c >> 8) & 255;
                    blue += c & 255;
                }
                dst[start + i * step] = 0xFF000000 | (red / n) << 16 | (green / n) << 8 | blue / n;
            }
        }
    }

    // Draws the blurred photo exactly behind itself, so it looks like frosted glass over the wallpaper.
    class GlassView extends FrameLayout {
        final float radius;
        final Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final Matrix m = new Matrix();
        final int[] loc = new int[2];
        Bitmap shaderFor;

        GlassView(Context c, int radius, int tintColor) {
            super(c);
            this.radius = px(radius);
            tint.setColor(tintColor);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, px(1.5f)));
            edge.setColor(radius > 0 ? 0x1FFFFFFF : 0);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            rect.set(0, 0, getWidth(), getHeight());
            if (blur != null) {
                if (shaderFor != blur) {
                    photo.setShader(new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    shaderFor = blur;
                }
                // Same centre-crop as the wallpaper, shifted to this view's place on screen.
                float k = Math.max((float) screenW / blur.getWidth(), (float) screenH / blur.getHeight());
                getLocationInWindow(loc);
                m.setScale(k, k);
                m.postTranslate((screenW - blur.getWidth() * k) / 2 - loc[0], (screenH - blur.getHeight() * k) / 2 - loc[1]);
                photo.getShader().setLocalMatrix(m);
                c.drawRoundRect(rect, radius, radius, photo);
            }
            c.drawRoundRect(rect, radius, radius, tint);
            if (radius > 0) {
                float e = edge.getStrokeWidth() / 2;
                rect.inset(e, e);
                c.drawRoundRect(rect, radius - e, radius - e, edge);
            }
        }
    }

    // ---- Helpers ----

    void showCaption(String text) {
        caption.setText(text);
        caption.animate().cancel();
        caption.animate().alpha(1).setDuration(160).withEndAction(() ->
                caption.animate().alpha(0).setStartDelay(2600).setDuration(400).start()).start();
    }

    Bitmap bitmap(String asset) {
        try (InputStream in = ctx.getAssets().open(asset)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    void rounded(View v, final int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(radius));
            }
        });
        v.setClipToOutline(true);
    }

    // The clock's position (0 top left, 1 top center, 2 top right) and size (0 small, 1 medium, 2 big).
    // The box always spans the same width, so the text just lines up left, center or right inside it.
    void placeClock() {
        int pos = prefs.getInt("clockPos", 0);
        int size = prefs.getInt("clockSize", 1);
        float big = new float[] {44, 66, 96}[size];
        float date = new float[] {20, 28, 40}[size];
        int gravity = pos == 0 ? Gravity.LEFT : pos == 1 ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT;
        int y = pos == 2 ? 150 : 60; // top right sits under the row of icons
        style(clock, big, medium);
        clock.setIncludeFontPadding(true); // keeps tall glyphs from being cut off at the top or bottom
        style(dateLine, date, medium);
        clock.setGravity(gravity);
        dateLine.setGravity(gravity);
        credit.setGravity(gravity);
        clockBox.setLayoutParams(at(140, y, 1640, -2));
    }

    void style(TextView t, float size, Typeface face) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(face);
        t.setTextColor(0xFFFFFFFF);
        t.setIncludeFontPadding(false);
    }

    FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? w : px(w), h < 0 ? h : px(h));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    int px(float v) {
        return Math.round(v * s);
    }
}

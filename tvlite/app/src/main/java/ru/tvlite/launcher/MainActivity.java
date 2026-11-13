package ru.tvlite.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TV Lite: минимальный лаунчер для слабых Android TV.
 *
 * Дизайн «запечённый глассморфизм»: размытый цветной фон рисуется один раз
 * при запуске и дальше остаётся статичной картинкой, а плитки сделаны
 * полупрозрачными со светлой рамкой. Реального размытия «на лету» нет,
 * поэтому нагрузка на GPU почти нулевая. Анимация только на смену фокуса.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tvlite";
    private static final String KEY_FAV = "fav";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_MONO = "mono";

    private static final int FAV_SIZE = 120; // dp, плитки избранного
    private static final int APP_SIZE = 96;  // dp, остальные плитки

    private static final int ICON_INPUT = 0;
    private static final int ICON_SLIDERS = 1;
    private static final int ICON_EYE = 2;

    /** Что попадает в «Избранное» при первом запуске (если установлено). */
    private static final String[] PREFERRED = {
            "org.smarttube.stable",
            "com.google.android.youtube.tv",
            "com.vk.tv",
            "ru.rutube.app",
            "ru.kinopoisk.tv",
            "ru.ivi.client",
            "com.yandex.browser.tv",
            "io.github.romanvht.byedpi"
    };

    private static final class AppEntry {
        String pkg;       // null для служебных плиток
        String label;
        Intent launch;
        Drawable art;
        int lineIcon = -1; // тип линейной иконки для служебных плиток
        Runnable action;   // для служебных плиток
    }

    private SharedPreferences prefs;
    private PackageManager pm;
    private float density;
    private ScrollView vscroll;
    private LinearLayout rows;
    private boolean dirty = true;
    private boolean resumed = false;
    private boolean mono = false;
    private ColorMatrixColorFilter mutedFilter;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dirty = true;
            if (resumed) {
                refreshIfDirty(false);
            }
        }
    };

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        mono = prefs.getBoolean(KEY_MONO, false);

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.1f);
        mutedFilter = new ColorMatrixColorFilter(cm);

        buildShell();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshIfDirty(true);
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (RuntimeException ignored) {
            // уже снят
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Нажатие Home внутри лаунчера: вернуться в начало
        vscroll.scrollTo(0, 0);
        focusFirst();
    }

    @Override
    public void onBackPressed() {
        // Лаунчер не закрывается кнопкой «Назад»
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View focused = getCurrentFocus();
            if (focused != null && focused.getTag() instanceof AppEntry) {
                AppEntry e = (AppEntry) focused.getTag();
                if (e.pkg != null) {
                    showAppMenu(e);
                    return true;
                }
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void refreshIfDirty(boolean forceFocus) {
        if (!dirty) {
            return;
        }
        dirty = false;
        rebuild();
        if (forceFocus) {
            focusFirst();
        }
    }

    // ------------------------------------------------------------ каркас и фон

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);

        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        backdrop.setImageBitmap(makeAurora());
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        vscroll = new ScrollView(this);
        vscroll.setVerticalScrollBarEnabled(false);
        vscroll.setClipToPadding(false);
        vscroll.setClipChildren(false);
        vscroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(64), dp(32), 0, dp(40));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        time.setTextColor(Color.WHITE);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        col.addView(time, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        date.setTextColor(0x99FFFFFF);
        date.setLetterSpacing(0.04f);
        col.addView(date, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        col.addView(rows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        vscroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(vscroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /**
     * Фон «северное сияние»: несколько больших мягких цветных пятен на тёмной основе.
     * Рисуется один раз в маленький битмап, растягивается на весь экран
     * (растяжение само даёт размытие), дальше это просто статичная текстура.
     */
    private Bitmap makeAurora() {
        final int w = 480;
        final int h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF060A14);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setDither(true);
        blob(c, p, w * 0.12f, h * 0.08f, w * 0.55f, 0xFF5B3DF5, 0.62f);
        blob(c, p, w * 0.92f, h * 0.16f, w * 0.42f, 0xFFD43C95, 0.38f);
        blob(c, p, w * 0.72f, h * 1.02f, w * 0.58f, 0xFF10A9BE, 0.46f);
        blob(c, p, w * 0.04f, h * 0.98f, w * 0.40f, 0xFF2B5CFF, 0.40f);
        // лёгкое затемнение к нижнему краю, чтобы подписи читались
        Paint shade = new Paint();
        shade.setShader(new android.graphics.LinearGradient(
                0, h * 0.45f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, shade);
        return bmp;
    }

    private void blob(Canvas c, Paint p, float cx, float cy, float r, int rgb, float alpha) {
        int inner = (rgb & 0x00FFFFFF) | (((int) (255 * alpha)) << 24);
        int outer = rgb & 0x00FFFFFF;
        p.setShader(new RadialGradient(cx, cy, r, inner, outer, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    // ------------------------------------------------------------ данные

    private Map<String, AppEntry> loadApps() {
        Map<String, AppEntry> map = new LinkedHashMap<>();
        addApps(map, Intent.CATEGORY_LEANBACK_LAUNCHER);
        addApps(map, Intent.CATEGORY_LAUNCHER);
        return map;
    }

    private void addApps(Map<String, AppEntry> map, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(query, 0);
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || map.containsKey(pkg)) {
                continue;
            }
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            CharSequence lbl = ri.loadLabel(pm);
            e.label = lbl != null ? lbl.toString() : pkg;
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(category);
            launch.setClassName(pkg, ri.activityInfo.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            e.launch = launch;
            e.art = ri.loadIcon(pm);
            map.put(pkg, e);
        }
    }

    /** Сохранённый порядок избранного (без значений по умолчанию). */
    private List<String> loadFavNames() {
        List<String> out = new ArrayList<>();
        String s = prefs.getString(KEY_FAV, "");
        if (s != null && !s.isEmpty()) {
            for (String p : s.split("\n")) {
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Избранное; при самом первом запуске подставляет установленные приложения из PREFERRED. */
    private List<String> readFavorites(Map<String, AppEntry> all) {
        if (prefs.getString(KEY_FAV, null) != null) {
            return loadFavNames();
        }
        List<String> out = new ArrayList<>();
        for (String p : PREFERRED) {
            if (all.containsKey(p)) {
                out.add(p);
            }
        }
        if (!out.isEmpty()) {
            saveFavorites(out);
        }
        return out;
    }

    private void saveFavorites(List<String> list) {
        prefs.edit().putString(KEY_FAV, TextUtils.join("\n", list)).apply();
    }

    private Set<String> readHidden() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
    }

    private void saveHidden(Set<String> set) {
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply();
    }

    // ------------------------------------------------------------ строки и плитки

    private void rebuild() {
        rows.removeAllViews();
        Map<String, AppEntry> all = loadApps();
        Set<String> hidden = readHidden();
        List<String> favNames = readFavorites(all);

        List<AppEntry> favs = new ArrayList<>();
        for (String p : favNames) {
            AppEntry e = all.get(p);
            if (e != null && !hidden.contains(p)) {
                favs.add(e);
            }
        }
        List<AppEntry> rest = new ArrayList<>();
        for (AppEntry e : all.values()) {
            if (!favNames.contains(e.pkg) && !hidden.contains(e.pkg)) {
                rest.add(e);
            }
        }
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));

        addTitle("Избранное", 30);
        LinearLayout favRow = newRow();
        if (favs.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Здесь пусто. Удерживайте OK на приложении, чтобы добавить его в избранное.");
            hint.setTextColor(0x99FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            hint.setPadding(0, dp(40), 0, dp(40));
            favRow.addView(hint);
        }
        for (AppEntry e : favs) {
            favRow.addView(makeTile(e, FAV_SIZE));
        }

        addTitle("Приложения", 12);
        LinearLayout allRow = newRow();
        allRow.addView(makeTile(specialTile("Источники", ICON_INPUT, this::showSources), APP_SIZE));
        allRow.addView(makeTile(specialTile("Настройки", ICON_SLIDERS, this::openSettings), APP_SIZE));
        boolean anyHidden = false;
        for (String p : hidden) {
            if (all.containsKey(p)) {
                anyHidden = true;
                break;
            }
        }
        if (anyHidden) {
            allRow.addView(makeTile(specialTile("Скрытые", ICON_EYE, this::showHiddenDialog), APP_SIZE));
        }
        for (AppEntry e : rest) {
            allRow.addView(makeTile(e, APP_SIZE));
        }
    }

    private AppEntry specialTile(String label, int lineIcon, Runnable action) {
        AppEntry e = new AppEntry();
        e.label = label;
        e.lineIcon = lineIcon;
        e.action = action;
        return e;
    }

    private void addTitle(String text, int topDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setAllCaps(true);
        t.setLetterSpacing(0.16f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0x80FFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(topDp), 0, dp(2));
        rows.addView(t);
    }

    private LinearLayout newRow() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setPadding(0, dp(16), dp(64), dp(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        hs.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rows.addView(hs);
        return row;
    }

    /** «Стеклянная» подложка: полупрозрачная заливка сверху вниз и светлая рамка. */
    private Drawable glass(float radiusPx, boolean focused) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{0x5CFFFFFF, 0x30FFFFFF} : new int[]{0x2EFFFFFF, 0x10FFFFFF});
        d.setCornerRadius(radiusPx);
        d.setStroke(focused ? dp(2.5f) : dp(1), focused ? 0xF2FFFFFF : 0x40FFFFFF);
        return d;
    }

    private View makeTile(final AppEntry e, int sizeDp) {
        final int size = dp(sizeDp);
        final float radius = size * 0.26f;

        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setClipChildren(false);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                size + dp(24), ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.rightMargin = dp(10);
        wrap.setLayoutParams(wlp);

        final Drawable normalBg = glass(radius, false);
        final Drawable focusBg = glass(radius, true);

        final FrameLayout card = new FrameLayout(this);
        card.setBackground(normalBg);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        card.setTag(e);

        final ImageView iconView;
        if (e.lineIcon >= 0) {
            iconView = null;
            card.addView(new LineIcon(this, e.lineIcon), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            iconView = new ImageView(this);
            iconView.setImageDrawable(e.art);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = (int) (size * 0.60f);
            card.addView(iconView, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            applyIconStyle(iconView, false);
        }
        wrap.addView(card, new LinearLayout.LayoutParams(size, size));

        final TextView label = new TextView(this);
        label.setText(e.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(0x99FFFFFF);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(10), 0, 0);
        wrap.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float scale = hasFocus ? 1.1f : 1f;
                wrap.animate().cancel();
                wrap.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .translationZ(hasFocus ? dp(10) : 0)
                        .setDuration(110)
                        .start();
                card.setBackground(hasFocus ? focusBg : normalBg);
                label.setTextColor(hasFocus ? Color.WHITE : 0x99FFFFFF);
                if (iconView != null) {
                    applyIconStyle(iconView, hasFocus);
                }
            }
        });
        card.setOnClickListener(v -> activate(e));
        card.setOnLongClickListener(v -> {
            if (e.pkg != null) {
                showAppMenu(e);
            }
            return true;
        });
        return wrap;
    }

    /** Приглушённые иконки в покое и полный цвет на фокусе. */
    private void applyIconStyle(ImageView iv, boolean focused) {
        if (mono && !focused) {
            iv.setColorFilter(mutedFilter);
            iv.setAlpha(0.82f);
        } else {
            iv.setColorFilter(null);
            iv.setAlpha(1f);
        }
    }

    private void focusFirst() {
        ArrayList<View> out = new ArrayList<>();
        rows.addFocusables(out, View.FOCUS_DOWN);
        if (!out.isEmpty()) {
            out.get(0).requestFocus();
        }
    }

    // ------------------------------------------------------------ тонкие линейные иконки

    /** Минималистичные иконки служебных плиток, нарисованные линиями. */
    private static final class LineIcon extends View {
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();

        LineIcon(Context context, int type) {
            super(context);
            this.type = type;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.WHITE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float u = Math.min(w, h) * 0.26f;
            p.setStrokeWidth(Math.max(2f, Math.min(w, h) * 0.032f));

            if (type == ICON_INPUT) {
                rect.set(cx - u, cy - u * 0.72f, cx + u, cy + u * 0.42f);
                c.drawRoundRect(rect, u * 0.2f, u * 0.2f, p);
                c.drawLine(cx, cy + u * 0.42f, cx, cy + u * 0.82f, p);
                c.drawLine(cx - u * 0.45f, cy + u * 0.82f, cx + u * 0.45f, cy + u * 0.82f, p);
            } else if (type == ICON_SLIDERS) {
                float[] ys = {-0.62f, 0f, 0.62f};
                float[] ks = {-0.35f, 0.45f, -0.05f};
                float r = u * 0.2f;
                for (int i = 0; i < 3; i++) {
                    float y = cy + ys[i] * u;
                    float kx = cx + ks[i] * u;
                    c.drawLine(cx - u, y, kx - r, y, p);
                    c.drawLine(kx + r, y, cx + u, y, p);
                    c.drawCircle(kx, y, r, p);
                }
            } else {
                path.reset();
                path.moveTo(cx - u, cy);
                path.quadTo(cx, cy - u * 0.95f, cx + u, cy);
                path.quadTo(cx, cy + u * 0.95f, cx - u, cy);
                c.drawPath(path, p);
                c.drawCircle(cx, cy, u * 0.28f, p);
            }
        }
    }

    // ------------------------------------------------------------ действия

    private void activate(AppEntry e) {
        if (e.action != null) {
            e.action.run();
            return;
        }
        try {
            startActivity(e.launch);
        } catch (RuntimeException ex) {
            toast("Не удалось открыть: " + e.label);
        }
    }

    private void showAppMenu(final AppEntry e) {
        final List<String> favs = loadFavNames();
        final boolean isFav = favs.contains(e.pkg);
        String[] items = {
                isFav ? "Убрать из избранного" : "Добавить в избранное",
                "Скрыть с главного экрана",
                "О приложении / удалить",
                mono ? "Иконки: показывать цветными" : "Иконки: приглушить (минимализм)",
                "Отмена"
        };
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        if (isFav) {
                            favs.remove(e.pkg);
                        } else {
                            favs.add(e.pkg);
                        }
                        saveFavorites(favs);
                        rebuildAndFocus();
                    } else if (which == 1) {
                        Set<String> hidden = readHidden();
                        hidden.add(e.pkg);
                        saveHidden(hidden);
                        favs.remove(e.pkg);
                        saveFavorites(favs);
                        rebuildAndFocus();
                        toast("Скрыто. Вернуть: Приложения → Скрытые");
                    } else if (which == 2) {
                        openAppDetails(e.pkg);
                    } else if (which == 3) {
                        mono = !mono;
                        prefs.edit().putBoolean(KEY_MONO, mono).apply();
                        rebuildAndFocus();
                    }
                })
                .show();
    }

    private void rebuildAndFocus() {
        rebuild();
        focusFirst();
    }

    private void showHiddenDialog() {
        final Map<String, AppEntry> all = loadApps();
        final Set<String> hidden = readHidden();
        final List<String> pkgs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (String p : hidden) {
            AppEntry e = all.get(p);
            if (e != null) {
                pkgs.add(p);
                names.add(e.label);
            }
        }
        if (pkgs.isEmpty()) {
            toast("Скрытых приложений нет");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Вернуть на главный экран")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    hidden.remove(pkgs.get(which));
                    saveHidden(hidden);
                    rebuildAndFocus();
                })
                .show();
    }

    private void openAppDetails(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (RuntimeException ex) {
            toast("Сведения о приложении недоступны на этом ТВ");
        }
    }

    private void openSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (RuntimeException ex) {
            toast("Не удалось открыть настройки");
        }
    }

    /** Выбор HDMI/AV-входа через TV Input Framework. */
    private void showSources() {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) {
            toast("Входы недоступны на этом устройстве");
            return;
        }
        final List<TvInputInfo> inputs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    CharSequence name = info.loadCustomLabel(this);
                    if (name == null || name.length() == 0) {
                        name = info.loadLabel(this);
                    }
                    inputs.add(info);
                    names.add(name != null ? name.toString() : info.getId());
                }
            }
        } catch (RuntimeException ex) {
            toast("Не удалось получить список входов");
            return;
        }
        if (inputs.isEmpty()) {
            toast("HDMI-входы не найдены");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Источник сигнала")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    Uri uri = TvContract.buildChannelUriForPassthroughInput(inputs.get(which).getId());
                    Intent i = new Intent(Intent.ACTION_VIEW, uri);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(i);
                    } catch (RuntimeException ex) {
                        toast("Не удалось переключить вход");
                    }
                })
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}

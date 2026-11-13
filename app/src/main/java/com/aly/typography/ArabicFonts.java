package com.aly.typography;

import android.app.Activity;
import android.app.Application;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.MetricAffectingSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.WeakHashMap;

/** Offline Thmanyah Sans for Arabic runs, using the existing SahlTV packaged-font format. */
public final class ArabicFonts {
    private static final byte[] KEY = "Sahl-TV/thmanyah-font/scramble-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static Typeface regular, medium, bold;
    private static final WeakHashMap<TextView, Boolean> bound = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> roots = new WeakHashMap<>();
    private ArabicFonts() {}

    public static void install(Application app) {
        regular = load(app, "r");
        medium = load(app, "m");
        bold = load(app, "b");
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { watch(a.getWindow()); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static Typeface load(Context context, String name) {
        // noBackupFilesDir prevents reconstructed fonts from entering backups. Version is pinned
        // to the checked build-48 assets; use a new directory when updating that input.
        File dir = new File(context.getNoBackupFilesDir(), "thmanyah-build-48");
        File out = new File(dir, name);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            if (!out.isFile()) {
                byte[] data;
                try (InputStream input = context.getAssets().open("thmanyah/" + name + ".bin");
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
                    data = bytes.toByteArray();
                }
                for (int i = 0; i < data.length; i++) data[i] ^= KEY[i % KEY.length];
                if (data.length < 4 || !(data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O'
                        || data[0] == 0 && data[1] == 1 && data[2] == 0 && data[3] == 0)) return null;
                File temp = new File(dir, name + ".tmp");
                try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(data); }
                if (!temp.renameTo(out)) { temp.delete(); return null; }
            }
            try (java.io.FileInputStream cached = new java.io.FileInputStream(out)) {
                byte[] header = new byte[4];
                if (cached.read(header) != 4 || !(header[0] == 'O' && header[1] == 'T' && header[2] == 'T' && header[3] == 'O'
                        || header[0] == 0 && header[1] == 1 && header[2] == 0 && header[3] == 0)) {
                    out.delete(); return null;
                }
            }
            return Typeface.createFromFile(out);
        } catch (IOException | RuntimeException ignored) {
            out.delete(); // corrupt cache recovers next launch; current launch uses the system font
            return null;
        }
    }

    public static void watch(Window window) {
        if (window != null) watchRoot(window.getDecorView());
    }

    /** Also used for service overlays and separately created dialog windows. */
    public static void watchRoot(View root) {
        if (regular == null || roots.put(root, true) != null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> applyTree(root));
        applyTree(root);
    }

    private static void applyTree(View view) {
        if (view instanceof AppWidgetHostView || "preserve-document-font".equals(view.getTag())) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (bound.put(text, true) == null) {
                text.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override public void afterTextChanged(Editable editable) { decorate(editable); }
                });
            }
            CharSequence value = text.getText();
            if (value instanceof Spannable) decorate((Spannable) value);
            else if (!ArabicRuns.of(value).isEmpty()) {
                SpannableString copy = new SpannableString(value);
                decorate(copy);
                text.setText(copy, TextView.BufferType.SPANNABLE);
            }
            CharSequence hint = text.getHint();
            if (hint != null && !ArabicRuns.of(hint).isEmpty() && !matches(hint)) {
                SpannableString copy = new SpannableString(hint); decorate(copy); text.setHint(copy);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i));
        }
    }

    private static boolean matches(CharSequence value) {
        if (!(value instanceof Spanned)) return false;
        Spanned spans = (Spanned) value;
        List<int[]> ranges = ArabicRuns.of(value);
        ArabicSpan[] existing = spans.getSpans(0, value.length(), ArabicSpan.class);
        if (existing.length != ranges.size()) return false;
        for (int i = 0; i < existing.length; i++) {
            if (spans.getSpanStart(existing[i]) != ranges.get(i)[0]
                    || spans.getSpanEnd(existing[i]) != ranges.get(i)[1]) return false;
        }
        return true;
    }

    static void decorate(Spannable value) {
        if (regular == null || matches(value)) return;
        for (ArabicSpan span : value.getSpans(0, value.length(), ArabicSpan.class)) value.removeSpan(span);
        for (int[] range : ArabicRuns.of(value)) value.setSpan(new ArabicSpan(), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Leaves characters, selection, other spans, sizes, colors and Latin typefaces unchanged. */
    public static final class ArabicSpan extends MetricAffectingSpan {
        private void apply(TextPaint paint) {
            Typeface original = paint.getTypeface();
            int style = original == null ? Typeface.NORMAL : original.getStyle();
            boolean heavy = paint.isFakeBoldText() || (style & Typeface.BOLD) != 0;
            Typeface face = heavy && bold != null ? bold : regular;
            if (!heavy && medium != null && Build.VERSION.SDK_INT >= 28
                    && original != null && original.getWeight() >= 500) face = medium;
            if (face != null) {
                paint.setTypeface((style & Typeface.ITALIC) != 0 ? Typeface.create(face, Typeface.ITALIC) : face);
                paint.setFakeBoldText(false);
            }
        }
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
    }
}

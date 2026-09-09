package io.github.hankaviator.gmailadremover;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Removes Gmail's native sponsored conversation-list rows without depending on
 * Gmail's obfuscated implementation classes.
 */
public final class GmailAdRemover implements IXposedHookLoadPackage {
    private static final String GMAIL_PACKAGE = "com.google.android.gm";
    private static final String TAG = "GmailAdRemover";
    private static final int MAX_ANCESTORS = 14;

    private static final Set<String> SPONSORED_LABELS = labels(
            "sponsored", "sponsorisé", "sponsorisée", "gesponsert", "patrocinado",
            "patrocinada", "sponsorizzato", "sponsorizzata", "スポンサー", "스폰서",
            "赞助内容", "贊助內容", "赞助", "贊助", "реклама", "sponsrad"
    );

    private static final Set<Activity> OBSERVED_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<View, SavedState> HIDDEN_ROWS = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!GMAIL_PACKAGE.equals(loadPackageParam.packageName)
                || !GMAIL_PACKAGE.equals(loadPackageParam.processName)) {
            return;
        }

        XposedBridge.log(TAG + ": loaded in Gmail " + loadPackageParam.processName);
        XposedBridge.hookAllMethods(Activity.class, "onPostResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                observe((Activity) param.thisObject);
            }
        });

        // This catches labels bound after the initial layout pass and keeps the
        // visible ad from flashing while a full-tree scan is pending.
        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                TextView textView = (TextView) param.thisObject;
                if (isSponsoredMarker(textView)) {
                    View row = findRecyclerRow(textView);
                    if (row != null) {
                        hide(row);
                    }
                }
            }
        });

        // Gmail inflates an ad-specific subtree before attaching the row. Hide
        // that row during insertion, before Android has a chance to draw it.
        XposedBridge.hookAllMethods(ViewGroup.class, "addView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof ViewGroup) || param.args.length == 0
                        || !(param.args[0] instanceof View)) {
                    return;
                }
                ViewGroup parent = (ViewGroup) param.thisObject;
                View child = (View) param.args[0];
                if (isRecyclerView(parent) && containsAdSignature(child)) {
                    hide(child);
                }
            }
        });
    }

    private static void observe(Activity activity) {
        View root = activity.getWindow().getDecorView();
        synchronized (OBSERVED_ACTIVITIES) {
            if (OBSERVED_ACTIVITIES.add(activity)) {
                root.getViewTreeObserver().addOnGlobalLayoutListener(() -> scan(root));
                root.getViewTreeObserver().addOnPreDrawListener(() -> !scan(root));
            }
        }
        scan(root);
        root.postDelayed(() -> scan(root), 250L);
        root.postDelayed(() -> scan(root), 1000L);
    }

    /** Returns true when a visibility change requires the current draw to be retried. */
    private static boolean scan(View root) {
        if (root == null || !root.isAttachedToWindow()) {
            return false;
        }
        Set<View> sponsoredRows = Collections.newSetFromMap(new WeakHashMap<>());
        collectSponsoredRows(root, sponsoredRows);
        boolean changed = false;

        // RecyclerView reuses its children. Ambiguous text-only matches may be
        // restored after rebinding, but Gmail's dedicated ad_teaser view type
        // must stay collapsed while detached/outgoing or it flashes during the
        // category transition.
        synchronized (HIDDEN_ROWS) {
            for (View oldRow : new HashSet<>(HIDDEN_ROWS.keySet())) {
                if (!sponsoredRows.contains(oldRow) && !containsAdSignature(oldRow)) {
                    changed |= restore(oldRow);
                }
            }
        }
        for (View row : sponsoredRows) {
            changed |= hide(row);
        }
        return changed;
    }

    private static void collectSponsoredRows(View view, Set<View> output) {
        if (view instanceof TextView && isSponsoredMarker((TextView) view)) {
            View row = findRecyclerRow(view);
            if (row != null) {
                output.add(row);
            }
        }
        CharSequence description = view.getContentDescription();
        if (isSponsoredText(description)) {
            View row = findRecyclerRow(view);
            if (row != null) {
                output.add(row);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSponsoredRows(group.getChildAt(i), output);
            }
        }
    }

    private static boolean isSponsoredMarker(TextView view) {
        if (isSponsoredText(view.getText()) || isSponsoredText(view.getContentDescription())) {
            return true;
        }
        int id = view.getId();
        if (id == View.NO_ID) {
            return false;
        }
        try {
            String name = view.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
            return name.contains("sponsor") || name.contains("ad_badge")
                    || name.contains("advertisement_label");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isSponsoredText(CharSequence value) {
        if (value == null || value.length() > 32) {
            return false;
        }
        String normalized = normalize(value.toString());
        if (SPONSORED_LABELS.contains(normalized)) {
            return true;
        }
        // Accessibility labels sometimes append punctuation, e.g. "Sponsored:"
        return normalized.endsWith(":")
                && SPONSORED_LABELS.contains(normalized.substring(0, normalized.length() - 1).trim());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static View findRecyclerRow(View start) {
        View child = start;
        ViewParent parent = start.getParent();
        for (int depth = 0; parent instanceof ViewGroup && depth < MAX_ANCESTORS; depth++) {
            ViewGroup group = (ViewGroup) parent;
            if (isRecyclerView(group)) {
                return child;
            }
            child = group;
            parent = group.getParent();
        }
        return null;
    }

    private static boolean isRecyclerView(View view) {
        Class<?> type = view.getClass();
        while (type != null) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(type.getName())
                    || "android.support.v7.widget.RecyclerView".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean containsAdSignature(View view) {
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                String name = view.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
                if (name.contains("ad_teaser") || name.contains("ad_badge")
                        || name.contains("advertisement_label")) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Continue through descendants.
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsAdSignature(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hide(View row) {
        boolean changed = row.getVisibility() != View.GONE || row.getAlpha() != 0f
                || row.getImportantForAccessibility()
                != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        synchronized (HIDDEN_ROWS) {
            if (!HIDDEN_ROWS.containsKey(row)) {
                HIDDEN_ROWS.put(row, new SavedState(row.getVisibility(), row.getAlpha(),
                        row.getImportantForAccessibility()));
            }
        }
        row.setAlpha(0f);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setVisibility(View.GONE);
        return changed;
    }

    private static boolean restore(View row) {
        SavedState state;
        synchronized (HIDDEN_ROWS) {
            state = HIDDEN_ROWS.remove(row);
        }
        if (state != null) {
            row.setVisibility(state.visibility);
            row.setAlpha(state.alpha);
            row.setImportantForAccessibility(state.importantForAccessibility);
            return true;
        }
        return false;
    }

    private static Set<String> labels(String... values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            result.add(normalize(value));
        }
        return Collections.unmodifiableSet(result);
    }

    private static final class SavedState {
        final int visibility;
        final float alpha;
        final int importantForAccessibility;

        SavedState(int visibility, float alpha, int importantForAccessibility) {
            this.visibility = visibility;
            this.alpha = alpha;
            this.importantForAccessibility = importantForAccessibility;
        }
    }
}

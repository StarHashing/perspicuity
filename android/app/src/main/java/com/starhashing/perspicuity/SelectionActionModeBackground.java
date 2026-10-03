package com.starhashing.perspicuity;

import android.view.ActionMode;
import android.view.View;
import android.view.ViewParent;

/**
 * Supplementary cleanup for the floating selection ActionMode's popup panel.
 *
 * The editor hides the system selection toolbar by repeatedly calling
 * {@link ActionMode#hide(long)} (see {@link HiddenSelectionActionModeCallback}).
 * On modern Android the floating ActionMode is
 * {@code com.android.internal.view.FloatingActionMode}; {@code hide()} fades the
 * toolbar content but can leave the popup panel painting a bare rectangle.
 *
 * The primary fix for that rectangle is upstream: the wrapped callback collapses
 * the ActionMode's content rect off-screen so the popup never lays out a visible
 * panel at all (see HiddenSelectionActionModeCallback#onGetContentRect). That is
 * the only lever that reaches the popup, because the popup lives in its own
 * WindowManager window (not the activity's view tree) and the owning
 * FloatingActionMode fields are hidden from reflection by the platform's
 * non-SDK API filter.
 *
 * This class is a best-effort belt-and-braces pass for OEM ROMs whose ActionMode
 * keeps the toolbar content view itself attached: it clears the background of
 * that view up the parent chain. Every step is wrapped so a failure can never
 * make things worse than the status quo.
 */
final class SelectionActionModeBackground {

    private SelectionActionModeBackground() {}

    /**
     * Clears the background of the mode's content view and a few of its
     * ancestors. Safe to call repeatedly; background clearing is idempotent.
     *
     * @return a short description of what was cleared, or null when the content
     *     view was not reachable (which is the norm on ROMs where the toolbar
     *     lives in a separate popup window).
     */
    static String clear(ActionMode mode) {
        if (mode == null) {
            return null;
        }

        View content = findContent(mode);
        if (content == null) {
            return null;
        }

        StringBuilder detail = new StringBuilder();
        detail.append("content=").append(content.getClass().getSimpleName());

        View node = content;
        int level = 0;
        int cleared = 0;
        while (node != null && level < 4) {
            try {
                node.setBackground(null);
                cleared++;
            } catch (Throwable ignored) {
                // ignore
            }
            ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
            level++;
        }
        detail.append(" clearedLevels=").append(cleared);
        return detail.toString();
    }

    /**
     * Reflectively fetches the mode's {@code mContent} view (the toolbar view).
     * Returns null on ROMs where the field is hidden by the non-SDK API filter,
     * which is the expected case on Android 14+.
     */
    private static View findContent(ActionMode mode) {
        for (Class<?> cls = mode.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField("mContent");
                field.setAccessible(true);
                Object value = field.get(mode);
                if (value instanceof View) {
                    return (View) value;
                }
            } catch (Throwable ignored) {
                // NoSuchField, IllegalAccess, hidden-API rejection: keep scanning.
            }
        }
        return null;
    }
}
package com.starhashing.perspicuity;

import android.graphics.Rect;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Wraps the WebView selection ActionMode callback so the real system floating
 * ActionMode is created and stays alive (Chromium clears the selection and the
 * touch handles whenever its ActionMode dies or fails to start), while the
 * toolbar itself is kept invisible through repeated ActionMode.hide() calls.
 *
 * This mirrors Chromium's own scroll-time behavior: SelectionPopupControllerImpl
 * keeps a repeating runnable that calls ActionMode.hide(duration) and re-arms
 * itself at (duration - 1), because FloatingActionMode clamps each hide() to a
 * 3000ms maximum and then shows the toolbar again.
 */
class HiddenSelectionActionModeCallback extends ActionMode.Callback2 {
    private static final long FALLBACK_HIDE_DURATION_MS = 2000L;
    private static final long MIN_REARM_DELAY_MS = 100L;

    private final MainActivity activity;
    private final View originatingView;
    private final ActionMode.Callback delegate;
    private final Runnable rearmHide = this::hideFromRearm;

    private ActionMode mode = null;
    private boolean destroyed = false;
    private boolean hideFailureRecorded = false;

    HiddenSelectionActionModeCallback(
        MainActivity activity,
        View originatingView,
        ActionMode.Callback delegate
    ) {
        this.activity = activity;
        this.originatingView = originatingView;
        this.delegate = delegate;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        this.mode = mode;
        boolean created = delegate.onCreateActionMode(mode, menu);
        if (created) {
            activity.registerSuppressedSelectionActionMode(mode, this);
            activity.recordSelectionActionModeEvent(
                "hidden-mode-create",
                "mode=" + mode.getClass().getName() + " menuSize=" + menu.size()
            );
            // Some ActionMode implementations are not fully initialized inside
            // onCreateActionMode, so the first hide is posted instead of called
            // inline. onPrepareActionMode below usually hides even earlier.
            originatingView.post(() -> hideNow("post-create"));
        }
        return created;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        boolean changed = delegate.onPrepareActionMode(mode, menu);
        // FloatingActionMode.invalidate() runs onPrepareActionMode before it
        // lays out and shows the toolbar, so hiding here prevents the toolbar
        // from ever becoming visible rather than dismissing it after a flash.
        hideNow("prepare");
        return changed;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        return delegate.onActionItemClicked(mode, item);
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        destroyed = true;
        originatingView.removeCallbacks(rearmHide);
        activity.recordSelectionActionModeEvent(
            "hidden-mode-destroy",
            "mode=" + mode.getClass().getName()
        );
        delegate.onDestroyActionMode(mode);
    }

    @Override
    public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
        if (delegate instanceof ActionMode.Callback2) {
            ((ActionMode.Callback2) delegate).onGetContentRect(mode, view, outRect);
        } else {
            super.onGetContentRect(mode, view, outRect);
        }
        // FloatingActionMode positions and sizes its popup from this rect. While
        // the editor suppresses the system selection toolbar AND the user has
        // not opted out, collapse the rect to a zero-size, off-screen point so
        // the popup never lays out a visible panel. This is the only lever that
        // reaches the popup: it lives in its own WindowManager window (not the
        // activity's DecorView), and its owning FloatingActionMode fields are
        // hidden from reflection by the platform's non-SDK API filter.
        if (activity.shouldSuppressEditorSelectionActionMode()
            && activity.isSelectionMenuRectCollapsed()
            && outRect != null) {
            outRect.set(-10000, -10000, -10000, -10000);
        }
    }

    private void hideFromRearm() {
        hideNow("rearm");
    }

    private void hideNow(String stage) {
        if (destroyed || mode == null) {
            return;
        }

        if (!activity.shouldSuppressEditorSelectionActionMode()) {
            // Suppression was switched off while a selection is active: stop
            // re-arming and let the native toolbar reappear on its own.
            originatingView.removeCallbacks(rearmHide);
            return;
        }

        long duration = hideDurationMs();
        try {
            mode.hide(duration);
        } catch (RuntimeException exception) {
            if (!hideFailureRecorded) {
                hideFailureRecorded = true;
                activity.recordSelectionActionModeEvent(
                    "hidden-mode-hide-failed",
                    "stage=" + stage + " error=" + exception
                );
            }
            return;
        }

        // hide() fades the toolbar content only; the FloatingActionMode popup
        // background panel survives and keeps painting a bare rectangle. Clear it
        // on every pass so an OEM that re-applies the background when the menu
        // re-lays-out cannot outrun the first clear (see
        // SelectionActionModeBackground for the full rationale).
        activity.clearSuppressedSelectionActionModeBackground(mode, "hide:" + stage);

        originatingView.removeCallbacks(rearmHide);
        originatingView.postDelayed(rearmHide, Math.max(MIN_REARM_DELAY_MS, duration - 100L));
    }

    private long hideDurationMs() {
        long duration = ViewConfiguration.getDefaultActionModeHideDuration();
        if (duration <= MIN_REARM_DELAY_MS) {
            return FALLBACK_HIDE_DURATION_MS;
        }
        return duration;
    }
}

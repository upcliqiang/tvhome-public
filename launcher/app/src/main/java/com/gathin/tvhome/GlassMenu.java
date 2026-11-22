package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * The shared build steps of the glass menus: the popup shell, the drop-in reveal and one menu row.
 * The top menu, the settings menu, the tile menu and the control panel (MainActivity), the app
 * library's action menu (AppLibraryDialog) and the quick settings panel all hang off these.
 */
final class GlassMenu {

    private GlassMenu() { }

    /**
     * The glass popup shell: a transparent background (so Back dismisses it), no window animation
     * (the reveal below is the animation) and no shadow (the panel's own glass carries the depth).
     */
    static PopupWindow popup(View panel, int width) {
        PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    /** The reveal's starting pose: invisible, folded to the edge it will grow from. Call before showing. */
    static void prepareReveal(View panel, boolean fromTop, int slidePx) {
        panel.setAlpha(0f);
        panel.setPivotY(fromTop ? 0f : panel.getMeasuredHeight());
        panel.setScaleY(0.92f);
        panel.setTranslationY(fromTop ? -slidePx : slidePx);
    }

    /** The second half of the drop-in: unfold and fade in, after the popup is showing. */
    static void animateIn(View panel, long durationMs) {
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /**
     * One row of a glass menu in the settings row's shape (item_input): the title plus an optional
     * icon and status text, both hidden when 0 / null. The caller adds any key handling of its own.
     */
    static View menuRow(Context ctx, ViewGroup rows, int titleRes, int iconRes, CharSequence status,
                        View.OnClickListener click) {
        View row = LayoutInflater.from(ctx).inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(titleRes);
        ImageView icon = row.findViewById(R.id.icon);
        if (iconRes != 0) icon.setImageResource(iconRes);
        else icon.setVisibility(View.GONE);
        TextView statusView = row.findViewById(R.id.status);
        if (status != null) statusView.setText(status);
        else statusView.setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.setOnClickListener(click);
        rows.addView(row);
        return row;
    }
}

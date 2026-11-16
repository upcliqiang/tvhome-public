package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A ScrollView that keeps a strip of air around the row the remote just moved to. Scrolling to a newly focused
 * row goes through arrowScroll / scrollToChild / computeScrollDeltaToGetChildRectOnScreen, which only scrolls
 * until the row is just inside the viewport — so the last row of the settings page came to rest on the screen
 * edge with its card border cut off, and its paddings are ignored by that path. Handing it a taller rectangle
 * leaves the gap.
 */
public class EdgeScrollView extends ScrollView {

    /** How far past the focused row the scroll may go, so it never touches the screen edge. */
    private final int edge;

    public EdgeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        edge = Math.round(44f * getResources().getDisplayMetrics().density);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        Rect relaxed = new Rect(rect);
        relaxed.top -= edge;
        relaxed.bottom += edge;
        return super.computeScrollDeltaToGetChildRectOnScreen(relaxed);
    }
}

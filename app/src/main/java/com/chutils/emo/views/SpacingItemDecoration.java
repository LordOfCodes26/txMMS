package com.chutils.emo.views;

import android.graphics.Rect;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;


/**
 * RecyclerView item decoration - give equal margin around grid item
 */

//hgc
public class SpacingItemDecoration extends RecyclerView.ItemDecoration {

    private final int mSpanCount;
    private final int mSpacingPx;
    private final boolean mIncludeEdge;

    public SpacingItemDecoration(int mSpanCount, int mSpacingPx, boolean mIncludeEdge) {
        this.mSpanCount = mSpanCount;
        this.mSpacingPx = mSpacingPx;
        this.mIncludeEdge = mIncludeEdge;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view); // item position
        int column = position % mSpanCount; // item column

        if (mIncludeEdge) {
            outRect.left = mSpacingPx - column * mSpacingPx / mSpanCount;
            outRect.right = (column + 1) * mSpacingPx / mSpanCount;

            if (position < mSpanCount) { // top edge
                outRect.top = mSpacingPx;
            }
            outRect.bottom = mSpacingPx; // item bottom
        }
        else {
            outRect.left = column * mSpacingPx / mSpanCount;
            outRect.right = mSpacingPx - (column + 1) * mSpacingPx / mSpanCount;
            if (position >= mSpanCount) {
                outRect.top = mSpacingPx; // item top
            }
        }
    }
}
package com.chutils.emo.views;

import androidx.annotation.Nullable;
import android.content.Context;
import android.graphics.Paint;
import android.util.AttributeSet;

import com.chutils.emo.model.EmoModel;

public class EmoGridCellView extends EmoTextView {

    public Paint mPainter = new Paint();
    private int mWidth = 0;
    private int mHeight = 0;

    public EmoGridCellView(Context context) {
        super(context);
        init();
    }

    public EmoGridCellView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmoGridCellView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void init() {
        setPadding(0, 0, 0, 0);
        mPainter.setAntiAlias(true);
        mPainter.setFilterBitmap(true);
    }

    public void onMeasure(int i, int i2) {
        super.onMeasure(i, i2);
        int measuredWidth = getMeasuredWidth();
        mWidth = measuredWidth;
        mHeight = getMeasuredHeight();
    }

    public void setEmoData(EmoModel emo, int position, EmoModel tabID) {
        setTag(emo);
        setEmoText(emo, EmoTextView.EMO_IN_GRID_VIEW);
    }
}
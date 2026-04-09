package com.chutils.emo.model;

import androidx.annotation.NonNull;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.style.ImageSpan;

import com.chutils.api.ResUtilsApi;
import com.chutils.emo.manager.ResManager;

import java.lang.ref.WeakReference;
import java.util.Arrays;

//bigbug128
public class EmoImageSpan extends ImageSpan {
	static final String TAG = EmoImageSpan.class.getSimpleName();

	public static final String EMO_SPAN_STR = "*";

	public EmoDrawable mDrawable = null;
	public byte[] mCodeBuffer = null;
	public int mEmoType = ResManager.RES_TYPE_NONE;

	// Extra variables used to redefine the Font Metrics when an ImageSpan is added
	int mInitialDescent = 0;
	int mExtraSpace = 0;

	WeakReference<Drawable> mDrawableRef = null;

	public EmoImageSpan(Drawable drawable) {
		super(drawable);
	}

	public EmoImageSpan(EmoDrawable drawable) {
		super(drawable);
		mDrawable = drawable;
	}

	public EmoImageSpan(Drawable drawable, int align) {
		super(drawable, align);
	}

	public EmoImageSpan(EmoDrawable drawable, int align) {
		super(drawable, align);
		mDrawable = drawable;
	}

	@Override
	public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
		Drawable d = getCachedDrawable();
		Rect rect = d.getBounds();

		if (fm != null) {
			// Centers the text with the ImageSpan
			if (rect.bottom - (fm.descent - fm.ascent) >= 0) {
				// Stores the initial descent and computes the margin available
				mInitialDescent = fm.descent;
				mExtraSpace = rect.bottom - (fm.descent - fm.ascent);
			}

			fm.descent = mExtraSpace / 2 + mInitialDescent;
			fm.bottom = fm.descent;

			fm.ascent = -rect.bottom + fm.descent;
			fm.top = fm.ascent;
		}
		return rect.right;
	}

	@Override
	public void draw(@NonNull Canvas canvas, CharSequence text,
					 int start, int end, float x,
					 int top, int y, int bottom, @NonNull Paint paint) {
		Drawable b = getCachedDrawable();
		canvas.save();

		int transY = bottom - b.getBounds().bottom;
		transY -= (paint.getFontMetricsInt().descent / 2);

		canvas.translate(x, transY);
		b.draw(canvas);
		canvas.restore();
	}

	// Redefined locally because it is a private member from DynamicDrawableSpan
	private Drawable getCachedDrawable() {
		WeakReference<Drawable> wr = mDrawableRef;
		Drawable d = null;

        if (wr != null) {
            d = wr.get();
        }
		if (d == null) {
			d = getDrawable();
			mDrawableRef = new WeakReference<>(d);
		}
		return d;
	}

	public String convert2Text(boolean isFake, int charIndex) {
		switch (mEmoType) {
			case ResManager.RES_TYPE_EMOGI: {
				if (isFake) {
					int count = ResUtilsApi.RES_EMOGI_TEXT_SIZE;
					byte[] dataBuffer = new byte[count];
					Arrays.fill(dataBuffer, (byte)0x20);
					return new String(dataBuffer);
				}
				return ResManager.buildCodeDispStr(mCodeBuffer, charIndex);
			}
			case ResManager.RES_TYPE_EMOICO: {
				return new String(mCodeBuffer);
			}
			default: {
				break;
			}
		}
		return EMO_SPAN_STR;
	}
}
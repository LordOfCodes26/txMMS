package com.chutils.emo.model;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.chutils.CHGlobal;
import com.chutils.CHLog;
import com.chutils.emo.manager.ResManager;
import com.chutils.emo.views.EmoTextView;

//bigbug128
public class EmoDrawable extends Drawable {
	static final String TAG = EmoDrawable.class.getSimpleName();

	public static final int EMO_NONE = 0x00;
	public static final int EMO_ONLY_PREVIEW = 0x01;
	public static final int EMO_ALL_MODE = 0xFF;

	int mId = -1;
	int mEmoWidth = 0;
	int mEmoHeight = 0;
	int mEmoMode = EMO_NONE;
	byte[] mCodeBytes = null;

	int mCurFrame = -1;
	EmoModel mEmo;
	Bitmap [] mBitmaps = null;

	long mUpdateTime = 0;
	long mUpdateDuration = CHGlobal.EMO_DRAW_INTERVAL;

	Paint mPainter = new Paint();

	boolean mIsThreadStarted = false;
	boolean mIsAsyncLoaded = false;

	final Object mThumbMutex = new Object();
	final Object mContentMutex = new Object();

	public EmoDrawable() {
		mCurFrame = 0;
		init();
	}

	public void init() {
		mPainter.setAntiAlias(true);
		mPainter.setFilterBitmap(true);
	}

	public void setDataAsync() {
		try {
			new Thread(new Runnable() {
				@Override
				public void run() {
					mIsAsyncLoaded = false;
					if (mIsThreadStarted) {
						return;
					}
					mIsThreadStarted = true;

					try {
						synchronized (mThumbMutex) {
							mEmo = ResManager.getInfoContent(mCodeBytes);
							if (mEmo == null) {
								return;
							}
							mEmo.getThumbBitmap();
						}
						if (CHGlobal.bitCheck(mEmoMode, EMO_ONLY_PREVIEW)) {
							mIsThreadStarted = false;
							return;
						}

						CHGlobal.sleep(CHGlobal.EMO_LOAD_DELAY);
						if (!waitForIdle()) {
							releaseEmoData();
							return;
						}
					}
					catch (Exception e) {
						CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " setDataAsyncByte pre-caught:" + e.toString());
						return;
					}

					mEmo.getDataBuffer();
					byte[] dataBytes = mEmo.mDataBuffer;

					int frameCount = 0;
					frameCount = CHGlobal.byteArrayToInt32(dataBytes, 0);
					int readPos = 4;

					synchronized (mContentMutex) {
						mBitmaps = new Bitmap[frameCount];
					}

					int stPos, edPos;
					stPos = CHGlobal.byteArrayToInt32(dataBytes, readPos);
					readPos = readPos + 8;

					for (int curFrame = 0; curFrame < frameCount; curFrame++) {
						if (!waitForIdle()) {
							releaseEmoData();
							return;
						}

						edPos = CHGlobal.byteArrayToInt32(dataBytes, readPos);
						readPos = readPos + 8;
						Bitmap bitmap = BitmapFactory.decodeByteArray(dataBytes, stPos, edPos - stPos);
						bitmap = Bitmap.createScaledBitmap(bitmap, (int) mEmoWidth, (int) mEmoHeight, true);

						synchronized (mContentMutex) {
							if (mBitmaps == null || mBitmaps.length <= curFrame || !mIsThreadStarted) {
								mBitmaps = null;
								mEmo.clearDataBuffer();
								mIsThreadStarted = false;
								return;
							}
							mBitmaps[curFrame] = bitmap;
						}
						stPos = edPos;
						CHGlobal.sleep(2);
					}

					synchronized (mContentMutex) {
						if (mEmo.mVal3 > 0) {
							mUpdateDuration = 1000 / mEmo.mVal3;
						}
						else {
							mUpdateDuration = CHGlobal.EMO_DRAW_INTERVAL;
						}

						mIsAsyncLoaded = true;
						mIsThreadStarted = false;
						mEmo.clearDataBuffer();
					}
				}
			}).start();
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " setDataAsyncByte caught:" + ex.toString());
		}
	}

	public Bitmap getThumbBitmap() {
		synchronized (mThumbMutex) {
			if (mEmo != null) {
				return mEmo.mThumbBitmap;
			}
		}
		return null;
	}

	public void setId(int id) {
		mId = id;
	}

	public void setEmoSize(int width, int height) {
		mEmoWidth = width;
		mEmoHeight = height;
	}

	public void setEmoMode(int mode) {
		mEmoMode = mode;
	}

	public int getEmoMode() {
		return mEmoMode;
	}

	public void setDataFromByte(byte[] dispBytes) {
		try {
			releaseEmoData();
			mCodeBytes = dispBytes;
			setDataAsync();
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, "ACKDrawable setDataFromByte caught:" + ex.toString());
		}
	}

	public int getOpacity() {
		return PixelFormat.TRANSPARENT;
	}

	public void releaseEmoData() {
		try {
			mCurFrame = -1;
			mCodeBytes = null;

			synchronized (mThumbMutex) {
				if (mEmo != null && mEmo.mThumbBitmap != null) {
					mEmo.mThumbBitmap.recycle();
					mEmo.mThumbBitmap = null;
				}
			}
			synchronized (mContentMutex) {
				CHGlobal.recycleBitmaps(mBitmaps);
				mBitmaps = null;
				if (mEmo != null) {
					mEmo.clearDataBuffer();
				}

				mIsAsyncLoaded = false;
				mIsThreadStarted = false;
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " releaseEmoData caught:" + ex.toString());
		}
	}

	public Bitmap getNextFrame() {
		Bitmap bitmap = null;

		synchronized (mContentMutex) {
			if (!mIsAsyncLoaded || mBitmaps == null || mBitmaps.length < 1) {
				mCurFrame = 0;
			}
			else {
				if (mCurFrame >= mBitmaps.length) {
					mCurFrame = 0;
				}
				bitmap = mBitmaps[mCurFrame];
			}
		}

		if (bitmap == null || bitmap.isRecycled()) {
			bitmap = getThumbBitmap();
		}
		if (bitmap != null && bitmap.isRecycled()) {
			bitmap = null;
		}
		return bitmap;
	}

	public boolean waitForIdle() {
		if (!CHGlobal.checkStatus(CHGlobal.STATUS_RES_INITED)
				|| !EmoTextView.isContainEmoView(mId)) {
			return false;
		}

		while (CHGlobal.checkStatus(CHGlobal.STATUS_EMO_VIEW_ONSCROLL)) {
			CHGlobal.sleep(CHGlobal.EMO_LOAD_DELAY);
			if (!EmoTextView.isContainEmoView(mId)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void draw(Canvas canvas) {
		try {
			long curTimeMs = System.currentTimeMillis();
			if (curTimeMs - mUpdateTime > mUpdateDuration) {
				mUpdateTime = mUpdateTime + mUpdateDuration;
				if (curTimeMs - mUpdateTime > 2 * mUpdateDuration) {
					mUpdateTime = curTimeMs + mUpdateDuration;
				}
				mCurFrame++;
			}

			Bitmap bitmap = getNextFrame();
			if (bitmap != null) {
				int bw = bitmap.getWidth();
				int bh = bitmap.getHeight();
				if (bw == mEmoWidth && bh == mEmoHeight) {
					canvas.drawBitmap(bitmap, 0, 0, mPainter);
				}
				else {
					canvas.drawBitmap(bitmap, new Rect(0,0,bw, bh),
							new Rect(0, 0, mEmoWidth, mEmoHeight), mPainter);
				}
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	@Override
	public void setAlpha(int alpha) {
	}

	@Override
	public void setColorFilter(ColorFilter colorFilter) {
	}
}
package com.chutils.emo.model;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.chutils.CHGlobal;
import com.chutils.CHLog;
import com.chutils.api.ResUtilsApi;
import com.chutils.emo.manager.ResManager;

//bigbug128
public class EmoModel {
	static final String TAG = EmoModel.class.getSimpleName();

	public byte mType = ResManager.RES_TYPE_NONE;
	public byte[] mCodeBuffer = null;
	public String mTextBuffer = null;
	public byte[] mDataBuffer = null;
	public byte mPermission = 0;
	public int mPosition = 0;
	public int mCount = 0;
	public int mVal1 = 0;
	public int mVal2 = 0;
	public int mVal3 = 0;
	public int mFlags = 0;
	public Bitmap mThumbBitmap = null;
	public Bitmap mBitmap = null;

	public Bitmap getThumbBitmap() {
		try {
			if (mThumbBitmap == null || mThumbBitmap.isRecycled()) {
				mThumbBitmap = ResManager.getThumbBitmapFromNative(mCodeBuffer);
			}
			return mThumbBitmap;
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " getThumbBitmap caught:" + ex.toString());
		}
		return null;
	}

	public Bitmap getBitmap() {
		try {
			if (mBitmap == null || mBitmap.isRecycled()) {
				mBitmap = ResManager.getBitmapFromNative(mCodeBuffer);
			}
			return mBitmap;
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " getBitmap caught:" + ex.toString());
		}
		return null;
	}

	public void getDataBuffer() {
		try {
			if (mDataBuffer == null) {
				mDataBuffer = ResManager.getResourceContent(mCodeBuffer);
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " getDataBuffer catch: " + ex.toString());
		}
	}

	public int getFrameCount() {
		try {
			if (!ResUtilsApi.isNativeLibraryLoaded()) {
				return 0;
			}
			mCount = 0;
			if (mCodeBuffer != null) {
				byte[] selAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];
				if (ResUtilsApi.select(mCodeBuffer, selAddress) <= 0) {
					return 0;
				}

				mCount = ResUtilsApi.getFrameCount(selAddress);
				return mCount;
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " getFrameCount caught:" + ex.toString());
		}
		return 0;
	}

	public Bitmap getFrameBitmap(int index) {

		mDataBuffer = null;
		mBitmap = null;

		try {
			if (!ResUtilsApi.isNativeLibraryLoaded()) {
				return null;
			}
			if (index < mCount && mCodeBuffer != null) {
				byte[] selAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];
				if (ResUtilsApi.select(mCodeBuffer, selAddress) <= 0) {
					return null;
				}

				byte[] dataAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];
				int frameSize = ResUtilsApi.getFrameBytes(selAddress, index, dataAddress);
				if (frameSize <= 0) {
					return null;
				}

				mDataBuffer = new byte[frameSize];
				ResUtilsApi.getFrameContent(dataAddress, mDataBuffer);

				if (mBitmap != null) {
					mBitmap.recycle();
					mBitmap = null;
				}

				mVal1 = CHGlobal.byteArrayToInt16(mDataBuffer, 4);
				mVal2 = CHGlobal.byteArrayToInt16(mDataBuffer, 6);
				mBitmap = BitmapFactory.decodeByteArray(mDataBuffer, 8, frameSize - 8);
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " getFrameBitmap caught:" + ex.toString());
		}
		return mBitmap;
	}

	public static Bitmap getBitmapFromBuffer(byte[] dataBuffer, int offset) {
		Bitmap bitmap = null;
		try {
			if (dataBuffer != null) {
				bitmap = BitmapFactory.decodeByteArray(dataBuffer, offset, dataBuffer.length - offset);
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " getBitmapFromBuffer caught:" + ex.toString());
		}

		if (bitmap == null) {
			CHLog.printLog(CHLog.TAG_ALWAYS, "decodeByteArray return null");
		}
		return bitmap;
	}

	public byte[] prepareAllFrames() {
		mDataBuffer = null;
		mBitmap = null;
		byte[] dataAddress = null;

		try {
			if (!ResUtilsApi.isNativeLibraryLoaded()) {
				return null;
			}
			if (mCodeBuffer != null) {
				byte[] selAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];
				if (ResUtilsApi.select(mCodeBuffer, selAddress) <= 0) {
					return null;
				}

				dataAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];
				if (!ResUtilsApi.getAllFrameBytes(selAddress, dataAddress)) {
					return null;
				}
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " prepareAllFrames caught:" + ex.toString());
		}
		return dataAddress;
	}

	public void getOneFrameData(byte[] dataAddress, int index) {
		mDataBuffer = null;
		mBitmap = null;

		try {
			if (!ResUtilsApi.isNativeLibraryLoaded()) {
				return;
			}
			if (index < mCount) {
				int frameSize = ResUtilsApi.getOneFrameBytes(dataAddress, index);
				if (frameSize <= 0) {
					return;
				}

				mDataBuffer = new byte[frameSize];
				ResUtilsApi.getOneFrameContent(dataAddress, index, mDataBuffer);

				mVal1 = CHGlobal.byteArrayToInt16(mDataBuffer, 0);
				mVal2 = CHGlobal.byteArrayToInt16(mDataBuffer, 2);
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " getOneFrameData caught:" + ex.toString());
		}
	}

	public void clearDataBuffer() {
		mDataBuffer = null;
	}

	public void releaseMemory() {
		if (mBitmap != null) {
			if (!mBitmap.isRecycled()) {
				mBitmap.recycle();
			}
			mBitmap = null;
		}

		if (mThumbBitmap != null) {
			if (!mThumbBitmap.isRecycled()) {
				mThumbBitmap.recycle();
			}
			mThumbBitmap = null;
		}
		mDataBuffer = null;
	}

	public boolean isNewAnimCode() {
		if (mCodeBuffer == null || mCodeBuffer.length != 2) {
			return false;
		}
		return (mCodeBuffer[0] == ResManager.EMO_NEW_CODE[0] && mCodeBuffer[1] == ResManager.EMO_NEW_CODE[1]);
	}

	public boolean isOldAnimCode() {
		if (mCodeBuffer == null || mCodeBuffer.length != 2) {
			return false;
		}
		return (mCodeBuffer[0] == ResManager.EMO_OLD_CODE[0] && mCodeBuffer[1] == ResManager.EMO_OLD_CODE[1]);
	}
}

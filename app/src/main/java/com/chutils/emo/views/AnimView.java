package com.chutils.emo.views;

import androidx.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import com.chutils.CHGlobal;
import com.chutils.CHLog;
import com.chutils.api.ResUtilsApi;
import com.chutils.emo.manager.ResManager;
import com.chutils.emo.model.EmoModel;

import java.util.ArrayList;
import java.util.Random;

//bigbug128
class AnimFrame {
    public byte[] mDataBuffer = null;
    public int mVal1 = 0;
    public int mVal2 = 0;
    public Bitmap mBitmap = null;

    public boolean isValid() {
        return (mBitmap != null && !mBitmap.isRecycled());
    }

    public void release() {
        if (isValid()) {
            mBitmap.recycle();
        }
        mBitmap = null;
        mDataBuffer = null;
    }
}

public class AnimView extends View {
    static final String TAG = AnimView.class.getSimpleName();

    static final int BUFFER_SIZE = 3;

    // activity destroy flag
    boolean mIsThreadCancel = false;

    // for calculate drawing interval time
    private int mInterval = CHGlobal.EMO_DRAW_INTERVAL;
    private int mFixedInterval = CHGlobal.EMO_DRAW_INTERVAL;
    private long mLastDrawMs = 0;

    // activity pause flag
    private boolean mIsPaused = false;

    // anim params
    int mAnimType = ResManager.RES_EMOANI_NONE;
    int mAnimEmoIndex = 0;
    ArrayList<EmoModel> mAnimEmos = new ArrayList<>();

    // resource
    int mAnimWidth = 0;
    int mAnimHeight = 0;

    boolean mIsPostDrawed = false;
    boolean mIsLoaded = false;
    boolean mIsCycled = false;
    EmoModel mCurEmo = null;
    int mEmoDelay = 0;
    int mEmoFrameIndex = 0;
    ArrayList<AnimFrame> mEmoFrameList = null;

    ArrayList<Integer> mQueueEmoList = new ArrayList<>();
    AnimFrame mCycledFrame = null;

    int mEmoOffsetX = 0;
    int mEmoOffsetY = 0;
    float mEmoRate = 0;

    final Object mThreadMutex = new Object();
    final Object mFrameMutex = new Object();
    Random mRandom = new Random();

    public AnimView(Context context) {
        super(context);
        init();
    }

    public AnimView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mInterval = CHGlobal.EMO_DRAW_INTERVAL;
        mFixedInterval = CHGlobal.EMO_DRAW_INTERVAL;
        mLastDrawMs = 0;
    }

    public void pauseAnim() {
        if (!mIsThreadCancel) {
            if (mAnimType != ResManager.RES_EMOANI_NONE) {
                mIsPaused = true;
            }
            CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "pauseAnim");
        }
    }

    public void resumeAnim() {
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "resumeAnim");

        if (!mIsThreadCancel) {
            if (mAnimType != ResManager.RES_EMOANI_NONE) {
                mIsPaused = false;
                mLastDrawMs = 0;
                if (!mIsPostDrawed) {
                    postInvalidateDelayed((long) 200);
                }
            }
        }
    }

    public boolean startAnim(int animType, ArrayList<EmoModel> animEmos, int emoIndex) {
        stopAnim();

        if (getVisibility() != View.VISIBLE) {
            return false;
        }
        if (animEmos.size() <= 0 || animType == ResManager.RES_EMOANI_NONE) {
            return false;
        }
        CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "startAnim: " + animType);

        //set emoanim
        mAnimType = animType;
        mAnimEmos = animEmos;
        mAnimEmoIndex = emoIndex;
        mAnimWidth = animEmos.get(0).mVal1;
        mAnimHeight = animEmos.get(0).mVal2;

        resetAnim();
        return true;
    }

    public boolean startAnim(EmoModel emo, int bw, int bh) {
        if (getVisibility() != View.VISIBLE) {
            return false;
        }

        // set emoanim
        mAnimEmos = new ArrayList<>();
        mAnimEmos.add(emo);
        mAnimEmoIndex = 0;

        mAnimWidth = bw;
        mAnimHeight = bh;
        mAnimType = ResManager.RES_EMOANI_FIRST;

        resetAnim();
        return true;
    }

    void resetAnim() {
        mIsCycled = true;
        mIsLoaded = false;
        mCurEmo = null;
        mQueueEmoList.clear();

        mEmoFrameIndex = 0;
        mEmoFrameList = null;

        mIsPaused = false;
        mIsThreadCancel = false;

        LoadThread thread = new LoadThread();
        thread.start();

        if (!mIsPostDrawed) {
            postInvalidateDelayed(CHGlobal.EMO_DRAW_INTERVAL);
        }
    }

    public void stopAnim() {
        mIsThreadCancel = true;

        synchronized (mThreadMutex) {
            CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " stopAnim Start");
            synchronized (mFrameMutex) {
                releaseEmoFrames();

                if (mCurEmo != null) {
                    mCurEmo.clearDataBuffer();
                }

                mIsCycled = false;
                mEmoDelay = 0;
                mAnimType = ResManager.RES_EMOANI_FIRST;
            }
            CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " stopAnim End");
        }
    }

    void releaseEmoFrames() {
        if (mEmoFrameList != null) {
            for (int i = 0; i < mEmoFrameList.size(); i++) {
                AnimFrame anim = mEmoFrameList.get(i);
                if (anim.mBitmap != null) {
                    if (!anim.mBitmap.isRecycled()) {
                        anim.mBitmap.recycle();
                    }
                }
                anim.mBitmap = null;
                anim.mDataBuffer = null;
            }
            mEmoFrameList = null;
        }
        mEmoFrameIndex = 0;
        mIsLoaded = false;
    }

    private void loadEmoFrames() {
        CHLog.printLog("kAnimView","loadAnimData Start...");

        byte[] dataAddress = mCurEmo.prepareAllFrames();
        if (dataAddress == null) {
            return;
        }

        CHLog.printLog("kAnimView","loadAnimData Frame Loaded...");
        int i, count = mCurEmo.getFrameCount();

        mEmoFrameIndex = 0;
        if (mAnimType == ResManager.RES_EMOANI_GROUP) {
            mEmoFrameIndex = mRandom.nextInt(count);
        }

        mEmoFrameList = new ArrayList<>();
        for (i = 0; i < count; i++) {
            AnimFrame anim = new AnimFrame();

            mCurEmo.getOneFrameData(dataAddress, i);
            anim.mDataBuffer = mCurEmo.mDataBuffer;
            anim.mVal1 = mCurEmo.mVal1;
            anim.mVal2 = mCurEmo.mVal2;

            int index = i - mEmoFrameIndex;
            if (index < BUFFER_SIZE && index >= 0) {
                anim.mBitmap = EmoModel.getBitmapFromBuffer(anim.mDataBuffer, 4);
            }
            mEmoFrameList.add(anim);
        }

        mCurEmo.clearDataBuffer();
        ResUtilsApi.clearDataAddress(dataAddress);

        mIsLoaded = true;
        mIsCycled = false;
        CHLog.printLog("kAnimView","loadAnimData Finished...");
    }

    boolean selectCallerEmo() {
        if (mCycledFrame != null) {
            mCycledFrame.release();
        }

        switch (mAnimType) {
            case ResManager.RES_EMOANI_SELECT: {
                if (mCurEmo == null) {
                    mCurEmo = mAnimEmos.get(mAnimEmoIndex);
                    return true;
                }
                break;
            }
            case ResManager.RES_EMOANI_GROUP: {
                if (mCurEmo != null) {
                    mCurEmo.clearDataBuffer();
                }

                if (mQueueEmoList.size() <= 0) {
                    int i, count = mAnimEmos.size();
                    for (i = 0; i < count; i++) {
                        mQueueEmoList.add(i);
                    }
                }

                int randIndex = mRandom.nextInt(mQueueEmoList.size());
                int emoIndex = mQueueEmoList.get(randIndex);
                mCurEmo = mAnimEmos.get(emoIndex);
                mQueueEmoList.remove(randIndex);
                return true;
            }
            case ResManager.RES_EMOANI_FIRST: {
                if (mCurEmo == null) {
                    mCurEmo = mAnimEmos.get(0);
                    return true;
                }
                break;
            }
            case ResManager.RES_EMOANI_LONG: {
                if (mCurEmo == null) {
                    mCurEmo = mAnimEmos.get(0);
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private final class LoadThread extends Thread {
        @Override
        public void run() {
            synchronized (mThreadMutex) {
                CHLog.printLog("kAnimView", "LoadThread start");
                while (!isInterrupted() && !mIsThreadCancel) {
                    if (!mIsPaused) {
                        synchronized (mFrameMutex) {
                            if (mIsCycled) {
                                if (selectCallerEmo()) {
                                    releaseEmoFrames();
                                    loadEmoFrames();
                                }
                                mIsCycled = false;
                            }
                        }

                        for (int i = 0; i <= BUFFER_SIZE; i++) {
                            int index = (mEmoFrameIndex + i) % mCurEmo.mCount;
                            AnimFrame anim = mEmoFrameList.get(index);
                            if (anim.isValid()) {
                                continue;
                            }

                            anim.mBitmap = EmoModel.getBitmapFromBuffer(anim.mDataBuffer, 4);
                            if (anim.mBitmap == null) {
                                CHLog.printLog(CHLog.TAG_ALWAYS, TAG +  " getBitmapFromBuffer failed. " + mEmoFrameIndex);
                                mEmoFrameIndex = (mEmoFrameIndex + 1) % mCurEmo.mCount;
                                i = -1;
                                continue;
                            }
                        }
                    }
                    CHGlobal.sleep(mIsPaused ? 100 : 1);
                }
                CHLog.printLog("kAnimView", "LoadThread after stopped");
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0 || mAnimWidth <= 0 || mAnimHeight <= 0) {
            return;
        }

        mEmoRate = Math.min((float) width / mAnimWidth, (float) height / mAnimHeight);
        mEmoOffsetX = (int)((mEmoRate* mAnimWidth - width)/2);
        mEmoOffsetY = (int)((mEmoRate* mAnimHeight - height)/2);
    }

    private void drawFrame(Canvas canvas, Bitmap frame, int x, int y) {
        if (frame == null || frame.isRecycled()) {
            return;
        }

        int sw = frame.getWidth();
        int sh = frame.getHeight();
        Rect srcRect = new Rect(0, 0, sw, sh);

        //1. Crate paint and apply opacity to that
        Paint p = new Paint();
        p.setFilterBitmap(true);
        p.setAntiAlias(true);

        int posX = (int) (mEmoRate * x) - mEmoOffsetX;
        int posY = (int) (mEmoRate * y) - mEmoOffsetY;
        int dw = (int) (mEmoRate * sw);
        int dh = (int) (mEmoRate * sh);
        Rect dstRect = new Rect(posX, posY, posX + dw, posY + dh);

        if (frame != null && !frame.isRecycled()) {
            canvas.drawBitmap(frame, srcRect, dstRect, p);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mAnimType == ResManager.RES_EMOANI_NONE) {
            return;
        }

        long stTimeMs = System.currentTimeMillis();
        if (stTimeMs - mLastDrawMs < 1000) {
            int dt = mFixedInterval - (int)(stTimeMs - mLastDrawMs);
            mInterval = (int)((float) mInterval *0.8f + (float)(mInterval + dt)*0.2f);
            mInterval = Math.max((int)(mFixedInterval *0.7f), mInterval);
            mInterval = Math.min((int)(mFixedInterval *1.4f), mInterval);
        }
        else {
            mInterval = mFixedInterval;
        }
        mLastDrawMs = stTimeMs;

        boolean isDrawCycled = false;
        if (!mIsThreadCancel && !mIsPaused) {
            boolean isDrawFrame = false;
            synchronized (mFrameMutex) {
                if (mIsLoaded) {
                    if(mEmoFrameIndex < mEmoFrameList.size()) {
                        AnimFrame anim = mEmoFrameList.get(mEmoFrameIndex);
                        Bitmap bitmap = anim.mBitmap;
                        if (bitmap != null && !bitmap.isRecycled()) {
                            //프레임그리기
                            isDrawFrame = true;
                            drawFrame(canvas, bitmap, anim.mVal1, anim.mVal2);

                            //프레임플레이정보변경
                            mEmoFrameIndex = (mEmoFrameIndex + 1) % mCurEmo.mCount;
                            if (mEmoFrameIndex == 0) {
                                CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " onDraw cycled");
                                setCycledBitmap(anim);
                                isDrawCycled = true;
                                mIsCycled = true;
                            }


                            //프레임기억기해방
                            bitmap.recycle();
                        }
                        anim.mBitmap = null;
                    }
                }

                //그리기실패이면 이전 화상을 그리기
                if (!isDrawFrame) {
                    if (mCycledFrame != null && mCycledFrame.isValid()) {
                        CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " onDraw cycleFrame");
                        drawFrame(canvas, mCycledFrame.mBitmap, mCycledFrame.mVal1, mCycledFrame.mVal2);
                    }
                    else {
                        CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " onDraw null");
                    }
                }
            }

            if (isDrawCycled && mEmoDelay > 0) {
                postInvalidateDelayed(mEmoDelay);
                mIsPostDrawed = true;
                return;
            }

            long edTimeMs = System.currentTimeMillis();
            int dtMs = Math.max(mInterval - (int)(edTimeMs - stTimeMs), 10);
            this.postInvalidateDelayed((long)dtMs);
            mIsPostDrawed = true;
        }
    }

    void setCycledBitmap(AnimFrame animFrame) {
        if (animFrame == null || !animFrame.isValid()) {
            return;
        }

        if (mCycledFrame != null) {
            mCycledFrame.release();
        }
        else {
            mCycledFrame = new AnimFrame();
        }

        mCycledFrame.mVal1 = animFrame.mVal1;
        mCycledFrame.mVal2 = animFrame.mVal2;
        mCycledFrame.mBitmap = animFrame.mBitmap.copy(animFrame.mBitmap.getConfig(), false);
    }

    public void setCycleDelay(int delay) {
        mEmoDelay = delay;
    }
}

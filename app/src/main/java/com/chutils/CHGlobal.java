package com.chutils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;

import com.chutils.emo.model.EmoImageSpan;
import com.chutils.emo.model.EmoModel;

import java.util.ArrayList;

public class CHGlobal {
    public static final int CH_PRODUCT_ID = 1001;

    //인증서고관련상수들
    public static final int AUTH_CODE_FAIL = 0;
    public static final int AUTH_CODE_INVALID_FILE = 1;
    public static final int AUTH_CODE_INVALID_PARAM = 2;
    public static final int AUTH_CODE_INVALID_DTM = 3;
    public static final int AUTH_CODE_INVALID_EXTERNAL = 4;
    public static final int AUTH_CODE_INVALID_CODE = 5;
    public static final int AUTH_CODE_CANTSAVE_FILE = 6;
    public static final int AUTH_CODE_SUCCESS = 7;

    // 앱의 기본상태들
    public final static int STATUS_NONE = 0;
    public final static int STATUS_PREMISSION_GRANTED = (1 << 1);
    public final static int STATUS_RES_INITED = (1 << 10);
    public final static int STATUS_EMO_VIEW_ONSCROLL = (1 << 20);

    //앱 기본상태들
    public static int mStatus = STATUS_NONE;

    //기타 상수들
    public static final int EMO_DRAW_INTERVAL = 40;
    public static final int EMO_LOAD_DELAY = 50;

    //그림기호크기
    public static final int EMOICO_SIZE_IN_GRID = 48;
    public static final int EMOGI_SIZE_IN_GRID = 72;

    //함수들
    public static boolean bitContain(int val, int check) {
        return ((val & check) != 0);
    }

    public static boolean bitCheck(int val, int check) {
        return ((val & check) == check);
    }

    public static int bitAdd(int val, int check) {
        return (val | check);
    }

    public static int bitRemove(int val, int check) {
        return (val & (~check));
    }

    public static int byteArrayToInt8(byte[] b, int offset) {
        return (b[offset] & 0xFF);
    }

    public static int byteArrayToInt16(byte[] b, int offset) {
        return (b[offset] & 0xFF) + ((b[1 + offset] & 0xFF) << 8);
    }

    public static int byteArrayToInt32(byte[] b, int offset) {
        if (b == null) {
            return 0;
        }
        return (b[0 + offset] & 0xFF) + ((b[1 + offset] & 0xFF) << 8) +
                ((b[2 + offset] & 0xFF) << 16) + ((b[3 + offset] & 0xFF) << 24);
    }

    public static byte[] int32ToByteArray(int param) {
        byte[] ret = new byte[4];
        ret[0] = (byte) (param & 0xFF);
        ret[1] = (byte) ((param >> 8) & 0xFF);
        ret[2] = (byte) ((param >> 16) & 0xFF);
        ret[3] = (byte) ((param >> 24) & 0xFF);
        return ret;
    }

    public static void int32ToByteArray(int param, byte[] buffer, int pos) {
        buffer[pos] = (byte) (param & 0xFF);
        buffer[pos + 1] = (byte) ((param >> 8) & 0xFF);
        buffer[pos + 2] = (byte) ((param >> 16) & 0xFF);
        buffer[pos + 3] = (byte) ((param >> 24) & 0xFF);
    }

    public static byte[] int16ToByteArray(int param) {
        byte[] ret = new byte[2];
        ret[0] = (byte) (param & 0xFF);
        ret[1] = (byte) ((param >> 8) & 0xFF);
        return ret;
    }

    public static void int16ToByteArray(int param, byte[] buffer, int pos) {
        buffer[pos] = (byte) (param & 0xFF);
        buffer[pos + 1] = (byte) ((param >> 8) & 0xFF);
    }

    public static byte[] int8ToByteArray(int param) {
        byte[] ret = new byte[1];
        ret[0] = (byte) (param & 0xFF);
        return ret;
    }

    public static void int8ToByteArray(int param, byte[] buffer, int pos) {
        buffer[pos] = (byte) (param & 0xFF);
    }

    public static int addStatus(int status) {
        mStatus = bitAdd(mStatus, status);
        return mStatus;
    }

    public static int removeStatus(int status) {
        mStatus = bitRemove(mStatus, status);
        return mStatus;
    }

    public static boolean checkStatus(int status) {
        return bitCheck(mStatus, status);
    }

    public static int setStatus(int status, boolean isEnabled) {
        if (isEnabled) {
            mStatus = bitAdd(mStatus, status);
        }
        else {
            mStatus = bitRemove(mStatus, status);
        }
        return mStatus;
    }

    public static boolean isAscii(byte value) {
        return value >= 0x20 && value < 0x80;
    }

    public static float dpToPx(Context context, float dp) {
        Resources resources = context.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        return dp * ((float) metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    public static void recycleBitmaps(Bitmap[] bitmaps) {
        try {
            if (bitmaps == null) {
                return;
            }

            for (int i = 0; i < bitmaps.length; i++) {
                Bitmap bitmap = bitmaps[i];
                if(bitmap == null){
                    continue;
                }
                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }
        catch (Exception ex) {
            CHLog.printLog(CHLog.TAG_ALWAYS, "recycleBitmapArray caught:" + ex.toString());
        }
    }

    public static void recycleBitmaps(ArrayList<Bitmap> bitmaps) {
        try {
            for (int i = 0; i < bitmaps.size(); i++) {
                Bitmap bitmap = bitmaps.get(i);
                bitmap.recycle();
            }
        }
        catch (Exception ex) {
            CHLog.printLog(CHLog.TAG_ALWAYS, "recycleBitmaps caught:" + ex.toString());
        }

        bitmaps.clear();
    }

    public static void sleep(long delay) {
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static boolean isEqual(byte[] first, byte[] second) {
        try {
            if (first.length == second.length) {
                for (int i = 0; i < first.length; i++) {
                    if (first[i] != second[i]) {
                        return false;
                    }
                }
                return true;
            }

        }
        catch (Exception ex) {
            CHLog.printLog(CHLog.TAG_ALWAYS, "CH isEqual caught:" + ex.toString());
        }
        return false;
    }

    public static String convertSpan2Text(Editable editable, boolean isFake) {
        if (editable == null || editable.length() < 1) {
            return "";
        }

        EmoImageSpan[] spans = editable.getSpans(0, editable.length(), EmoImageSpan.class);
        ArrayList<Integer> edList = new ArrayList<>();
        ArrayList<EmoImageSpan> spanList = new ArrayList<>();

        int i, j, tmp;
        int count = spans.length;
        for (i = 0; i < count; i++) {
            EmoImageSpan span = spans[i];
            edList.add(editable.getSpanEnd(span));
            spanList.add(span);
        }

        for (i = 0; i < count; i++) {
            for (j = i + 1; j < count; j++) {
                if (edList.get(i) > edList.get(j)) {
                    tmp = edList.get(i);
                    edList.set(i, edList.get(j));
                    edList.set(j, tmp);

                    EmoImageSpan span = spanList.get(i);
                    spanList.set(i, spanList.get(j));
                    spanList.set(j, span);
                }
            }
        }

        int prevPos = 0, curPos = 0;
        StringBuilder newStr = new StringBuilder();
        for (i = 0; i < count; i++) {
            EmoImageSpan span = spanList.get(i);
            curPos = editable.getSpanStart(span);
            if (curPos > prevPos) {
                newStr.append(editable.subSequence(prevPos, curPos));
            }
            newStr.append(span.convert2Text(isFake, newStr.length()));
            prevPos = edList.get(i);
        }

        if (prevPos < editable.length()) {
            newStr.append(editable.subSequence(prevPos, editable.length()));
        }
        return newStr.toString();
    }

    public static SpannableString makeImageSpan(Context context, EmoModel emo) {
        if (emo.mThumbBitmap == null || emo.mThumbBitmap.isRecycled()) {
            return null;
        }

        int w, h;
        if (emo.mVal1 > 0) {
            float rate = (float) emo.mVal1 / emo.mThumbBitmap.getWidth();
            w = emo.mVal1;
            h = (int) (emo.mThumbBitmap.getHeight() * rate);
        }
        else if (emo.mVal2 > 0) {
            float rate = (float) emo.mVal2 / emo.mThumbBitmap.getHeight();
            w = (int) (emo.mThumbBitmap.getWidth() * rate);
            h = emo.mVal2;
        }
        else {
            w = emo.mThumbBitmap.getWidth();
            h = emo.mThumbBitmap.getHeight();
        }

        w = (int) CHGlobal.dpToPx(context, (float) w);
        h = (int) CHGlobal.dpToPx(context, (float) h);
        Bitmap bitmap = emo.mThumbBitmap.copy(emo.mThumbBitmap.getConfig(), false);
        Drawable drawable = new BitmapDrawable(bitmap);
        drawable.setBounds(0, 0, w, h);

        CHLog.printLog("hades", "width:" + w + "height:" + h);

        ImageSpan imgSpanObj = null;
        if (emo.mCodeBuffer != null) {
            imgSpanObj = new EmoImageSpan(drawable);
            ((EmoImageSpan) imgSpanObj).mCodeBuffer = emo.mCodeBuffer;
            ((EmoImageSpan) imgSpanObj).mEmoType = emo.mType;
        }
        else {
            imgSpanObj = new ImageSpan(drawable);
        }

        SpannableString emoSpan = new SpannableString(EmoImageSpan.EMO_SPAN_STR);
        emoSpan.setSpan(imgSpanObj, 0, emoSpan.length(), 33);
        return emoSpan;
    }
}

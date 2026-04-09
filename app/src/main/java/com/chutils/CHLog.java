package com.chutils;

import android.content.Context;
import android.util.Log;

public class CHLog {
    static final String TAG = CHLog.class.getSimpleName();

    public Context mContext;

    public static final int MODE_NONE_LOG = 0;
    public static final int MODE_FILE_LOG = 1;
    public static final int MODE_NORMAL = 2;
    public static final int MODE_MIX_LOG = 3;

    public static final String TAG_ALWAYS = "CHLog";
    public static final String TAG_TEST = "CHTest";

    public static int mMode = MODE_MIX_LOG;

    public static boolean mIsInited = false;
    public static boolean mIsFilelog = false;

    public static void initInstance() {
        if (mIsInited) {
            return;
        }
        mIsInited = true;

        if (mMode != MODE_FILE_LOG  && mMode != MODE_MIX_LOG) {
            return;
        }

        mIsFilelog = true;
    }

    public static void exitInstance() {
        if (mIsInited) {
            mIsInited = false;
        }
        mIsFilelog = false;
    }

    public static void printLog(String tag, String log) {
        if (!mIsInited) {
            initInstance();
        }

        if (tag == null || log == null) {
            return;
        }

        switch (mMode) {
            case MODE_FILE_LOG: {
                if (mIsFilelog) {
                }
                break;
            }
            case MODE_NORMAL: {
                Log.e(tag, log);
                break;
            }
            case MODE_MIX_LOG: {
                Log.e(tag, log);
                if (mIsFilelog) {
                }
                break;
            }
        }
    }
}

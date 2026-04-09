package com.chutils.emo.views;

import androidx.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import androidx.appcompat.widget.AppCompatTextView;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.util.TypedValue;

import com.chutils.CHGlobal;
import com.chutils.CHLog;
import com.chutils.api.ResUtilsApi;
import com.chutils.emo.manager.ResManager;
import com.chutils.emo.model.EmoDrawable;
import com.chutils.emo.model.EmoImageSpan;
import com.chutils.emo.model.EmoModel;

import java.util.ArrayList;

//bigbug128
public class EmoTextView extends AppCompatTextView {
	static final String TAG = EmoTextView.class.getSimpleName();

	public static final int EMO_IN_NONE = 0;
	public static final int EMO_IN_GRID_VIEW = 1;
	public static final int EMO_IN_BIG_CONTACT = 2;
	public static final int EMO_IN_CALLER_CONTACT = 3;
	public static final int EMO_IN_MESSAGE = 4;
	public static final int EMO_IN_CONTACT = 5;
	public static final int EMO_IN_SMS_EDIT = 6;
	public static final int EMO_IN_MMS_EDIT = 7;
	public static final int EMO_IN_TRIM = 8;

	public static final int EMOICO_SIZE_IN_GRID = 48;
	public static final int EMOGI_SIZE_IN_GRID = 72;
	public static final int EMO_SIZE_IN_CALLER_CONTACT = 100;
	public static final int EMOICO_SIZE_IN_MESSAGE = 48;
	public static final int EMOGI_SIZE_IN_MESSAGE = 96;
	public static final int EMO_SIZE_IN_CONTACT = 42;
	public static final int EMO_SIZE_IN_BIG_CONTACT = 80;

	static int mLastAttachedId = 0;
	static ArrayList<Integer> mAttachedEmoViews = new ArrayList<>();

	int mEmoTextMode = EMO_IN_NONE;
	boolean mUseEmoInText = true;
	boolean mUsePreviewMode = false;
	boolean mUseAttachSignal = true;

	int mInterval = CHGlobal.EMO_DRAW_INTERVAL;
	int mFixedInterval = CHGlobal.EMO_DRAW_INTERVAL;
	long mLastDrawMs = 0;

	Context mContext = null;

	int mId = -1;
	boolean mIncludeEmo = false;
	Editable mParsedData = null;
	ArrayList<EmoDrawable> mDrawableList = new ArrayList<>();
	EmoModel mEmo = null;

	public EmoTextView(Context context) {
		super(context);
		init(context);
	}

	public EmoTextView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	public EmoTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init(context);
	}

	void init(Context context) {
		mContext = context;

		clearParsedData();
		setFocusable(false);
		setFocusableInTouchMode(false);

		mInterval = CHGlobal.EMO_DRAW_INTERVAL;
		mFixedInterval = CHGlobal.EMO_DRAW_INTERVAL;
		mLastDrawMs = 0;

		setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5.0f, getResources().getDisplayMetrics()), 1.0f);
	}

	void clearParsedData() {
		mId = -1;
		mIncludeEmo = false;

		for (int i = 0; i < mDrawableList.size(); i++) {
			EmoDrawable drawable = mDrawableList.get(i);
			drawable.releaseEmoData();
		}
		mDrawableList.clear();
		mParsedData = null;
	}

	public Editable parseString() {
		try {
			switch (mEmoTextMode) {
				case EMO_IN_GRID_VIEW:
				case EMO_IN_BIG_CONTACT:
				case EMO_IN_CALLER_CONTACT:
				case EMO_IN_CONTACT:{
					return parseEmoString();
				}
				case EMO_IN_TRIM:
				case EMO_IN_SMS_EDIT:
				case EMO_IN_MMS_EDIT:
				case EMO_IN_MESSAGE: {
					return parseEmoText();
				}
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " parseString catch: " + ex.toString());
		}
		return null;
	}

	private Editable parseEmoString() {
		if (mEmo == null) {
			mEmo = ResManager.getEmo(getText().toString());
		}
		if (mEmo == null) {
			return null;
		}

		SpannableStringBuilder builder = new SpannableStringBuilder("");
		CharSequence emoSpan = makeEmoSpan(mEmo, ResManager.EMO_PRODUCT_CH, EmoImageSpan.EMO_SPAN_STR);
		builder.append(emoSpan);
		mEmo.releaseMemory();
		return builder;
	}

	private Editable parseEmoText() {
		CharSequence content = getText();
		byte[] textBuffer = content.toString().getBytes();
		int i, len = textBuffer.length;
		if (len <= 0) {
			return null;
		}

		//본문에 들어있는 이모지들을 변환
		ArrayList<EmoModel> emoArray = new ArrayList<>();
		for (i = 0; i < len; i++) {
			EmoModel emo = ResManager.getPossibleEmo(textBuffer, i, len, true);
			if (emo != null) {
				emoArray.add(emo);
				i += (emo.mVal2 - 1);

				//다매체인 경우
				if (emo.mVal1 == ResUtilsApi.EMO_PRODUCT_CH && emo.mType == ResUtilsApi.RES_TYPE_NONE) {
					return new SpannableStringBuilder("[다매체]");
				}
			}
		}

		//원래본문과 이모지들을 묶는다.
		int curCharPos, curCharLen;
		SpannableStringBuilder builder = new SpannableStringBuilder(content);

		for (i = emoArray.size()-1; i >= 0; i--) {
			EmoModel emo = emoArray.get(i);
			curCharPos = emo.mVal3;
			curCharLen = emo.mFlags;
			builder.replace(curCharPos, curCharPos + curCharLen, makeEmoSpan(emo, emo.mVal1, emo.mTextBuffer));
		}
		return builder;
	}

	CharSequence makeEmoSpan(EmoModel emo, int productId, String strText) {
		if (!mUseEmoInText || emo.mType == ResUtilsApi.RES_TYPE_NONE) {
			switch (mEmoTextMode) {
				case EMO_IN_TRIM: {
					break;
				}
				default: {
					//본문방식으로 이모지를 출력
					String strEmo;
					if (emo.mType == ResManager.RES_TYPE_EMOICO) {
						strEmo = new String(emo.mCodeBuffer);
					}
					else {
						strEmo = ResManager.getProductMarkStr(productId);
					}
					return new SpannableString(strEmo);
				}
			}
			return "";
		}

		//Span방식으로 이모지를 출력
		float dpSize = calcEmoSize( mEmoTextMode, emo);
		int size = (int)CHGlobal.dpToPx(mContext, dpSize);
		if (size <= 0) {
			return null;
		}

		EmoDrawable drawable = new EmoDrawable();
		drawable.setId(mId);
		drawable.setEmoSize(size, size);
		drawable.setBounds(0, 0, size, size);

		switch (mEmoTextMode) {
			case EMO_IN_SMS_EDIT:
			case EMO_IN_MMS_EDIT: {
				drawable.setEmoMode(EmoDrawable.EMO_ONLY_PREVIEW);
				strText = EmoImageSpan.EMO_SPAN_STR;
				break;
			}
			case EMO_IN_GRID_VIEW: {
				if (mUsePreviewMode) {
					drawable.setEmoMode(EmoDrawable.EMO_ONLY_PREVIEW);
				}
				break;
			}
		}
		drawable.setDataFromByte(emo.mCodeBuffer);

		mDrawableList.add(drawable);
		mIncludeEmo = true;

		SpannableString emoStr = new SpannableString(strText);

		EmoImageSpan emoSpan = new EmoImageSpan(drawable);
		emoSpan.mCodeBuffer = emo.mCodeBuffer;
		emoSpan.mEmoType = emo.mType;
		emoStr.setSpan(emoSpan, 0, emoStr.length(), 33);
		return emoStr;
	}

	public static int calcEmoSize(int emoTextMode, EmoModel emo) {
		if (emo == null) {
			return 0;
		}

		int emoType = emo.mType;
		int emoSize = 0;
		switch (emoTextMode) {
			case EMO_IN_GRID_VIEW:
			case EMO_IN_MMS_EDIT: {
				switch (emoType) {
					case ResManager.RES_TYPE_EMOICO: {
						emoSize = EMOICO_SIZE_IN_GRID;
						break;
					}
					case ResManager.RES_TYPE_EMOGI: {
						emoSize = EMOGI_SIZE_IN_GRID;
						break;
					}
				}
				break;
			}
			case EMO_IN_BIG_CONTACT: {
				emoSize = EMO_SIZE_IN_BIG_CONTACT;
				break;
			}
			case EMO_IN_CALLER_CONTACT: {
				emoSize = EMO_SIZE_IN_CALLER_CONTACT;
				break;
			}
			case EMO_IN_MESSAGE: {
				switch (emoType) {
					case ResManager.RES_TYPE_EMOICO:{
						emoSize = EMOICO_SIZE_IN_MESSAGE;
						break;
					}
					case ResManager.RES_TYPE_EMOGI: {
						emoSize = EMOGI_SIZE_IN_MESSAGE;
						break;
					}
				}
				break;
			}
			case EMO_IN_CONTACT: {
				emoSize = EMO_SIZE_IN_CONTACT;
				break;
			}
			case EMO_IN_SMS_EDIT: {
				emoSize = EMOICO_SIZE_IN_GRID;
				break;
			}
		}
		return emoSize;
	}

	@Override
	protected void onDraw(final Canvas canvas) {
		long stTimeMs = System.currentTimeMillis();
		if (stTimeMs - mLastDrawMs < 1000) {
			int dt = mFixedInterval - (int) (stTimeMs - mLastDrawMs);
			mInterval = (int) ((float) mInterval * 0.8f + (float) (mInterval + dt) * 0.2f);
			mInterval = Math.max((int) (mFixedInterval * 0.7f), mInterval);
			mInterval = Math.min((int) (mFixedInterval * 1.4f), mInterval);

			//CHLog.printLog("Emo", "updateInterval:" + String.valueOf(mId) + ":" + String.valueOf(mInterval));
		}
		else {
			mInterval = mFixedInterval;
		}
		mLastDrawMs = stTimeMs;

		super.onDraw(canvas);

		int minEmoMode = EmoDrawable.EMO_ALL_MODE;
		int i, count = mDrawableList.size();
		for (i = 0; i < count; i++)
		{
			minEmoMode = minEmoMode & mDrawableList.get(i).getEmoMode();
		}

		if (CHGlobal.bitCheck(minEmoMode, EmoDrawable.EMO_ONLY_PREVIEW)) {
			return;
		}
		if (mId < 0 || !mIncludeEmo) {
			return;
		}

		long edTimeMs = System.currentTimeMillis();
		this.postInvalidateDelayed((long)Math.max(mInterval - (int) (edTimeMs - stTimeMs), 1));
	}

	public void setEmoText(String emoStr, int strMode) {
		clearParsedData();

		mEmo = null;
		mEmoTextMode = strMode;
		setText(emoStr);

		if (isAttachedToWindow()) {
			activateEmoView();
		}

//		//TODO: bigbug128
//		CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " setEmoText Str:" + emoStr);
	}

	public void setEmoText(CharSequence emoChars, int strMode) {
		clearParsedData();

		mEmo = null;
		mEmoTextMode = strMode;
		setText(emoChars);

		if (isAttachedToWindow()) {
			activateEmoView();
		}

//		//TODO: bigbug128
//		CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " setEmoText Chars:" + emoChars);
	}

	public void setEmoText(EmoModel emo, int strMode) {
		clearParsedData();

		mEmo = emo;
		mEmoTextMode = strMode;
		setText("");

		if (isAttachedToWindow()) {
			activateEmoView();
		}

//		//TODO: bigbug128
//		CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " setEmoText Emo:" + ((mEmo != null) ? new String(mEmo.mCodeBuffer) : ""));
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (mUseAttachSignal) {
			activateEmoView();
		}

//		if (mIncludeEmo) { //TODO: bigbug128
//			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " onAttachedToWindow:" + mId + ":"
//					+ ((mEmo != null) ? new String(mEmo.mCodeBuffer) : ""));
//		}
	}

	public void activateEmoView() {
		try {
			if (mId < 0) {
				mId = mLastAttachedId++;
				mAttachedEmoViews.add(Integer.valueOf(mId));

				mParsedData = parseString();
				if (mParsedData != null) {
					setText(mParsedData);
				}
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " activateEmoTextView caught:" + ex.toString());
		}
	}

	@Override
	protected void onDetachedFromWindow() {
//		if (mIncludeEmo) { //TODO: bigbug128
//			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " onDetachedFromWindow:" + mId + ":"
//					+ ((mEmo != null) ? new String(mEmo.mCodeBuffer) : ""));
//		}

		if (mUseAttachSignal) {
			deactivateEmoView();
		}
		super.onDetachedFromWindow();
	}

	public void deactivateEmoView() {
		try {
			mAttachedEmoViews.remove(Integer.valueOf(mId));
			clearParsedData();
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " deactivateEmoView caught:" + ex.toString());
		}
	}

	public boolean getIncludeEmo() {
		return mIncludeEmo;
	}

	public Editable getParsedData() {
		return mParsedData;
	}

	public void setUseEmoInText(boolean useEmoInText) {
		mUseEmoInText = useEmoInText;
	}

	public boolean getUseEmoInText() {
		return mUseEmoInText;
	}

	public void setUseAttachSignal(boolean useAttachSignal) {
		mUseAttachSignal = useAttachSignal;
	}

	public boolean getUseAttachSignal() {
		return mUseAttachSignal;
	}

	public void setPreivewMode(boolean usePreviewMode) {
		mUsePreviewMode = usePreviewMode;
	}

	public boolean getPreviewMode() {
		return mUsePreviewMode;
	}

	////////////////////////////////////////////////////////////////////////////////////////////////
	public static boolean isContainEmoView(int viewId) {
		return mAttachedEmoViews.contains(Integer.valueOf(viewId));
	}

	public static Editable getEditable(Context context, String emoStr, int emoTextMode, boolean useEmoInText) {
		EmoTextView etv = new EmoTextView(context);
		etv.setUseEmoInText(useEmoInText);
		etv.setEmoText(emoStr, emoTextMode);
		return etv.parseString();
	}

	public static String getEmoTrimText(Context context, String emoStr) {
		EmoTextView etv = new EmoTextView(context);
		etv.setUseEmoInText(false);
		etv.setEmoText(emoStr, EMO_IN_TRIM);

		Editable editable = etv.parseString();
		if (editable == null) {
			return "";
		}
		return editable.toString();
	}
}
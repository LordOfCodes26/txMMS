package com.chutils.emo.views;

import android.content.Context;
import androidx.viewpager.widget.ViewPager;
import android.text.Editable;
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.android.mms.R;
import com.chutils.CHGlobal;
import com.chutils.emo.manager.ResManager;
import com.chutils.emo.model.EmoModel;
public class EmoGridViewPage extends RelativeLayout {
    static public final int EDITBOX_EMO_MAX_COUNT = 10;

    View mViewEmogiRoot;
    LinearLayout mViewEmogiBottomTab;

    EmoViewPageAdapter mEmoViewpageAdapter;
    ViewPager mEmogiViewPager;

    PagerSlidingTabStrip mEmogiSlideTabs;
    ImageView mIvEmogiStart;

    int mCurEmogiTabindex = 0;
    boolean mInitializedEmogiLayout = false;

    private Context mContext;
    private EditText mEditSms;

    public EmoGridViewPage(Context context) {
        super(context);

        mContext = context;
    }

    public EmoGridViewPage(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
    }

    public EmoGridViewPage(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mContext = context;
    }

    public void initView(EditText eEditSms) {
        // init from activity
        mEditSms = eEditSms;

        // init view
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mViewEmogiRoot = inflater.inflate(R.layout.layout_emogi_root, null);
        addView(mViewEmogiRoot);

        mViewEmogiBottomTab = mViewEmogiRoot.findViewById(R.id.layout_emogi_bottom_tab);

        initEmogiLayout();
        mInitializedEmogiLayout = true;
    }

    void initEmogiLayout() {

        mEmogiViewPager = mViewEmogiRoot.findViewById(R.id.view_pager_emo);
        mEmoViewpageAdapter = new EmoViewPageAdapter(getContext(), mEmoClickListener);
        mEmogiViewPager.setAdapter(mEmoViewpageAdapter);
        mEmogiSlideTabs = mViewEmogiRoot.findViewById(R.id.emogi_tabs);
        mEmogiSlideTabs.tabType = 1;
        mEmogiSlideTabs.setViewPager(mEmogiViewPager);

        mIvEmogiStart = mViewEmogiRoot.findViewById(R.id.emogi_iv_start);

        this.post(new Runnable() {
            @Override
            public void run() {
                initEmogiTabIndex();
            }
        });
    }

    public OnClickListener mEmoClickListener = new OnClickListener() {
        final String mLastChars = "";
        View mLastSelection = null;

        @Override
        public void onClick(View arg0) {
            EmoModel emo = (EmoModel) arg0.getTag();
            boolean isEnable = checkEmoCount(mEditSms.getText());

//            try {
//                if (mLastSelection != null) {
//                    mLastSelection.setVisibility(GONE);
//                }
//            }
//            catch (Exception ex) {
//                ex.printStackTrace();
//            }
//            if (arg0.getTag(R.id.selection) != null) {
//                View view = (View) arg0.getTag(R.id.selection);
//                view.setVisibility(View.VISIBLE);
//                mLastSelection = view;
//            }

            if (!isEnable) {
                Toast.makeText(mContext, mContext.getString(R.string.ch350_emoji_max_stickers), Toast.LENGTH_SHORT).show();
                return;
            }

            EmoModel spanEmo = new EmoModel();
            spanEmo.mThumbBitmap = emo.getThumbBitmap();
            spanEmo.mCodeBuffer = emo.mCodeBuffer;
            spanEmo.mType = emo.mType;
            spanEmo.mVal2 = EmoTextView.EMOICO_SIZE_IN_GRID;

            SpannableString emoSpan = CHGlobal.makeImageSpan(mContext, spanEmo);
            if (emoSpan == null) {
                return;
            }

            int cursorStart = mEditSms.getSelectionStart();
            int cursorEnd = mEditSms.getSelectionEnd();
            if (cursorStart > cursorEnd) {
                int swap = cursorStart;
                cursorStart = cursorEnd;
                cursorEnd = swap;
            }
            Editable value = mEditSms.getText();
            if (cursorStart != cursorEnd) {
                value.replace(cursorStart, cursorEnd, "");
            }

            value.insert(cursorStart, emoSpan);
            if(emo.mType == ResManager.RES_TYPE_EMOICO) {
                if(cursorStart > 0) {
                    value.insert(cursorStart, mLastChars);
                }
                value.append(mLastChars);
            }
        }
    };

    private void initEmogiTabIndex() {
        if (!mInitializedEmogiLayout) {
            return;
        }
        if (mCurEmogiTabindex != 0) {
            setEmogiTabIndex(0);
        }
    }

    public void setEmogiTabIndex(final int index) {
        if (mCurEmogiTabindex == index) {
            return;
        }
        mCurEmogiTabindex = index;
        mEmogiViewPager.setCurrentItem(index, true);
    }

    public boolean checkEmoCount(Editable value) {
        if (value == null || value.length() < 1) {
            return true;
        }

        ImageSpan[] spans = value.getSpans(0, value.length() - 1, ImageSpan.class);
        return spans.length < EDITBOX_EMO_MAX_COUNT;
    }

    public void deleteEditable(EditText editText) {
        Editable editable = editText.getText();
        int cursorStart = editText.getSelectionStart();
        int cursorEnd = editText.getSelectionEnd();
        if (cursorStart > cursorEnd) {
            int swap = cursorStart;
            cursorStart = cursorEnd;
            cursorEnd = swap;
        }
        if (cursorStart != cursorEnd) {
            editable.replace(cursorStart, cursorEnd, "");
        } else {
            ImageSpan[] imageSpans = editable.getSpans(0, editable.length() - 1, ImageSpan.class);
            boolean delEmo = false;
            if (imageSpans != null) {
                for (int i = 0; i < imageSpans.length; i++) {
                    ImageSpan span = imageSpans[i];
                    int spanstart = editable.getSpanStart(span);
                    int spanend = editable.getSpanEnd(span);
                    if (cursorStart <= spanend && cursorStart >= spanstart) {
                        delEmo = true;
                        editable.replace(spanstart, spanend, "");
                        break;
                    }
                }
            }
            if (!delEmo) {
                if (cursorStart > 0) {
                    editText.getText().delete(cursorStart - 1, cursorStart);
                    editText.setSelection(cursorStart - 1);
                }
            }
        }
    }
}
package com.chutils.emo.views;


import androidx.annotation.Nullable;
import android.content.Context;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.AttributeSet;

import com.chutils.CHGlobal;
import com.chutils.emo.manager.ResManager;
import com.chutils.emo.model.EmoModel;

import java.util.ArrayList;

public class EmoGridView extends RecyclerView {
    private EmoGridViewAdapter mViewAdapter;
    private Context mContext;
    private EmoModel mTabEmo;

    public EmoGridView(Context context) {
        super(context);
    }

    public EmoGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public EmoGridView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void init(Context context, EmoModel tabEmo, OnClickListener onClickListener) {
        mTabEmo = tabEmo;
        mContext = context;
        ArrayList<EmoModel> emogiList = getEmoInTab(tabEmo);
        mViewAdapter = new EmoGridViewAdapter(context, 0, emogiList, tabEmo);

        String textBuffer = new String(tabEmo.mCodeBuffer);
        int cols = 3;
        if (textBuffer.contains(ResManager.RES_SUBROOT_EMOICO)) {
            cols = 4;
        }
        else if(textBuffer.contains(ResManager.RES_SUBROOT_EMOTXT)) {
            cols = 1;
        }

        mViewAdapter.setEmogiClickListener(onClickListener);
        setLayoutManager(new GridLayoutManager(context, cols));
        addItemDecoration(new SpacingItemDecoration(cols, 0, true));
        setAdapter(mViewAdapter);
        setVerticalScrollBarEnabled(false);
    }

    public ArrayList<EmoModel> getEmoInTab(EmoModel emoTab) {
        return ResManager.getChildResourceInfo(emoTab.mCodeBuffer, -1);
    }

    @Override
    public void onScrollStateChanged(int state) {
        CHGlobal.setStatus(CHGlobal.STATUS_EMO_VIEW_ONSCROLL, (state != 0));
        super.onScrollStateChanged(state);
    }

    public void releaseMemory(){
        mViewAdapter.releaseMemory();
    }
}
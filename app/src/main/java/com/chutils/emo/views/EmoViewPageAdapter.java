package com.chutils.emo.views;

import android.content.Context;
import androidx.viewpager.widget.PagerAdapter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.mms.R;
import com.chutils.CHLog;
import com.chutils.api.ResUtilsApi;
import com.chutils.emo.manager.ResManager;
import com.chutils.emo.model.EmoModel;

import java.util.ArrayList;

public class EmoViewPageAdapter extends PagerAdapter {

    public ArrayList<EmoModel> mVisTabData = new ArrayList<>();
    private EmoGridView mEmoGridView;

    private Context mContext;

    private final View.OnClickListener mOnClickListener;

    public EmoViewPageAdapter(Context context, View.OnClickListener onClickListener) {
        mContext = context;
        mOnClickListener = onClickListener;
        initVisTabData();
    }

    public void initVisTabData() {
        mVisTabData.clear();

        ArrayList<EmoModel> list = ResManager.getChildResourceInfo(ResUtilsApi.RES_SUBROOT_EMOICO.getBytes(), -1);
        mVisTabData.addAll(list);

        list = ResManager.getChildResourceInfo(ResUtilsApi.RES_SUBROOT_EMOGI.getBytes(), -1);
        mVisTabData.addAll(list);

        mVisTabData.add(ResManager.getInfoContent(ResUtilsApi.RES_SUBROOT_EMOTXT.getBytes()));

        if(mVisTabData.size() > 0)
            mVisTabData.remove(mVisTabData.size() - 1);
        for (int i = 0 ; i < mVisTabData.size(); i++) {
            EmoModel emo = mVisTabData.get(i);
            if(emo == null) {
                continue;
            }
            emo.mPosition = i;
        }
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
        LayoutInflater layoutInflater = ((LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE));
        View localView = layoutInflater.inflate(R.layout.fragment_emogi, container, false);
        mEmoGridView = localView.findViewById(R.id.gridview);

        // 대면부초기화
        EmoModel emo = mVisTabData.get(position);
        mEmoGridView.init(mContext, emo, mOnClickListener);

        container.addView(localView);
        return localView;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView((View)object);
    }

    @Override
    public int getCount() {
        if(mVisTabData != null) {
            return mVisTabData.size();
        }
        else {
            return 0;
        }
    }

    @Override
    public boolean isViewFromObject(View view, Object o) {
        return view == o;
    }

    public void releaseMemory() {
        try {
            mContext = null;
            mEmoGridView.releaseMemory();
            for (int i = 0; i < mVisTabData.size(); i++) {
                mVisTabData.get(i).releaseMemory();
            }
        }
        catch (Exception ex) {
            CHLog.printLog(CHLog.TAG_ALWAYS, EmoViewPageAdapter.class.getSimpleName() + "releaseMemory caught" + ex.toString());
        }
    }
}

package com.chutils.emo.views;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.mms.R;
import com.chutils.CHGlobal;
import com.chutils.api.ResUtilsApi;
import com.chutils.emo.model.EmoModel;

import java.util.ArrayList;


/**
 * Created by hgc on 6/14/2016.r
 */

public class EmoGridViewAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements View.OnClickListener {

    private EmoModel mTabEmo;
    private ArrayList<EmoModel> mData = new ArrayList<>();
    private EmoModel mSelEmo;
    private Context mContext;

    private int miPadding5, miPadding10;

    private View.OnClickListener mOnClickListener;

    public EmoGridViewAdapter(@NonNull Context context, @LayoutRes int resource, @NonNull ArrayList<EmoModel> objects, EmoModel tabEmo) {
        mTabEmo = tabEmo;
        mData = objects;
        mContext = context;

        // init
        miPadding5 = (int) CHGlobal.dpToPx(mContext, 5);
        miPadding10 = (int) CHGlobal.dpToPx(mContext, 10);
    }

    public void setData(ArrayList<EmoModel> data) {
        mData = data;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.emogi_line_item2, parent, false);
        OriginalViewHolder holder = new OriginalViewHolder(v);

        holder.textView.setSoundEffectsEnabled(false);
        holder.mTvLayout.setOnClickListener(this);
        holder.mTvLayout.setTag(R.id.adapter, this);
        holder.mTvLayout.setTag(R.id.selection, holder.selection);
        holder.textView.setTag(R.id.adapter, this);
        holder.textView.setTag(R.id.selection, holder.selection);
        return holder;
    }

    @Override
    public int getItemCount() {
        try {
            return mData.size();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return 0;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder pholder, final int position) {

        if (pholder instanceof OriginalViewHolder) {
            OriginalViewHolder holder = (OriginalViewHolder) pholder;

            EmoModel emo = mData.get(position);
            holder.textView.setEmoData(emo, position, mTabEmo);
            holder.mTvLayout.setTag(emo);
            if(emo.mType == ResUtilsApi.RES_TYPE_EMOGI) {
                holder.mTvLayout.setPadding(miPadding5, miPadding5, miPadding5, miPadding5);
            }
            else {
                holder.mTvLayout.setPadding(miPadding10, miPadding10, miPadding10, miPadding10);
            }

            EmoModel selEmo = mSelEmo;
            if (selEmo != null && CHGlobal.isEqual(selEmo.mCodeBuffer, emo.mCodeBuffer)) {
                holder.selection.setVisibility(View.VISIBLE);
            }
            else {
                holder.selection.setVisibility(View.GONE);
            }
            holder.mask.setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(View v) {
        if (mOnClickListener != null) {
            mSelEmo = (EmoModel) v.getTag();
            mOnClickListener.onClick(v);
        }
    }

    public void setEmogiClickListener(View.OnClickListener listener) {
        this.mOnClickListener = listener;
    }

    public class OriginalViewHolder extends RecyclerView.ViewHolder {
        public EmoGridCellView textView;
        public View selection;
        public View mask;
        public LinearLayout mTvLayout;

        public OriginalViewHolder(View v) {
            super(v);
            textView = v.findViewById(R.id.content);
            selection = v.findViewById(R.id.selection);
            mask = v.findViewById(R.id.mask);
            mTvLayout = v.findViewById(R.id.content_layout);
        }
    }

    public void releaseMemory(){
        try{
            for (EmoModel emo : mData){
                emo.releaseMemory();
            }
            mData = null;
        }
        catch (Exception ex){

        }
    }
}
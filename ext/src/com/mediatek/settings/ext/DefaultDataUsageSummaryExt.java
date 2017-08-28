package com.mediatek.settings.ext;

import android.content.Context;
import android.view.View;
import android.view.View.OnClickListener;

public class DefaultDataUsageSummaryExt implements IDataUsageSummaryExt {

    public DefaultDataUsageSummaryExt(Context context) {
    }

    /**
     * Called when user trying to disabling data
     * @param subId  the sub id been disabling
     * @return true if need handled disabling data by host, false if plug-in handled
     * @internal
     */
    @Override
    public boolean onDisablingData(int subId) {
        return true;
    }

    /**
     * Called when DataUsageSummary need set data switch state such as clickable.
     * @param subId current SIM subId
     * @return true if allow data switch.
     * @internal
     */
    @Override
    public boolean isAllowDataEnable(int subId) {
        return true;
    }

    /**
     * Called when host bind the view, plug-in should set customized onClickListener and call
     * the passed listener.onClick if necessary
     * @param context context of the host app
     * @param view the view need to set onClickListener
     * @param listener view on click listener
     * @internal
     */
    @Override
    public void onBindViewHolder(Context context, View view, OnClickListener listener) {
    }
}

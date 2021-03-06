/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settings.datausage;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.INetworkStatsSession;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.SearchIndexableResource;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.BidiFormatter;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.text.style.RelativeSizeSpan;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SummaryPreference;
import com.android.settings.Utils;
import com.android.settings.dashboard.SummaryLoader;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.search.Indexable;
import com.android.settingslib.net.DataUsageController;
import com.mediatek.cta.CtaUtils;
import com.mediatek.settings.sim.SimHotSwapHandler;
import com.mediatek.settings.sim.SimHotSwapHandler.OnSimHotSwapListener;

import java.util.ArrayList;
import java.util.List;

import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_WIFI;

public class DataUsageSummary extends DataUsageBase implements Indexable {

    private static final String TAG = "DataUsageSummary";
    static final boolean LOGD = true;

    public static final boolean TEST_RADIOS = false;
    public static final String TEST_RADIOS_PROP = "test.radios";

    private static final String KEY_STATUS_HEADER = "status_header";
    private static final String KEY_LIMIT_SUMMARY = "limit_summary";
    private static final String KEY_RESTRICT_BACKGROUND = "restrict_background";

    private DataUsageController mDataUsageController;
    private SummaryPreference mSummaryPreference;
    private Preference mLimitPreference;
    private NetworkTemplate mDefaultTemplate;
    private int mDataUsageTemplate;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        boolean hasMobileData = hasMobileData(getContext());
        mDataUsageController = new DataUsageController(getContext());
        addPreferencesFromResource(R.xml.data_usage);

        int defaultSubId = getDefaultSubscriptionId(getContext());
        if (defaultSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            hasMobileData = false;
        }
        mDefaultTemplate = getDefaultTemplate(getContext(), defaultSubId);
        if (hasMobileData) {
            mLimitPreference = findPreference(KEY_LIMIT_SUMMARY);
        } else {
            removePreference(KEY_LIMIT_SUMMARY);
        }
        if (!hasMobileData || !isAdmin()) {
            removePreference(KEY_RESTRICT_BACKGROUND);
        }
        if (hasMobileData) {
            List<SubscriptionInfo> subscriptions =
                    services.mSubscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions == null || subscriptions.size() == 0) {
                addMobileSection(defaultSubId);
            }
            for (int i = 0; subscriptions != null && i < subscriptions.size(); i++) {
                addMobileSection(subscriptions.get(i).getSubscriptionId());
            }
        }
        boolean hasWifiRadio = hasWifiRadio(getContext());
        if (hasWifiRadio) {
            addWifiSection();
        }
        if (hasEthernet(getContext())) {
            addEthernetSection();
        }
        mDataUsageTemplate = hasMobileData ? R.string.cell_data_template
                : hasWifiRadio ? R.string.wifi_data_template
                : R.string.ethernet_data_template;

        mSummaryPreference = (SummaryPreference) findPreference(KEY_STATUS_HEADER);
        setHasOptionsMenu(true);

        /// M: for [SIM Hot Swap] @{
        mSimHotSwapHandler = new SimHotSwapHandler(getActivity().getApplicationContext());
        mSimHotSwapHandler.registerOnSimHotSwap(new OnSimHotSwapListener() {
            @Override
            public void onSimHotSwap() {
                if (getActivity() != null) {
                    Log.d(TAG, "onSimHotSwap, finish Activity~~");
                    getActivity().finish();
                }
            }
        });
        /// @}
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (UserManager.get(getContext()).isAdminUser()) {
            inflater.inflate(R.menu.data_usage, menu);
        }
        /// M: Remove Cellular networks menu item on wifi-only device @{
        if (Utils.isWifiOnly(getActivity())) {
            menu.removeItem(R.id.data_usage_menu_cellular_networks);
        }
        /// @}
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.data_usage_menu_cellular_networks: {
                final Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setComponent(new ComponentName("com.android.phone",
                        "com.android.phone.MobileNetworkSettings"));
                startActivity(intent);
                return true;
            }
            /// M: for [CTA2016 requirement] @{
            // start a cellular data control page
            case R.id.data_usage_menu_cellular_data_control: {
                Log.d(TAG, "select CELLULAR_DATA");
                Intent intent = new Intent("com.mediatek.security.CELLULAR_DATA");
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.e(TAG, "cellular data control activity not found!!!");
                }
                return true;
            }
            /// @}
        }
        return false;
    }

    private void addMobileSection(int subId) {
        TemplatePreferenceCategory category = (TemplatePreferenceCategory)
                inflatePreferences(R.xml.data_usage_cellular);
        category.setTemplate(getNetworkTemplate(subId), subId, services);
        category.pushTemplates(services);
    }

    private void addWifiSection() {
        TemplatePreferenceCategory category = (TemplatePreferenceCategory)
                inflatePreferences(R.xml.data_usage_wifi);
        category.setTemplate(NetworkTemplate.buildTemplateWifiWildcard(), 0, services);
    }

    private void addEthernetSection() {
        TemplatePreferenceCategory category = (TemplatePreferenceCategory)
                inflatePreferences(R.xml.data_usage_ethernet);
        category.setTemplate(NetworkTemplate.buildTemplateEthernet(), 0, services);
    }

    private Preference inflatePreferences(int resId) {
        PreferenceScreen rootPreferences = getPreferenceManager().inflateFromResource(
                getPrefContext(), resId, null);
        Preference pref = rootPreferences.getPreference(0);
        rootPreferences.removeAll();

        PreferenceScreen screen = getPreferenceScreen();
        pref.setOrder(screen.getPreferenceCount());
        screen.addPreference(pref);

        return pref;
    }

    private NetworkTemplate getNetworkTemplate(int subscriptionId) {
        NetworkTemplate mobileAll = NetworkTemplate.buildTemplateMobileAll(
                services.mTelephonyManager.getSubscriberId(subscriptionId));
        return NetworkTemplate.normalize(mobileAll,
                services.mTelephonyManager.getMergedSubscriberIds());
    }

    @Override
    public void onResume() {
        super.onResume();
        updateState();
    }

    private static void verySmallSpanExcept(SpannableString s, CharSequence exception) {
        final float SIZE = 0.8f * 0.8f;
        final int FLAGS = Spannable.SPAN_INCLUSIVE_INCLUSIVE;
        final int exceptionStart = TextUtils.indexOf(s, exception);
        if (exceptionStart == -1) {
           s.setSpan(new RelativeSizeSpan(SIZE), 0, s.length(), FLAGS);
        } else {
            if (exceptionStart > 0) {
                s.setSpan(new RelativeSizeSpan(SIZE), 0, exceptionStart, FLAGS);
            }
            final int exceptionEnd = exceptionStart + exception.length();
            if (exceptionEnd < s.length()) {
                s.setSpan(new RelativeSizeSpan(SIZE), exceptionEnd, s.length(), FLAGS);
            }
        }
    }

    private static CharSequence formatTitle(Context context, String template, long usageLevel) {
        final SpannableString amountTemplate = new SpannableString(
                context.getString(com.android.internal.R.string.fileSizeSuffix)
                .replace("%1$s", "^1").replace("%2$s", "^2"));
        verySmallSpanExcept(amountTemplate, "^1");
        final Formatter.BytesResult usedResult = Formatter.formatBytes(context.getResources(),
                usageLevel, Formatter.FLAG_SHORTER);
        final CharSequence formattedUsage = TextUtils.expandTemplate(amountTemplate,
                usedResult.value, usedResult.units);

        final SpannableString fullTemplate = new SpannableString(template.replace("%1$s", "^1"));
        verySmallSpanExcept(fullTemplate, "^1");
        return TextUtils.expandTemplate(fullTemplate,
                BidiFormatter.getInstance().unicodeWrap(formattedUsage));
    }

    private void updateState() {
        DataUsageController.DataUsageInfo info = mDataUsageController.getDataUsageInfo(
                mDefaultTemplate);
        Context context = getContext();
        if (mSummaryPreference != null) {
            mSummaryPreference.setTitle(
                    formatTitle(context, getString(mDataUsageTemplate), info.usageLevel));
            long limit = info.limitLevel;
            if (limit <= 0) {
                limit = info.warningLevel;
            }
            if (info.usageLevel > limit) {
                limit = info.usageLevel;
            }
            mSummaryPreference.setSummary(info.period);
            mSummaryPreference.setLabels(Formatter.formatFileSize(context, 0),
                    Formatter.formatFileSize(context, limit));
            mSummaryPreference.setRatios(info.usageLevel / (float) limit, 0,
                    (limit - info.usageLevel) / (float) limit);
        }
        if (mLimitPreference != null) {
            String warning = Formatter.formatFileSize(context, info.warningLevel);
            String limit = Formatter.formatFileSize(context, info.limitLevel);
            mLimitPreference.setSummary(getString(info.limitLevel <= 0 ? R.string.cell_warning_only
                    : R.string.cell_warning_and_limit, warning, limit));
        }

        PreferenceScreen screen = getPreferenceScreen();
        for (int i = 1; i < screen.getPreferenceCount(); i++) {
            ((TemplatePreferenceCategory) screen.getPreference(i)).pushTemplates(services);
        }
    }

    @Override
    protected int getMetricsCategory() {
        return MetricsEvent.DATA_USAGE_SUMMARY;
    }

    /**
     * Test if device has an ethernet network connection.
     */
    public boolean hasEthernet(Context context) {
        if (TEST_RADIOS) {
            return SystemProperties.get(TEST_RADIOS_PROP).contains("ethernet");
        }

        final ConnectivityManager conn = ConnectivityManager.from(context);
        final boolean hasEthernet = conn.isNetworkSupported(TYPE_ETHERNET);

        final long ethernetBytes;
        try {
            INetworkStatsSession statsSession = services.mStatsService.openSession();
            if (statsSession != null) {
                ethernetBytes = statsSession.getSummaryForNetwork(
                        NetworkTemplate.buildTemplateEthernet(), Long.MIN_VALUE, Long.MAX_VALUE)
                        .getTotalBytes();
                TrafficStats.closeQuietly(statsSession);
            } else {
                ethernetBytes = 0;
            }
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // only show ethernet when both hardware present and traffic has occurred
        return hasEthernet && ethernetBytes > 0;
    }

    public static boolean hasMobileData(Context context) {
        return ConnectivityManager.from(context).isNetworkSupported(
                ConnectivityManager.TYPE_MOBILE);
    }

    /**
     * Test if device has a Wi-Fi data radio.
     */
    public static boolean hasWifiRadio(Context context) {
        if (TEST_RADIOS) {
            return SystemProperties.get(TEST_RADIOS_PROP).contains("wifi");
        }

        final ConnectivityManager conn = ConnectivityManager.from(context);
        return conn.isNetworkSupported(TYPE_WIFI);
    }

    public static int getDefaultSubscriptionId(Context context) {
        SubscriptionManager subManager = SubscriptionManager.from(context);
        if (subManager == null) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        SubscriptionInfo subscriptionInfo = subManager.getDefaultDataSubscriptionInfo();
        if (subscriptionInfo == null) {
            List<SubscriptionInfo> list = subManager.getAllSubscriptionInfoList();
            if (list.size() == 0) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }
            subscriptionInfo = list.get(0);
        }
        Log.d(TAG, "getDefaultSubscriptionId = " + subscriptionInfo);
        return subscriptionInfo.getSubscriptionId();
    }

    public static NetworkTemplate getDefaultTemplate(Context context, int defaultSubId) {
        if (hasMobileData(context) && defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            TelephonyManager telephonyManager = TelephonyManager.from(context);
            NetworkTemplate mobileAll = NetworkTemplate.buildTemplateMobileAll(
                    telephonyManager.getSubscriberId(defaultSubId));
            return NetworkTemplate.normalize(mobileAll,
                    telephonyManager.getMergedSubscriberIds());
        } else if (hasWifiRadio(context)) {
            return NetworkTemplate.buildTemplateWifiWildcard();
        } else {
            return NetworkTemplate.buildTemplateEthernet();
        }
    }

    private static class SummaryProvider
            implements SummaryLoader.SummaryProvider {

        private final Activity mActivity;
        private final SummaryLoader mSummaryLoader;
        private final DataUsageController mDataController;

        public SummaryProvider(Activity activity, SummaryLoader summaryLoader) {
            mActivity = activity;
            mSummaryLoader = summaryLoader;
            mDataController = new DataUsageController(activity);
        }

        @Override
        public void setListening(boolean listening) {
            if (listening) {
                DataUsageController.DataUsageInfo info = mDataController.getDataUsageInfo();
                String used;
                if (info == null) {
                    used = Formatter.formatFileSize(mActivity, 0);
                } else if (info.limitLevel <= 0) {
                    used = Formatter.formatFileSize(mActivity, info.usageLevel);
                } else {
                    used = Utils.formatPercentage(info.usageLevel, info.limitLevel);
                }
                mSummaryLoader.setSummary(this,
                        mActivity.getString(R.string.data_usage_summary_format, used));
            }
        }
    }

    public static final SummaryLoader.SummaryProviderFactory SUMMARY_PROVIDER_FACTORY
            = new SummaryLoader.SummaryProviderFactory() {
        @Override
        public SummaryLoader.SummaryProvider createSummaryProvider(Activity activity,
                                                                   SummaryLoader summaryLoader) {
            return new SummaryProvider(activity, summaryLoader);
        }
    };

    /**
     * For search
     */
    public static final Indexable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider() {

            @Override
            public List<SearchIndexableResource> getXmlResourcesToIndex(Context context,
                    boolean enabled) {
                ArrayList<SearchIndexableResource> resources = new ArrayList<>();
                SearchIndexableResource resource = new SearchIndexableResource(context);
                resource.xmlResId = R.xml.data_usage;
                resources.add(resource);

                if (hasMobileData(context)) {
                    resource = new SearchIndexableResource(context);
                    resource.xmlResId = R.xml.data_usage_cellular;
                    resources.add(resource);
                }
                if (hasWifiRadio(context)) {
                    resource = new SearchIndexableResource(context);
                    resource.xmlResId = R.xml.data_usage_wifi;
                    resources.add(resource);
                }
                return resources;
            }

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                ArrayList<String> keys = new ArrayList<>();
                boolean hasMobileData = ConnectivityManager.from(context).isNetworkSupported(
                        ConnectivityManager.TYPE_MOBILE);

                if (hasMobileData) {
                    keys.add(KEY_RESTRICT_BACKGROUND);
                }

                return keys;
            }
        };

    ///------------------------------------MTK------------------------------------------------

    /// M: for [SIM Hot Swap]
    private SimHotSwapHandler mSimHotSwapHandler;

    @Override
    public void onDestroy() {
        super.onDestroy();
        /// M: for [Sim Hot Swap]
        mSimHotSwapHandler.unregisterOnSimHotSwap();
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        /// M: for [CTA2016 requirement] @{
        MenuItem cellularDataControl = menu.findItem(R.id.data_usage_menu_cellular_data_control);
        if (cellularDataControl != null) {
            cellularDataControl.setVisible(CtaUtils.isCtaSupported());
        }
        /// @}
    }
}

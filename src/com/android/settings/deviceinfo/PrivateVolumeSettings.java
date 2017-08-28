/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.deviceinfo;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.provider.DocumentsContract;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceCategory;
import android.support.v7.preference.PreferenceGroup;
import android.support.v7.preference.PreferenceScreen;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.format.Formatter.BytesResult;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.Settings.StorageUseActivity;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.Utils;
import com.android.settings.applications.ManageApplications;
import com.android.settings.deviceinfo.StorageSettings.MountTask;
import com.android.settingslib.deviceinfo.StorageMeasurement;
import com.android.settingslib.deviceinfo.StorageMeasurement.MeasurementDetails;
import com.android.settingslib.deviceinfo.StorageMeasurement.MeasurementReceiver;
import com.google.android.collect.Lists;

import com.mediatek.storage.StorageManagerEx;
import com.mediatek.settings.UtilsExt;
import com.mediatek.settings.ext.IStorageSettingsExt;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static com.android.settings.deviceinfo.StorageSettings.TAG;

/**
 * Panel showing summary and actions for a {@link VolumeInfo#TYPE_PRIVATE}
 * storage volume.
 */
public class PrivateVolumeSettings extends SettingsPreferenceFragment {
    // TODO: disable unmount when providing over MTP/PTP
    // TODO: warn when mounted read-only

    private static final String TAG_RENAME = "rename";
    private static final String TAG_OTHER_INFO = "otherInfo";
    private static final String TAG_USER_INFO = "userInfo";
    private static final String TAG_CONFIRM_CLEAR_CACHE = "confirmClearCache";

    private static final String AUTHORITY_MEDIA = "com.android.providers.media.documents";

    private static final int[] ITEMS_NO_SHOW_SHARED = new int[] {
            R.string.storage_detail_apps,
    };

    private static final int[] ITEMS_SHOW_SHARED = new int[] {
            R.string.storage_detail_apps,
            R.string.storage_detail_images,
            R.string.storage_detail_videos,
            R.string.storage_detail_audio,
            R.string.storage_detail_other
    };

    private StorageManager mStorageManager;
    private UserManager mUserManager;

    private String mVolumeId;
    private VolumeInfo mVolume;
    private VolumeInfo mSharedVolume;

    private StorageMeasurement mMeasure;

    private UserInfo mCurrentUser;
    private IStorageSettingsExt mExt;

    private StorageSummaryPreference mSummary;
    private List<StorageItemPreference> mItemPreferencePool = Lists.newArrayList();
    private List<PreferenceCategory> mHeaderPreferencePool = Lists.newArrayList();
    private int mHeaderPoolIndex;
    private int mItemPoolIndex;

    private Preference mExplore;

    private boolean mNeedsUpdate;

    private boolean isVolumeValid() {
        return (mVolume != null) && (mVolume.getType() == VolumeInfo.TYPE_PRIVATE)
                && mVolume.isMountedReadable();
    }

    public PrivateVolumeSettings() {
        setRetainInstance(true);
    }

    @Override
    protected int getMetricsCategory() {
        return MetricsEvent.DEVICEINFO_STORAGE;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Context context = getActivity();

        mUserManager = context.getSystemService(UserManager.class);
        mStorageManager = context.getSystemService(StorageManager.class);

        mVolumeId = getArguments().getString(VolumeInfo.EXTRA_VOLUME_ID);
        mVolume = mStorageManager.findVolumeById(mVolumeId);

        // Find the emulated shared storage layered above this private volume
        mSharedVolume = mStorageManager.findEmulatedForPrivate(mVolume);

        mMeasure = new StorageMeasurement(context, mVolume, mSharedVolume);
        mMeasure.setReceiver(mReceiver);

        if (!isVolumeValid()) {
            getActivity().finish();
            return;
        }

        addPreferencesFromResource(R.xml.device_info_storage_volume);
        getPreferenceScreen().setOrderingAsAdded(true);

        mSummary = new StorageSummaryPreference(getPrefContext());
        mCurrentUser = mUserManager.getUserInfo(UserHandle.myUserId());

        mExplore = buildAction(R.string.storage_menu_explore);

        mNeedsUpdate = true;

        setHasOptionsMenu(true);
        mExt = UtilsExt.getStorageSettingsPlugin(context);
        mExt.initCustomizationStoragePlugin(context);
    }

    private void setTitle() {
        getActivity().setTitle(mStorageManager.getBestVolumeDescription(mVolume));
    }

    private void update() {
        if (!isVolumeValid()) {
            getActivity().finish();
            return;
        }

        setTitle();

        // Valid options may have changed
        getFragmentManager().invalidateOptionsMenu();

        final Context context = getActivity();
        final PreferenceScreen screen = getPreferenceScreen();

        screen.removeAll();

        addPreference(screen, mSummary);

        List<UserInfo> allUsers = mUserManager.getUsers();
        final int userCount = allUsers.size();
        final boolean showHeaders = userCount > 1;
        final boolean showShared = (mSharedVolume != null) && mSharedVolume.isMountedReadable();

        mItemPoolIndex = 0;
        mHeaderPoolIndex = 0;

        int addedUserCount = 0;
        // Add current user and its profiles first
        for (int userIndex = 0; userIndex < userCount; ++userIndex) {
            final UserInfo userInfo = allUsers.get(userIndex);
            if (isProfileOf(mCurrentUser, userInfo)) {
                final PreferenceGroup details = showHeaders ?
                        addCategory(screen, userInfo.name) : screen;
                addDetailItems(details, showShared, userInfo.id);
                ++addedUserCount;
            }
        }

        // Add rest of users
        if (userCount - addedUserCount > 0) {
            PreferenceGroup otherUsers = addCategory(screen,
                    getText(R.string.storage_other_users));
            for (int userIndex = 0; userIndex < userCount; ++userIndex) {
                final UserInfo userInfo = allUsers.get(userIndex);
                if (!isProfileOf(mCurrentUser, userInfo)) {
                    addItem(otherUsers, /* titleRes */ 0, userInfo.name, userInfo.id);
                }
            }
        }

        addItem(screen, R.string.storage_detail_cached, null, UserHandle.USER_NULL);

        if (showShared) {
            addPreference(screen, mExplore);
        }

        final File file = mVolume.getPath();
        final long totalBytes = file.getTotalSpace();
        final long freeBytes = file.getFreeSpace();
        final long usedBytes = totalBytes - freeBytes;

        final BytesResult result = Formatter.formatBytes(getResources(), usedBytes, 0);
        mSummary.setTitle(TextUtils.expandTemplate(getText(R.string.storage_size_large),
                result.value, result.units));
        mSummary.setSummary(getString(R.string.storage_volume_used,
                Formatter.formatFileSize(context, totalBytes)));
        mSummary.setPercent((int) ((usedBytes * 100) / totalBytes));
        mExt.updateCustomizedPrivateSettingsPlugin(screen, mVolume);
        mMeasure.forceMeasure();
        mNeedsUpdate = false;
    }

    private void addPreference(PreferenceGroup group, Preference pref) {
        pref.setOrder(Preference.DEFAULT_ORDER);
        group.addPreference(pref);
    }

    private PreferenceCategory addCategory(PreferenceGroup group, CharSequence title) {
        PreferenceCategory category;
        if (mHeaderPoolIndex < mHeaderPreferencePool.size()) {
            category = mHeaderPreferencePool.get(mHeaderPoolIndex);
        } else {
            category = new PreferenceCategory(getPrefContext(), null,
                    com.android.internal.R.attr.preferenceCategoryStyle);
            mHeaderPreferencePool.add(category);
        }
        category.setTitle(title);
        category.removeAll();
        addPreference(group, category);
        ++mHeaderPoolIndex;
        return category;
    }

    private void addDetailItems(PreferenceGroup category, boolean showShared, int userId) {
        final int[] itemsToAdd = (showShared ? ITEMS_SHOW_SHARED : ITEMS_NO_SHOW_SHARED);
        for (int i = 0; i < itemsToAdd.length; ++i) {
            addItem(category, itemsToAdd[i], null, userId);
        }
    }

    private void addItem(PreferenceGroup group, int titleRes, CharSequence title, int userId) {
        StorageItemPreference item;
        if (mItemPoolIndex < mItemPreferencePool.size()) {
            item = mItemPreferencePool.get(mItemPoolIndex);
        } else {
            item = buildItem();
            mItemPreferencePool.add(item);
        }
        if (title != null) {
            item.setTitle(title);
            item.setKey(title.toString());
        } else {
            item.setTitle(titleRes);
            item.setKey(Integer.toString(titleRes));
        }
        item.setSummary(R.string.memory_calculating_size);
        item.userHandle = userId;
        addPreference(group, item);
        ++mItemPoolIndex;
    }

    private StorageItemPreference buildItem() {
        final StorageItemPreference item = new StorageItemPreference(getPrefContext());
        return item;
    }

    private Preference buildAction(int titleRes) {
        final Preference pref = new Preference(getPrefContext());
        pref.setTitle(titleRes);
        pref.setKey(Integer.toString(titleRes));
        return pref;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Refresh to verify that we haven't been formatted away
        mVolume = mStorageManager.findVolumeById(mVolumeId);
        if (!isVolumeValid()) {
            getActivity().finish();
            return;
        }

        mStorageManager.registerListener(mStorageListener);

        if (mNeedsUpdate) {
            update();
        } else {
            setTitle();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mStorageManager.unregisterListener(mStorageListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mMeasure != null) {
            mMeasure.onDestroy();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.storage_volume, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        if (!isVolumeValid()) return;

        final MenuItem rename = menu.findItem(R.id.storage_rename);
        final MenuItem mount = menu.findItem(R.id.storage_mount);
        final MenuItem unmount = menu.findItem(R.id.storage_unmount);
        final MenuItem format = menu.findItem(R.id.storage_format);
        final MenuItem migrate = menu.findItem(R.id.storage_migrate);

        // Actions live in menu for non-internal private volumes; they're shown
        // as preference items for public volumes.
        if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(mVolume.getId())) {
            rename.setVisible(false);
            mount.setVisible(false);
            unmount.setVisible(false);
            format.setVisible(false);
        } else {
            rename.setVisible(mVolume.getType() == VolumeInfo.TYPE_PRIVATE);
            mount.setVisible(mVolume.getState() == VolumeInfo.STATE_UNMOUNTED);
            unmount.setVisible(mVolume.isMountedReadable());
            format.setVisible(true);
        }

        format.setTitle(R.string.storage_menu_format_public);

        // Only offer to migrate when not current storage
        final VolumeInfo privateVol = getActivity().getPackageManager()
                .getPrimaryStorageCurrentVolume();
        migrate.setVisible((privateVol != null)
                && (privateVol.getType() == VolumeInfo.TYPE_PRIVATE)
                && !Objects.equals(mVolume, privateVol)
                /// M: Hide the entrance when migrating.
                && StorageManagerEx.isSetPrimaryStorageUuidFinished());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final Context context = getActivity();
        final Bundle args = new Bundle();
        switch (item.getItemId()) {
            case R.id.storage_rename:
                RenameFragment.show(this, mVolume);
                return true;
            case R.id.storage_mount:
                new MountTask(context, mVolume).execute();
                return true;
            case R.id.storage_unmount:
                args.putString(VolumeInfo.EXTRA_VOLUME_ID, mVolume.getId());
                startFragment(this, PrivateVolumeUnmount.class.getCanonicalName(),
                        R.string.storage_menu_unmount, 0, args);
                return true;
            case R.id.storage_format:
                args.putString(VolumeInfo.EXTRA_VOLUME_ID, mVolume.getId());
                startFragment(this, PrivateVolumeFormat.class.getCanonicalName(),
                        R.string.storage_menu_format, 0, args);
                return true;
            case R.id.storage_migrate:
                final Intent intent = new Intent(context, StorageWizardMigrateConfirm.class);
                intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, mVolume.getId());
                startActivity(intent);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference pref) {
        // TODO: launch better intents for specific volume

        final int userId = (pref instanceof StorageItemPreference ?
                ((StorageItemPreference)pref).userHandle : -1);
        int itemTitleId;
        try {
            itemTitleId = Integer.parseInt(pref.getKey());
        } catch (NumberFormatException e) {
            itemTitleId = 0;
        }
        Intent intent = null;
        switch (itemTitleId) {
            case R.string.storage_detail_apps: {
                Bundle args = new Bundle();
                args.putString(ManageApplications.EXTRA_CLASSNAME,
                        StorageUseActivity.class.getName());
                args.putString(ManageApplications.EXTRA_VOLUME_UUID, mVolume.getFsUuid());
                args.putString(ManageApplications.EXTRA_VOLUME_NAME, mVolume.getDescription());
                intent = Utils.onBuildStartFragmentIntent(getActivity(),
                        ManageApplications.class.getName(), args, null, R.string.apps_storage, null,
                        false);

            } break;
            case R.string.storage_detail_images: {
                intent = new Intent(DocumentsContract.ACTION_BROWSE);
                intent.setData(DocumentsContract.buildRootUri(AUTHORITY_MEDIA, "images_root"));
                intent.addCategory(Intent.CATEGORY_DEFAULT);

            } break;
            case R.string.storage_detail_videos: {
                intent = new Intent(DocumentsContract.ACTION_BROWSE);
                intent.setData(DocumentsContract.buildRootUri(AUTHORITY_MEDIA, "videos_root"));
                intent.addCategory(Intent.CATEGORY_DEFAULT);

            } break;
            case R.string.storage_detail_audio: {
                intent = new Intent(DocumentsContract.ACTION_BROWSE);
                intent.setData(DocumentsContract.buildRootUri(AUTHORITY_MEDIA, "audio_root"));
                intent.addCategory(Intent.CATEGORY_DEFAULT);

            } break;
            case R.string.storage_detail_other: {
                OtherInfoFragment.show(this, mStorageManager.getBestVolumeDescription(mVolume),
                        mSharedVolume);
                return true;

            }
            case R.string.storage_detail_cached: {
                ConfirmClearCacheFragment.show(this);
                return true;

            }
            case R.string.storage_menu_explore: {
                intent = mSharedVolume.buildBrowseIntent();
            } break;
            case 0: {
                UserInfoFragment.show(this, pref.getTitle(), pref.getSummary());
                return true;
            }
        }

        if (intent != null) {
            try {
                if (userId == -1) {
                    startActivity(intent);
                } else {
                    getActivity().startActivityAsUser(intent, new UserHandle(userId));
                }
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "No activity found for " + intent);
            }
            return true;
        }
        return super.onPreferenceTreeClick(pref);
    }

    private final MeasurementReceiver mReceiver = new MeasurementReceiver() {
        @Override
        public void onDetailsChanged(MeasurementDetails details) {
            updateDetails(details);
            mExt.updateCustomizedPrefDetails(mVolume);
        }
    };

    private void updateDetails(MeasurementDetails details) {
        for (int i = 0; i < mItemPoolIndex; ++i) {
            StorageItemPreference item = mItemPreferencePool.get(i);
            final int userId = item.userHandle;
            int itemTitleId;
            try {
                itemTitleId = Integer.parseInt(item.getKey());
            } catch (NumberFormatException e) {
                itemTitleId = 0;
            }
            switch (itemTitleId) {
                case R.string.storage_detail_apps: {
                    updatePreference(item, details.appsSize.get(userId));
                } break;
                case R.string.storage_detail_images: {
                    final long imagesSize = totalValues(details, userId,
                            Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES,
                            Environment.DIRECTORY_PICTURES);
                    updatePreference(item, imagesSize);
                } break;
                case R.string.storage_detail_videos: {
                    final long videosSize = totalValues(details, userId,
                            Environment.DIRECTORY_MOVIES);
                    updatePreference(item, videosSize);
                } break;
                case R.string.storage_detail_audio: {
                    final long audioSize = totalValues(details, userId,
                            Environment.DIRECTORY_MUSIC,
                            Environment.DIRECTORY_ALARMS, Environment.DIRECTORY_NOTIFICATIONS,
                            Environment.DIRECTORY_RINGTONES, Environment.DIRECTORY_PODCASTS);
                    updatePreference(item, audioSize);
                } break;
                case R.string.storage_detail_other: {
                    updatePreference(item, details.miscSize.get(userId));
                } break;
                case R.string.storage_detail_cached: {
                    updatePreference(item, details.cacheSize);
                } break;
                case 0: {
                    final long userSize = details.usersSize.get(userId);
                    updatePreference(item, userSize);
                } break;
            }
        }
    }

    private void updatePreference(StorageItemPreference pref, long size) {
        pref.setStorageSize(size, mVolume.getPath().getTotalSpace());
    }

    private boolean isProfileOf(UserInfo user, UserInfo profile) {
        return user.id == profile.id ||
                (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                && user.profileGroupId == profile.profileGroupId);
    }

    private static long totalValues(MeasurementDetails details, int userId, String... keys) {
        long total = 0;
        HashMap<String, Long> map = details.mediaSize.get(userId);
        if (map != null) {
            for (String key : keys) {
                if (map.containsKey(key)) {
                    total += map.get(key);
                }
            }
        } else {
            Log.w(TAG, "MeasurementDetails mediaSize array does not have key for user " + userId);
        }
        return total;
    }

    private final StorageEventListener mStorageListener = new StorageEventListener() {
        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            if (Objects.equals(mVolume.getId(), vol.getId())) {
                mVolume = vol;
                update();
            }
        }

        @Override
        public void onVolumeRecordChanged(VolumeRecord rec) {
            if (Objects.equals(mVolume.getFsUuid(), rec.getFsUuid())) {
                mVolume = mStorageManager.findVolumeById(mVolumeId);
                update();
            }
        }
    };

    /**
     * Dialog that allows editing of volume nickname.
     */
    public static class RenameFragment extends DialogFragment {
        public static void show(PrivateVolumeSettings parent, VolumeInfo vol) {
            if (!parent.isAdded()) return;

            final RenameFragment dialog = new RenameFragment();
            dialog.setTargetFragment(parent, 0);
            final Bundle args = new Bundle();
            args.putString(VolumeRecord.EXTRA_FS_UUID, vol.getFsUuid());
            dialog.setArguments(args);
            dialog.show(parent.getFragmentManager(), TAG_RENAME);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();
            final StorageManager storageManager = context.getSystemService(StorageManager.class);

            final String fsUuid = getArguments().getString(VolumeRecord.EXTRA_FS_UUID);
            final VolumeInfo vol = storageManager.findVolumeByUuid(fsUuid);
            final VolumeRecord rec = storageManager.findRecordByUuid(fsUuid);

            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            final LayoutInflater dialogInflater = LayoutInflater.from(builder.getContext());

            final View view = dialogInflater.inflate(R.layout.dialog_edittext, null, false);
            final EditText nickname = (EditText) view.findViewById(R.id.edittext);
            nickname.setText(rec.getNickname());

            builder.setTitle(R.string.storage_rename_title);
            builder.setView(view);

            builder.setPositiveButton(R.string.save,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // TODO: move to background thread
                            storageManager.setVolumeNickname(fsUuid,
                                    nickname.getText().toString());
                        }
                    });
            builder.setNegativeButton(R.string.cancel, null);

            return builder.create();
        }
    }

    public static class OtherInfoFragment extends DialogFragment {
        public static void show(Fragment parent, String title, VolumeInfo sharedVol) {
            if (!parent.isAdded()) return;

            final OtherInfoFragment dialog = new OtherInfoFragment();
            dialog.setTargetFragment(parent, 0);
            final Bundle args = new Bundle();
            args.putString(Intent.EXTRA_TITLE, title);
            args.putParcelable(Intent.EXTRA_INTENT, sharedVol.buildBrowseIntent());
            dialog.setArguments(args);
            dialog.show(parent.getFragmentManager(), TAG_OTHER_INFO);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();

            final String title = getArguments().getString(Intent.EXTRA_TITLE);
            final Intent intent = getArguments().getParcelable(Intent.EXTRA_INTENT);

            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setMessage(
                    TextUtils.expandTemplate(getText(R.string.storage_detail_dialog_other), title));

            builder.setPositiveButton(R.string.storage_menu_explore,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startActivity(intent);
                        }
                    });
            builder.setNegativeButton(android.R.string.cancel, null);

            return builder.create();
        }
    }

    public static class UserInfoFragment extends DialogFragment {
        public static void show(Fragment parent, CharSequence userLabel, CharSequence userSize) {
            if (!parent.isAdded()) return;

            final UserInfoFragment dialog = new UserInfoFragment();
            dialog.setTargetFragment(parent, 0);
            final Bundle args = new Bundle();
            args.putCharSequence(Intent.EXTRA_TITLE, userLabel);
            args.putCharSequence(Intent.EXTRA_SUBJECT, userSize);
            dialog.setArguments(args);
            dialog.show(parent.getFragmentManager(), TAG_USER_INFO);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();

            final CharSequence userLabel = getArguments().getCharSequence(Intent.EXTRA_TITLE);
            final CharSequence userSize = getArguments().getCharSequence(Intent.EXTRA_SUBJECT);

            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setMessage(TextUtils.expandTemplate(
                    getText(R.string.storage_detail_dialog_user), userLabel, userSize));

            builder.setPositiveButton(android.R.string.ok, null);

            return builder.create();
        }
    }

    /**
     * Dialog to request user confirmation before clearing all cache data.
     */
    public static class ConfirmClearCacheFragment extends DialogFragment {
        public static void show(Fragment parent) {
            if (!parent.isAdded()) return;

            final ConfirmClearCacheFragment dialog = new ConfirmClearCacheFragment();
            dialog.setTargetFragment(parent, 0);
            dialog.show(parent.getFragmentManager(), TAG_CONFIRM_CLEAR_CACHE);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();

            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.memory_clear_cache_title);
            builder.setMessage(getString(R.string.memory_clear_cache_message));

            builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final PrivateVolumeSettings target = (PrivateVolumeSettings) getTargetFragment();
                    final PackageManager pm = context.getPackageManager();
                    final UserManager um = context.getSystemService(UserManager.class);

                    for (int userId : um.getProfileIdsWithDisabled(context.getUserId())) {
                        final List<PackageInfo> infos = pm.getInstalledPackagesAsUser(0, userId);
                        final ClearCacheObserver observer = new ClearCacheObserver(
                                target, infos.size());
                        for (PackageInfo info : infos) {
                            pm.deleteApplicationCacheFilesAsUser(info.packageName, userId,
                                    observer);
                        }
                    }
                }
            });
            builder.setNegativeButton(android.R.string.cancel, null);

            return builder.create();
        }
    }

    private static class ClearCacheObserver extends IPackageDataObserver.Stub {
        private final PrivateVolumeSettings mTarget;
        private int mRemaining;

        public ClearCacheObserver(PrivateVolumeSettings target, int remaining) {
            mTarget = target;
            mRemaining = remaining;
        }

        @Override
        public void onRemoveCompleted(final String packageName, final boolean succeeded) {
            synchronized (this) {
                if (--mRemaining == 0) {
                    mTarget.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mTarget.update();
                        }
                    });
                }
            }
        }
    }
}
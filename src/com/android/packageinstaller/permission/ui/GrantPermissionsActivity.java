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

package com.android.packageinstaller.permission.ui;

import static android.content.pm.PackageManager.PERMISSION_DENIED;

import static com.android.packageinstaller.permission.ui.GrantPermissionsViewHandler.DENIED;
import static com.android.packageinstaller.permission.ui.GrantPermissionsViewHandler
        .DENIED_DO_NOT_ASK_AGAIN;
import static com.android.packageinstaller.permission.ui.GrantPermissionsViewHandler.GRANTED;

import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.nano.MetricsProto;
import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.ui.auto.GrantPermissionsAutoViewHandler;
import com.android.packageinstaller.permission.utils.ArrayUtils;
import com.android.packageinstaller.permission.utils.EventLogger;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;

import java.util.ArrayList;
import java.util.List;

public class GrantPermissionsActivity extends OverlayTouchActivity
        implements GrantPermissionsViewHandler.ResultListener {

    private static final String LOG_TAG = "GrantPermissionsActivity";

    private String[] mRequestedPermissions;

    private ArrayMap<String, GroupState> mRequestGrantPermissionGroups = new ArrayMap<>();

    private GrantPermissionsViewHandler mViewHandler;
    private AppPermissions mAppPermissions;

    boolean mResultSet;

    private PackageManager.OnPermissionsChangedListener mPermissionChangeListener;
    private PackageMonitor mPackageMonitor;

    private String mCallingPackage;

    private int getPermissionPolicy() {
        DevicePolicyManager devicePolicyManager = getSystemService(DevicePolicyManager.class);
        return devicePolicyManager.getPermissionPolicy(null);
    }

    /**
     * Try to add a single permission that is requested to be granted.
     *
     * <p>This does <u>not</u> expand the permissions into the {@link #computeAffectedPermissions
     * affected permissions}.
     *
     * @param group The group the permission belongs to (might be a background permission group)
     * @param permission The permission to add
     * @param isFirstInstance Is this the first time the groupStates get created
     */
    private void addRequestedPermissions(AppPermissionGroup group, String permission,
            boolean isFirstInstance) {
        if (!group.isGrantingAllowed()) {
            // Skip showing groups that we know cannot be granted.
            return;
        }

        // We allow the user to choose only non-fixed permissions. A permission
        // is fixed either by device policy or the user denying with prejudice.
        if (!group.isUserFixed() && !group.isPolicyFixed()) {
            String groupKey = group.getName();

            GroupState state = mRequestGrantPermissionGroups.get(groupKey);
            if (state == null) {
                state = new GroupState(group);
                mRequestGrantPermissionGroups.put(groupKey, state);
            }
            state.affectedPermissions = ArrayUtils.appendString(
                    state.affectedPermissions, permission);

            boolean skipGroup = false;
            switch (getPermissionPolicy()) {
                case DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT: {
                    if (!group.areRuntimePermissionsGranted()) {
                        group.grantRuntimePermissions(false, new String[]{permission});
                    }
                    state.mState = GroupState.STATE_ALLOWED;
                    group.setPolicyFixed();
                    skipGroup = true;
                } break;

                case DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY: {
                    if (group.areRuntimePermissionsGranted()) {
                        group.revokeRuntimePermissions(false, new String[]{permission});
                    }
                    state.mState = GroupState.STATE_DENIED;
                    group.setPolicyFixed();
                    skipGroup = true;
                } break;

                default: {
                    if (group.areRuntimePermissionsGranted()) {
                        group.grantRuntimePermissions(false, new String[]{permission});
                        state.mState = GroupState.STATE_ALLOWED;
                        skipGroup = true;
                    }
                } break;
            }

            if (skipGroup && isFirstInstance) {
                // Only allow to skip groups when this is the first time the dialog was created.
                // Otherwise the number of groups changes between instances of the dialog.
                state.mState = GroupState.STATE_SKIPPED;
            }
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Cache this as this can only read on onCreate, not later.
        mCallingPackage = getCallingPackage();

        mPackageMonitor = new PackageMonitor() {
            @Override
            public void onPackageRemoved(String packageName, int uid) {
                if (mCallingPackage.equals(packageName)) {
                    Log.w(LOG_TAG, mCallingPackage + " was uninstalled");

                    finish();
                }
            }
        };

        setFinishOnTouchOutside(false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setTitle(R.string.permission_request_title);

        if (DeviceUtils.isTelevision(this)) {
            mViewHandler = new com.android.packageinstaller.permission.ui.television
                    .GrantPermissionsViewHandlerImpl(this,
                    mCallingPackage).setResultListener(this);
        } else if (DeviceUtils.isWear(this)) {
            mViewHandler = new GrantPermissionsWatchViewHandler(this).setResultListener(this);
        } else if (DeviceUtils.isAuto(this)) {
            mViewHandler = new GrantPermissionsAutoViewHandler(this, mCallingPackage)
                    .setResultListener(this);
        } else {
            mViewHandler = new com.android.packageinstaller.permission.ui.handheld
                    .GrantPermissionsViewHandlerImpl(this, mCallingPackage)
                    .setResultListener(this);
        }

        mRequestedPermissions = getIntent().getStringArrayExtra(
                PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (mRequestedPermissions == null) {
            mRequestedPermissions = new String[0];
        }

        final int requestedPermCount = mRequestedPermissions.length;

        if (requestedPermCount == 0) {
            setResultAndFinish();
            return;
        }

        try {
            mPermissionChangeListener = new PermissionChangeListener();
        } catch (NameNotFoundException e) {
            setResultAndFinish();
            return;
        }

        PackageInfo callingPackageInfo = getCallingPackageInfo();

        if (callingPackageInfo == null || callingPackageInfo.requestedPermissions == null
                || callingPackageInfo.requestedPermissions.length <= 0) {
            setResultAndFinish();
            return;
        }

        // Don't allow legacy apps to request runtime permissions.
        if (callingPackageInfo.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
            // Returning empty arrays means a cancellation.
            mRequestedPermissions = new String[0];
            setResultAndFinish();
            return;
        }

        mAppPermissions = new AppPermissions(this, callingPackageInfo, null, false,
                new Runnable() {
                    @Override
                    public void run() {
                        setResultAndFinish();
                    }
                });

        for (String requestedPermission : mRequestedPermissions) {
            AppPermissionGroup group = null;
            for (AppPermissionGroup nextGroup : mAppPermissions.getPermissionGroups()) {
                if (nextGroup.hasPermission(requestedPermission)) {
                    group = nextGroup;
                    break;
                }
            }
            if (group == null) {
                continue;
            }

            String[] affectedPermissions = computeAffectedPermissions(callingPackageInfo, group,
                        requestedPermission);

            int numAffectedPermissions = affectedPermissions.length;
            for (int i = 0; i < numAffectedPermissions; i++) {
                addRequestedPermissions(group, affectedPermissions[i], icicle == null);
            }
        }

        int numGroupStates = mRequestGrantPermissionGroups.size();
        for (int groupStateNum = 0; groupStateNum < numGroupStates; groupStateNum++) {
            GroupState groupState = mRequestGrantPermissionGroups.valueAt(groupStateNum);
            AppPermissionGroup group = groupState.mGroup;

            // Restore permission group state after lifecycle events
            if (icicle != null) {
                groupState.mState = icicle.getInt(
                        getInstanceStateKey(mRequestGrantPermissionGroups.keyAt(groupStateNum)),
                        groupState.mState);
            }
        }

        setContentView(mViewHandler.createView());

        Window window = getWindow();
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        mViewHandler.updateWindowAttributes(layoutParams);
        window.setAttributes(layoutParams);

        // Restore UI state after lifecycle events. This has to be before
        // showNextPermissionGroupGrantRequest is called. showNextPermissionGroupGrantRequest might
        // update the UI and the UI behaves differently for updates and initial creations.
        if (icicle != null) {
            mViewHandler.loadInstanceState(icicle);
        }

        if (!showNextPermissionGroupGrantRequest()) {
            setResultAndFinish();
        } else if (icicle == null) {
            int numRequestedPermissions = mRequestedPermissions.length;
            for (int permissionNum = 0; permissionNum < numRequestedPermissions; permissionNum++) {
                String permission = mRequestedPermissions[permissionNum];

                EventLogger.logPermission(
                        MetricsProto.MetricsEvent.ACTION_PERMISSION_REQUESTED, permission,
                        mAppPermissions.getPackageInfo().packageName);
            }
        }
    }

    /**
     * Update the {@link #mRequestedPermissions} if the system reports them as granted.
     *
     * <p>This also updates the {@link #mAppPermissions} state and switches to the next group grant
     * request if the current group becomes granted.
     */
    private void updateIfPermissionsWereGranted() {
        PackageManager pm = getPackageManager();

        boolean mightShowNextGroup = true;
        int numGroupStates = mRequestGrantPermissionGroups.size();
        for (int i = 0; i < numGroupStates; i++) {
            GroupState groupState = mRequestGrantPermissionGroups.valueAt(i);

            if (groupState == null || groupState.mState != GroupState.STATE_UNKNOWN) {
                // Group has already been approved / denied via the UI by the user
                continue;
            }

            boolean allAffectedPermissionsOfThisGroupAreGranted = true;

            if (groupState.affectedPermissions == null) {
                // It is not clear which permissions belong to this group, hence never skip this
                // view
                allAffectedPermissionsOfThisGroupAreGranted = false;
            } else {
                for (int permNum = 0; permNum < groupState.affectedPermissions.length;
                        permNum++) {
                    if (pm.checkPermission(groupState.affectedPermissions[permNum], mCallingPackage)
                            == PERMISSION_DENIED) {
                        allAffectedPermissionsOfThisGroupAreGranted = false;
                        break;
                    }
                }
            }

            if (allAffectedPermissionsOfThisGroupAreGranted) {
                groupState.mState = GroupState.STATE_ALLOWED;

                if (mightShowNextGroup) {
                    // The UI currently displays the first group with
                    // mState == STATE_UNKNOWN. So we are switching to next group until we
                    // could not allow a group that was still unknown
                    if (!showNextPermissionGroupGrantRequest()) {
                        setResultAndFinish();
                    }
                }
            } else {
                mightShowNextGroup = false;
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        PackageManager pm = getPackageManager();
        pm.addOnPermissionsChangeListener(mPermissionChangeListener);

        // get notified when the package is removed
        mPackageMonitor.register(this, getMainLooper(), false);

        // check if the package was removed while this activity was not started
        try {
            pm.getPackageInfo(mCallingPackage, 0);
        } catch (NameNotFoundException e) {
            Log.w(LOG_TAG, mCallingPackage + " was uninstalled while this activity was stopped", e);
            finish();
        }

        updateIfPermissionsWereGranted();
    }

    @Override
    protected void onStop() {
        super.onStop();

        mPackageMonitor.unregister();

        getPackageManager().removeOnPermissionsChangeListener(mPermissionChangeListener);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        View rootView = getWindow().getDecorView();
        if (rootView.getTop() != 0) {
            // We are animating the top view, need to compensate for that in motion events.
            ev.setLocation(ev.getX(), ev.getY() - rootView.getTop());
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Compose a key that stores the GroupState.mState in the instance state.
     *
     * @param permissionGroupName The permission group name
     *
     * @return A unique key to be used in the instance state
     */
    private static String getInstanceStateKey(String permissionGroupName) {
        return GrantPermissionsActivity.class.getName() + "_" + permissionGroupName;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        mViewHandler.saveInstanceState(outState);

        int numGroups = mRequestGrantPermissionGroups.size();
        for (int i = 0; i < numGroups; i++) {
            int state = mRequestGrantPermissionGroups.valueAt(i).mState;

            if (state != GroupState.STATE_UNKNOWN) {
                outState.putInt(getInstanceStateKey(mRequestGrantPermissionGroups.keyAt(i)), state);
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        // Restore permission group state after lifecycle events
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) {
            if (savedInstanceState != null) {
                groupState.mState = savedInstanceState.getInt(
                        getInstanceStateKey(groupState.mGroup.getName()), groupState.mState);
            }
        }

        mViewHandler.loadInstanceState(savedInstanceState);
        showNextPermissionGroupGrantRequest();
    }

    /**
     * @return the group state for the permission group with the {@code name}
     */
    private GroupState getGroupState(String name) {
        return mRequestGrantPermissionGroups.get(name);
    }

    private boolean showNextPermissionGroupGrantRequest() {
        int groupCount = 0;
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) {
            if (groupState.mState != GroupState.STATE_SKIPPED) {
                groupCount++;
            }
        }

        int currentIndex = 0;
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) {
            if (groupState.mState == GroupState.STATE_UNKNOWN) {
                CharSequence appLabel = mAppPermissions.getAppLabel();

                Spanned message = null;
                int requestMessageId = groupState.mGroup.getRequest();
                if (requestMessageId != 0) {
                    try {
                        message = Html.fromHtml(getPackageManager().getResourcesForApplication(
                                groupState.mGroup.getDeclaringPackage()).getString(requestMessageId,
                                appLabel), 0);
                    } catch (NameNotFoundException ignored) {
                    }
                }

                if (message == null) {
                    message = Html.fromHtml(getString(R.string.permission_warning_template,
                            appLabel, groupState.mGroup.getDescription()), 0);
                }

                // Set the permission message as the title so it can be announced.
                setTitle(message);

                // Set the new grant view
                // TODO: Use a real message for the action. We need group action APIs
                Resources resources;
                try {
                    resources = getPackageManager().getResourcesForApplication(
                            groupState.mGroup.getIconPkg());
                } catch (NameNotFoundException e) {
                    // Fallback to system.
                    resources = Resources.getSystem();
                }

                Icon icon;
                try {
                    icon = Icon.createWithResource(resources, groupState.mGroup.getIconResId());
                } catch (Resources.NotFoundException e) {
                    Log.e(LOG_TAG, "Cannot load icon for group" + groupState.mGroup.getName(), e);
                    icon = null;
                }

                mViewHandler.updateUi(groupState.mGroup.getName(), groupCount, currentIndex,
                        icon, message, groupState.mGroup.isUserSet());
                return true;
            }

            if (groupState.mState != GroupState.STATE_SKIPPED) {
                currentIndex++;
            }
        }

        return false;
    }

    @Override
    public void onPermissionGrantResult(String name,
            @GrantPermissionsViewHandler.Result int result) {
        GroupState groupState = getGroupState(name);

        switch (result) {
            case GRANTED :
                onPermissionGrantResultSingleState(groupState, true, false);
                break;
            case DENIED :
                onPermissionGrantResultSingleState(groupState, false, false);
                break;
            case DENIED_DO_NOT_ASK_AGAIN :
                onPermissionGrantResultSingleState(groupState, false, true);
                break;
        }

        if (!showNextPermissionGroupGrantRequest()) {
            setResultAndFinish();
        }
    }

    /**
     * Grants or revoked the affected permissions for a single {@link groupState}.
     *
     * @param groupState The group state with the permissions to grant/revoke
     * @param granted {@code true} if the permissions should be granted, {@code false} if they
     *        should be revoked
     * @param doNotAskAgain if the permissions should be revoked should be app be allowed to ask
     *        again for the same permissions?
     */
    private void onPermissionGrantResultSingleState(GroupState groupState, boolean granted,
            boolean doNotAskAgain) {
        if (groupState != null && groupState.mGroup != null) {
            if (granted) {
                groupState.mGroup.grantRuntimePermissions(doNotAskAgain,
                        groupState.affectedPermissions);
                groupState.mState = GroupState.STATE_ALLOWED;
            } else {
                groupState.mGroup.revokeRuntimePermissions(doNotAskAgain,
                        groupState.affectedPermissions);
                groupState.mState = GroupState.STATE_DENIED;

                int numRequestedPermissions = mRequestedPermissions.length;
                for (int i = 0; i < numRequestedPermissions; i++) {
                    String permission = mRequestedPermissions[i];

                    if (groupState.mGroup.hasPermission(permission)) {
                        EventLogger.logPermission(
                                MetricsProto.MetricsEvent.ACTION_PERMISSION_DENIED, permission,
                                mAppPermissions.getPackageInfo().packageName);
                    }
                }
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)  {
        // We do not allow backing out.
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)  {
        // We do not allow backing out.
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public void finish() {
        setResultIfNeeded(RESULT_CANCELED);
        super.finish();
    }

    private PackageInfo getCallingPackageInfo() {
        try {
            return getPackageManager().getPackageInfo(mCallingPackage,
                    PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            Log.i(LOG_TAG, "No package: " + mCallingPackage, e);
            return null;
        }
    }

    private void setResultIfNeeded(int resultCode) {
        if (!mResultSet) {
            mResultSet = true;
            logRequestedPermissionGroups();
            Intent result = new Intent(PackageManager.ACTION_REQUEST_PERMISSIONS);
            result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES, mRequestedPermissions);

            PackageManager pm = getPackageManager();
            int numRequestedPermissions = mRequestedPermissions.length;
            int[] grantResults = new int[numRequestedPermissions];
            for (int i = 0; i < numRequestedPermissions; i++) {
                grantResults[i] = pm.checkPermission(mRequestedPermissions[i], mCallingPackage);
            }

            result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS, grantResults);
            setResult(resultCode, result);
        }
    }

    private void setResultAndFinish() {
        setResultIfNeeded(RESULT_OK);
        finish();
    }

    private void logRequestedPermissionGroups() {
        if (mRequestGrantPermissionGroups.isEmpty()) {
            return;
        }

        final int groupCount = mRequestGrantPermissionGroups.size();
        List<AppPermissionGroup> groups = new ArrayList<>(groupCount);
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) {
            groups.add(groupState.mGroup);
        }

        SafetyNetLogger.logPermissionsRequested(mAppPermissions.getPackageInfo(), groups);
    }

    /**
     * Get the actually requested permissions when a permission is requested.
     *
     * <p>>In some cases requesting to grant a single permission requires the system to grant
     * additional permissions. E.g. before N-MR1 a single permission of a group caused the whole
     * group to be granted. Another case are permissions that are split into two. For apps that
     * target an SDK before the split, this method automatically adds the split off permission.
     *
     * @param callingPkg The package requesting the permission
     * @param group The group the permission belongs to
     * @param permission The requested permission
     *
     * @return The actually requested permissions
     */
    private static String[] computeAffectedPermissions(PackageInfo callingPkg,
            AppPermissionGroup group, String permission) {
        // For <= N_MR1 apps all permissions are affected.
        if (callingPkg.applicationInfo.targetSdkVersion <= Build.VERSION_CODES.N_MR1) {
            List<Permission> permissions = group.getPermissions();

            int numPermission = permissions.size();
            String[] permissionNames = new String[numPermission];
            for (int i = 0; i < numPermission; i++) {
                permissionNames[i] = permissions.get(i).getName();
            }

            return permissionNames;
        }

        // For N_MR1+ apps only the requested permission is affected with addition
        // to splits of this permission applicable to apps targeting N_MR1.
        String[] permissions = new String[] {permission};
        for (PackageParser.SplitPermissionInfo splitPerm : PackageParser.SPLIT_PERMISSIONS) {
            if (splitPerm.targetSdk <= Build.VERSION_CODES.N_MR1
                    || callingPkg.applicationInfo.targetSdkVersion >= splitPerm.targetSdk
                    || !permission.equals(splitPerm.rootPerm)) {
                continue;
            }
            for (int i = 0; i < splitPerm.newPerms.length; i++) {
                final String newPerm = splitPerm.newPerms[i];
                permissions = ArrayUtils.appendString(permissions, newPerm);
            }
        }

        return permissions;
    }

    private static final class GroupState {
        static final int STATE_UNKNOWN = 0;
        static final int STATE_ALLOWED = 1;
        static final int STATE_DENIED = 2;
        static final int STATE_SKIPPED = 3;

        final AppPermissionGroup mGroup;
        int mState = STATE_UNKNOWN;

        /** Permissions of this group that need to be granted, null == no permissions of group */
        String[] affectedPermissions;

        GroupState(AppPermissionGroup group) {
            mGroup = group;
        }
    }

    private class PermissionChangeListener implements PackageManager.OnPermissionsChangedListener {
        final int mCallingPackageUid;

        PermissionChangeListener() throws NameNotFoundException {
            mCallingPackageUid = getPackageManager().getPackageUid(mCallingPackage, 0);
        }

        @Override
        public void onPermissionsChanged(int uid) {
            if (uid == mCallingPackageUid) {
                updateIfPermissionsWereGranted();
            }
        }
    }
}

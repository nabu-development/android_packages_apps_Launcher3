/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep;

import static com.android.launcher3.LauncherAnimUtils.MIN_PROGRESS_TO_ALL_APPS;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.anim.Interpolators.DEACCEL;
import static com.android.launcher3.config.FeatureFlags.ENABLE_QUICKSTEP_LIVE_TILE;
import static com.android.launcher3.config.FeatureFlags.QUICKSTEP_SPRINGS;
import static com.android.quickstep.WindowTransformSwipeHandler.MAX_SWIPE_DURATION;
import static com.android.quickstep.WindowTransformSwipeHandler.MIN_OVERSHOOT_DURATION;

import android.animation.ValueAnimator;
import android.view.animation.Interpolator;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherStateManager;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.AllAppsTransitionController;
import com.android.launcher3.allapps.DiscoveryBounce;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.AnimatorSetBuilder;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.anim.Interpolators.OvershootParams;
import com.android.launcher3.uioverrides.PortraitStatesTouchController;
import com.android.launcher3.userevent.nano.LauncherLogProto.Action.Direction;
import com.android.launcher3.userevent.nano.LauncherLogProto.Action.Touch;
import com.android.launcher3.userevent.nano.LauncherLogProto.ContainerType;
import com.android.launcher3.util.FlingBlockCheck;
import com.android.quickstep.views.RecentsView;

/**
 * Utility class to handle long swipe from an app.
 * This assumes the presence of Launcher activity as long swipe is not supported on the
 * fallback activity.
 */
public class LongSwipeHelper {

    private static final float SWIPE_DURATION_MULTIPLIER =
            Math.min(1 / MIN_PROGRESS_TO_ALL_APPS, 1 / (1 - MIN_PROGRESS_TO_ALL_APPS));

    private final Launcher mLauncher;
    private final int mRunningTaskId;

    private float mMaxSwipeDistance = 1;
    private AnimatorPlaybackController mAnimator;
    private FlingBlockCheck mFlingBlockCheck = new FlingBlockCheck();

    LongSwipeHelper(Launcher launcher, int runningTaskId) {
        mLauncher = launcher;
        mRunningTaskId = runningTaskId;
        init();
    }

    private void init() {
        mFlingBlockCheck.blockFling();

        // Init animations
        AllAppsTransitionController controller = mLauncher.getAllAppsController();
        // TODO: Scale it down so that we can reach all-apps in screen space
        mMaxSwipeDistance = Math.max(1, controller.getProgress() * controller.getShiftRange());

        AnimatorSetBuilder builder = PortraitStatesTouchController.getOverviewToAllAppsAnimation();
        mAnimator = mLauncher.getStateManager().createAnimationToNewWorkspace(ALL_APPS, builder,
                Math.round(2 * mMaxSwipeDistance), null, LauncherStateManager.ANIM_ALL);
        mAnimator.dispatchOnStart();
    }

    public void onMove(float displacement) {
        mAnimator.setPlayFraction(displacement / mMaxSwipeDistance);
        mFlingBlockCheck.onEvent();
    }

    public void destroy() {
        // TODO: We can probably also show the task view

        mLauncher.getStateManager().goToState(OVERVIEW, false);
    }

    public void end(float velocity, boolean isFling, Runnable callback) {
        float velocityPxPerMs = velocity / 1000;
        long duration = MAX_SWIPE_DURATION;
        Interpolator interpolator = DEACCEL;

        final float currentFraction = mAnimator.getProgressFraction();
        final boolean toAllApps;
        float endProgress;

        boolean blockedFling = isFling && mFlingBlockCheck.isBlocked();
        if (blockedFling) {
            isFling = false;
        }

        if (!isFling) {
            toAllApps = currentFraction > MIN_PROGRESS_TO_ALL_APPS;
            endProgress = toAllApps ? 1 : 0;

            long expectedDuration = Math.abs(Math.round((endProgress - currentFraction)
                    * MAX_SWIPE_DURATION * SWIPE_DURATION_MULTIPLIER));
            duration = Math.min(MAX_SWIPE_DURATION, expectedDuration);

            if (blockedFling && !toAllApps && !QUICKSTEP_SPRINGS.get()) {
                Interpolators.OvershootParams overshoot = new OvershootParams(currentFraction,
                        currentFraction, endProgress, velocityPxPerMs, (int) mMaxSwipeDistance);
                duration = (overshoot.duration + duration);
                duration = Utilities.boundToRange(duration, MIN_OVERSHOOT_DURATION,
                        MAX_SWIPE_DURATION);
                interpolator = overshoot.interpolator;
                endProgress = overshoot.end;
            }
        } else {
            toAllApps = velocity < 0;
            endProgress = toAllApps ? 1 : 0;

            float minFlingVelocity = mLauncher.getResources()
                    .getDimension(R.dimen.quickstep_fling_min_velocity);
            if (Math.abs(velocity) > minFlingVelocity && mMaxSwipeDistance > 0) {
                float distanceToTravel = (endProgress - currentFraction) * mMaxSwipeDistance;

                // we want the page's snap velocity to approximately match the velocity at
                // which the user flings, so we scale the duration by a value near to the
                // derivative of the scroll interpolator at zero, ie. 2.
                long baseDuration = Math.round(Math.abs(distanceToTravel / velocityPxPerMs));
                duration = Math.min(MAX_SWIPE_DURATION, 2 * baseDuration);
            }
        }

        final boolean finalIsFling = isFling;
        mAnimator.setEndAction(() -> onSwipeAnimationComplete(toAllApps, finalIsFling, callback));

        ValueAnimator animator = mAnimator.getAnimationPlayer();
        animator.setDuration(duration).setInterpolator(interpolator);
        animator.setFloatValues(currentFraction, endProgress);

        if (QUICKSTEP_SPRINGS.get()) {
            mAnimator.dispatchOnStartWithVelocity(endProgress, velocityPxPerMs);
        } else {
            animator.start();
        }
    }

    private void onSwipeAnimationComplete(boolean toAllApps, boolean isFling, Runnable callback) {
        RecentsView rv = mLauncher.getOverviewPanel();
        if (!toAllApps) {
            rv.setIgnoreResetTask(mRunningTaskId);
        }

        mLauncher.getStateManager().goToState(toAllApps ? ALL_APPS : OVERVIEW, false);
        if (!toAllApps) {
            DiscoveryBounce.showForOverviewIfNeeded(mLauncher);
            rv.animateUpRunningTaskIconScale();
            rv.setSwipeDownShouldLaunchApp(true);
        }

        mLauncher.getUserEventDispatcher().logStateChangeAction(
                isFling ? Touch.FLING : Touch.SWIPE, Direction.UP,
                ContainerType.NAVBAR, ContainerType.APP,
                toAllApps ? ContainerType.ALLAPPS : ContainerType.TASKSWITCHER,
                0);

        callback.run();

        if (ENABLE_QUICKSTEP_LIVE_TILE.get() && toAllApps) {
            rv.finishRecentsAnimation(true, null);
        }
    }
}
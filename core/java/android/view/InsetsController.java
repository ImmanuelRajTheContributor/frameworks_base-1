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

package android.view;

import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.toPublicType;
import static android.view.WindowInsets.Type.all;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Property;
import android.util.SparseArray;
import android.view.InsetsSourceConsumer.ShowResult;
import android.view.InsetsState.InternalInsetsType;
import android.view.SurfaceControl.Transaction;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimationCallback.AnimationBounds;
import android.view.WindowInsetsAnimationCallback.InsetsAnimation;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Implements {@link WindowInsetsController} on the client.
 * @hide
 */
public class InsetsController implements WindowInsetsController, InsetsAnimationControlCallbacks {

    private static final int ANIMATION_DURATION_SHOW_MS = 275;
    private static final int ANIMATION_DURATION_HIDE_MS = 340;

    static final Interpolator INTERPOLATOR = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /**
     * Layout mode during insets animation: The views should be laid out as if the changing inset
     * types are fully shown. Before starting the animation, {@link View#onApplyWindowInsets} will
     * be called as if the changing insets types are shown, which will result in the views being
     * laid out as if the insets are fully shown.
     */
    static final int LAYOUT_INSETS_DURING_ANIMATION_SHOWN = 0;

    /**
     * Layout mode during insets animation: The views should be laid out as if the changing inset
     * types are fully hidden. Before starting the animation, {@link View#onApplyWindowInsets} will
     * be called as if the changing insets types are hidden, which will result in the views being
     * laid out as if the insets are fully hidden.
     */
    static final int LAYOUT_INSETS_DURING_ANIMATION_HIDDEN = 1;

    /**
     * Determines the behavior of how the views should be laid out during an insets animation that
     * is controlled by the application by calling {@link #controlWindowInsetsAnimation}.
     * <p>
     * When the animation is system-initiated, the layout mode is always chosen such that the
     * pre-animation layout will represent the opposite of the starting state, i.e. when insets
     * are appearing, {@link #LAYOUT_INSETS_DURING_ANIMATION_SHOWN} will be used. When insets
     * are disappearing, {@link #LAYOUT_INSETS_DURING_ANIMATION_HIDDEN} will be used.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {LAYOUT_INSETS_DURING_ANIMATION_SHOWN,
            LAYOUT_INSETS_DURING_ANIMATION_HIDDEN})
    @interface LayoutInsetsDuringAnimation {
    }

    /** Not running an animation. */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_NONE = -1;

    /** Running animation will show insets */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_SHOW = 0;

    /** Running animation will hide insets */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_HIDE = 1;

    /** Running animation is controlled by user via {@link #controlWindowInsetsAnimation} */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_USER = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {ANIMATION_TYPE_NONE, ANIMATION_TYPE_SHOW, ANIMATION_TYPE_HIDE,
            ANIMATION_TYPE_USER})
    @interface AnimationType {
    }

    /**
     * Translation animation evaluator.
     */
    private static TypeEvaluator<Insets> sEvaluator = (fraction, startValue, endValue) -> Insets.of(
            (int) (startValue.left + fraction * (endValue.left - startValue.left)),
            (int) (startValue.top + fraction * (endValue.top - startValue.top)),
            (int) (startValue.right + fraction * (endValue.right - startValue.right)),
            (int) (startValue.bottom + fraction * (endValue.bottom - startValue.bottom)));

    /**
     * Linear animation property
     */
    private static class InsetsProperty extends Property<WindowInsetsAnimationController, Insets> {
        InsetsProperty() {
            super(Insets.class, "Insets");
        }

        @Override
        public Insets get(WindowInsetsAnimationController object) {
            return object.getCurrentInsets();
        }
        @Override
        public void set(WindowInsetsAnimationController controller, Insets value) {
            controller.setInsetsAndAlpha(
                    value, 1f /* alpha */, (((DefaultAnimationControlListener)
                            ((InsetsAnimationControlImpl) controller).getListener())
                                    .getRawProgress()));
        }
    }

    private class DefaultAnimationControlListener implements WindowInsetsAnimationControlListener {

        private WindowInsetsAnimationController mController;
        private ObjectAnimator mAnimator;
        private boolean mShow;

        DefaultAnimationControlListener(boolean show) {
            mShow = show;
        }

        @Override
        public void onReady(WindowInsetsAnimationController controller, int types) {
            mController = controller;

            mAnimator = ObjectAnimator.ofObject(
                    controller,
                    new InsetsProperty(),
                    sEvaluator,
                    mShow ? controller.getHiddenStateInsets() : controller.getShownStateInsets(),
                    mShow ? controller.getShownStateInsets() : controller.getHiddenStateInsets()
            );
            mAnimator.setDuration(getDurationMs());
            mAnimator.setInterpolator(INTERPOLATOR);
            mAnimator.addListener(new AnimatorListenerAdapter() {

                @Override
                public void onAnimationEnd(Animator animation) {
                    onAnimationFinish();
                }
            });
            mStartingAnimation = true;
            mAnimator.start();
            mStartingAnimation = false;
        }

        @Override
        public void onCancelled() {
            // Animator can be null when it is cancelled before onReady() completes.
            if (mAnimator != null) {
                mAnimator.cancel();
            }
        }

        private void onAnimationFinish() {
            mController.finish(mShow);
        }

        private float getRawProgress() {
            float fraction = (float) mAnimator.getCurrentPlayTime() / mAnimator.getDuration();
            return mShow ? fraction : 1 - fraction;
        }

        private long getDurationMs() {
            if (mAnimator != null) {
                return mAnimator.getDuration();
            }
            return mShow ? ANIMATION_DURATION_SHOW_MS : ANIMATION_DURATION_HIDE_MS;
        }
    }

    /**
     * Represents a running animation
     */
    private static class RunningAnimation {

        RunningAnimation(InsetsAnimationControlImpl control, int type) {
            this.control = control;
            this.type = type;
        }

        final InsetsAnimationControlImpl control;
        final @AnimationType int type;
    }

    private final String TAG = "InsetsControllerImpl";

    private final InsetsState mState = new InsetsState();
    private final InsetsState mTmpState = new InsetsState();

    private final Rect mFrame = new Rect();
    private final SparseArray<InsetsSourceConsumer> mSourceConsumers = new SparseArray<>();
    private final ViewRootImpl mViewRoot;

    private final SparseArray<InsetsSourceControl> mTmpControlArray = new SparseArray<>();
    private final ArrayList<RunningAnimation> mRunningAnimations = new ArrayList<>();
    private final ArrayList<InsetsAnimationControlImpl> mTmpFinishedControls = new ArrayList<>();
    private WindowInsets mLastInsets;

    private boolean mAnimCallbackScheduled;

    private final Runnable mAnimCallback;

    private final Rect mLastLegacyContentInsets = new Rect();
    private final Rect mLastLegacyStableInsets = new Rect();

    private int mPendingTypesToShow;

    private int mLastLegacySoftInputMode;
    private boolean mStartingAnimation;

    private SyncRtSurfaceTransactionApplier mApplier;

    public InsetsController(ViewRootImpl viewRoot) {
        mViewRoot = viewRoot;
        mAnimCallback = () -> {
            mAnimCallbackScheduled = false;
            if (mRunningAnimations.isEmpty()) {
                return;
            }
            if (mViewRoot.mView == null) {
                // The view has already detached from window.
                return;
            }

            mTmpFinishedControls.clear();
            InsetsState state = new InsetsState(mState, true /* copySources */);
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                InsetsAnimationControlImpl control = mRunningAnimations.get(i).control;
                if (control.applyChangeInsets(state)) {
                    mTmpFinishedControls.add(control);
                }
            }

            WindowInsets insets = state.calculateInsets(mFrame, mLastInsets.isRound(),
                    mLastInsets.shouldAlwaysConsumeSystemBars(), mLastInsets.getDisplayCutout(),
                    mLastLegacyContentInsets, mLastLegacyStableInsets, mLastLegacySoftInputMode,
                    null /* typeSideMap */);
            mViewRoot.mView.dispatchWindowInsetsAnimationProgress(insets);

            for (int i = mTmpFinishedControls.size() - 1; i >= 0; i--) {
                dispatchAnimationFinished(mTmpFinishedControls.get(i).getAnimation());
            }
        };
    }

    @VisibleForTesting
    public void onFrameChanged(Rect frame) {
        if (mFrame.equals(frame)) {
            return;
        }
        mViewRoot.notifyInsetsChanged();
        mFrame.set(frame);
    }

    @Override
    public InsetsState getState() {
        return mState;
    }

    boolean onStateChanged(InsetsState state) {
        if (mState.equals(state)) {
            return false;
        }
        mState.set(state);
        mTmpState.set(state, true /* copySources */);
        applyLocalVisibilityOverride();
        mViewRoot.notifyInsetsChanged();
        if (!mState.equals(mTmpState)) {
            sendStateToWindowManager();
        }
        return true;
    }

    /**
     * @see InsetsState#calculateInsets
     */
    @VisibleForTesting
    public WindowInsets calculateInsets(boolean isScreenRound,
            boolean alwaysConsumeSystemBars, DisplayCutout cutout, Rect legacyContentInsets,
            Rect legacyStableInsets, int legacySoftInputMode) {
        mLastLegacyContentInsets.set(legacyContentInsets);
        mLastLegacyStableInsets.set(legacyStableInsets);
        mLastLegacySoftInputMode = legacySoftInputMode;
        mLastInsets = mState.calculateInsets(mFrame, isScreenRound, alwaysConsumeSystemBars, cutout,
                legacyContentInsets, legacyStableInsets, legacySoftInputMode,
                null /* typeSideMap */);
        return mLastInsets;
    }

    /**
     * @see InsetsState#calculateVisibleInsets(Rect, Rect, int)
     */
    public Rect calculateVisibleInsets(Rect legacyVisibleInsets,
            @SoftInputModeFlags int softInputMode) {
        return mState.calculateVisibleInsets(mFrame, legacyVisibleInsets, softInputMode);
    }

    /**
     * Called when the server has dispatched us a new set of inset controls.
     */
    public void onControlsChanged(InsetsSourceControl[] activeControls) {
        if (activeControls != null) {
            for (InsetsSourceControl activeControl : activeControls) {
                if (activeControl != null) {
                    // TODO(b/122982984): Figure out why it can be null.
                    mTmpControlArray.put(activeControl.getType(), activeControl);
                }
            }
        }

        // Ensure to update all existing source consumers
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            final InsetsSourceControl control = mTmpControlArray.get(consumer.getType());

            // control may be null, but we still need to update the control to null if it got
            // revoked.
            consumer.setControl(control);
        }

        // Ensure to create source consumers if not available yet.
        for (int i = mTmpControlArray.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = mTmpControlArray.valueAt(i);
            getSourceConsumer(control.getType()).setControl(control);
        }
        mTmpControlArray.clear();
    }

    @Override
    public void show(@InsetsType int types) {
        show(types, false /* fromIme */);
    }

    void show(@InsetsType int types, boolean fromIme) {
        // TODO: Support a ResultReceiver for IME.
        // TODO(b/123718661): Make show() work for multi-session IME.
        int typesReady = 0;
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            @InternalInsetsType int internalType = internalTypes.valueAt(i);
            @AnimationType int animationType = getAnimationType(internalType);
            InsetsSourceConsumer consumer = getSourceConsumer(internalType);
            if (mState.getSource(internalType).isVisible() && animationType == ANIMATION_TYPE_NONE
                    || animationType == ANIMATION_TYPE_SHOW) {
                // no-op: already shown or animating in (because window visibility is
                // applied before starting animation).
                continue;
            }
            typesReady |= InsetsState.toPublicType(consumer.getType());
        }
        applyAnimation(typesReady, true /* show */, fromIme);
    }

    @Override
    public void hide(@InsetsType int types) {
        hide(types, false /* fromIme */);
    }

    void hide(@InsetsType int types, boolean fromIme) {
        int typesReady = 0;
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            @InternalInsetsType int internalType = internalTypes.valueAt(i);
            @AnimationType int animationType = getAnimationType(internalType);
            InsetsSourceConsumer consumer = getSourceConsumer(internalType);
            if (!mState.getSource(internalType).isVisible() && animationType == ANIMATION_TYPE_NONE
                    || animationType == ANIMATION_TYPE_HIDE) {
                // no-op: already hidden or animating out.
                continue;
            }
            typesReady |= InsetsState.toPublicType(consumer.getType());
        }
        applyAnimation(typesReady, false /* show */, fromIme /* fromIme */);
    }

    @Override
    public void controlWindowInsetsAnimation(@InsetsType int types, long durationMs,
            WindowInsetsAnimationControlListener listener) {
        controlWindowInsetsAnimation(types, listener, false /* fromIme */, durationMs,
                ANIMATION_TYPE_USER);
    }

    private void controlWindowInsetsAnimation(@InsetsType int types,
            WindowInsetsAnimationControlListener listener, boolean fromIme, long durationMs,
            @AnimationType int animationType) {
        // If the frame of our window doesn't span the entire display, the control API makes very
        // little sense, as we don't deal with negative insets. So just cancel immediately.
        if (!mState.getDisplayFrame().equals(mFrame)) {
            listener.onCancelled();
            return;
        }
        controlAnimationUnchecked(types, listener, mFrame, fromIme, durationMs, false /* fade */,
                animationType, getLayoutInsetsDuringAnimationMode(types));
    }

    private void controlAnimationUnchecked(@InsetsType int types,
            WindowInsetsAnimationControlListener listener, Rect frame, boolean fromIme,
            long durationMs, boolean fade, @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation) {
        if (types == 0) {
            // nothing to animate.
            return;
        }
        cancelExistingControllers(types);

        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        final SparseArray<InsetsSourceControl> controls = new SparseArray<>();

        Pair<Integer, Boolean> typesReadyPair = collectSourceControls(
                fromIme, internalTypes, controls, listener);
        int typesReady = typesReadyPair.first;
        boolean isReady = typesReadyPair.second;
        if (!isReady) {
            // IME isn't ready, all requested types would be shown once IME is ready.
            mPendingTypesToShow = typesReady;
            // TODO: listener for pending types.
            return;
        }

        // pending types from previous request.
        typesReady = collectPendingTypes(typesReady);

        if (typesReady == 0) {
            listener.onCancelled();
            return;
        }

        final InsetsAnimationControlImpl controller = new InsetsAnimationControlImpl(controls,
                frame, mState, listener, typesReady, this, durationMs, fade,
                layoutInsetsDuringAnimation);
        mRunningAnimations.add(new RunningAnimation(controller, animationType));
    }

    /**
     * @return Pair of (types ready to animate, is ready to animate).
     */
    private Pair<Integer, Boolean> collectSourceControls(boolean fromIme,
            ArraySet<Integer> internalTypes, SparseArray<InsetsSourceControl> controls,
            WindowInsetsAnimationControlListener listener) {
        int typesReady = 0;
        boolean isReady = true;
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = getSourceConsumer(internalTypes.valueAt(i));
            boolean setVisible = !consumer.isRequestedVisible();
            if (setVisible) {
                // Show request
                switch(consumer.requestShow(fromIme)) {
                    case ShowResult.SHOW_IMMEDIATELY:
                        typesReady |= InsetsState.toPublicType(consumer.getType());
                        break;
                    case ShowResult.SHOW_DELAYED:
                        isReady = false;
                        break;
                    case ShowResult.SHOW_FAILED:
                        // IME cannot be shown (since it didn't have focus), proceed
                        // with animation of other types.
                        if (mPendingTypesToShow != 0) {
                            // remove IME from pending because view no longer has focus.
                            mPendingTypesToShow &= ~InsetsState.toPublicType(ITYPE_IME);
                        }
                        break;
                }
            } else {
                // Hide request
                // TODO: Move notifyHidden() to beginning of the hide animation
                // (when visibility actually changes using hideDirectly()).
                if (!fromIme) {
                    consumer.notifyHidden();
                }
                typesReady |= InsetsState.toPublicType(consumer.getType());
            }
            final InsetsSourceControl control = consumer.getControl();
            if (control != null) {
                controls.put(consumer.getType(), control);
            }
        }
        return new Pair<>(typesReady, isReady);
    }

    private int collectPendingTypes(@InsetsType int typesReady) {
        typesReady |= mPendingTypesToShow;
        mPendingTypesToShow = 0;
        return typesReady;
    }

    private @LayoutInsetsDuringAnimation int getLayoutInsetsDuringAnimationMode(
            @InsetsType int types) {

        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);

        // Generally, we want to layout the opposite of the current state. This is to make animation
        // callbacks easy to use: The can capture the layout values and then treat that as end-state
        // during the animation.
        //
        // However, if controlling multiple sources, we want to treat it as shown if any of the
        // types is currently hidden.
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = mSourceConsumers.get(internalTypes.valueAt(i));
            if (consumer == null) {
                continue;
            }
            if (!consumer.isRequestedVisible()) {
                return LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
            }
        }
        return LAYOUT_INSETS_DURING_ANIMATION_HIDDEN;
    }

    private void cancelExistingControllers(@InsetsType int types) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlImpl control = mRunningAnimations.get(i).control;
            if ((control.getTypes() & types) != 0) {
                cancelAnimation(control, true /* invokeCallback */);
            }
        }
    }

    @VisibleForTesting
    @Override
    public void notifyFinished(InsetsAnimationControlImpl controller, boolean shown) {
        cancelAnimation(controller, false /* invokeCallback */);
        if (shown) {
            showDirectly(controller.getTypes());
        } else {
            hideDirectly(controller.getTypes());
        }
    }

    @Override
    public void applySurfaceParams(final SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
        if (mApplier == null) {
            if (mViewRoot.mView == null) {
                throw new IllegalStateException("View of the ViewRootImpl is not initiated.");
            }
            mApplier = new SyncRtSurfaceTransactionApplier(mViewRoot.mView);
        }
        mApplier.scheduleApply(params);
    }

    void notifyControlRevoked(InsetsSourceConsumer consumer) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlImpl control = mRunningAnimations.get(i).control;
            if ((control.getTypes() & toPublicType(consumer.getType())) != 0) {
                cancelAnimation(control, true /* invokeCallback */);
            }
        }
    }

    private void cancelAnimation(InsetsAnimationControlImpl control, boolean invokeCallback) {
        if (invokeCallback) {
            control.onCancelled();
        }
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            if (mRunningAnimations.get(i).control == control) {
                mRunningAnimations.remove(i);
                break;
            }
        }
    }

    private void applyLocalVisibilityOverride() {
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer controller = mSourceConsumers.valueAt(i);
            controller.applyLocalVisibilityOverride();
        }
    }

    @VisibleForTesting
    public @NonNull InsetsSourceConsumer getSourceConsumer(@InternalInsetsType int type) {
        InsetsSourceConsumer controller = mSourceConsumers.get(type);
        if (controller != null) {
            return controller;
        }
        controller = createConsumerOfType(type);
        mSourceConsumers.put(type, controller);
        return controller;
    }

    @VisibleForTesting
    public void notifyVisibilityChanged() {
        mViewRoot.notifyInsetsChanged();
        sendStateToWindowManager();
    }

    /**
     * @see ViewRootImpl#updateCompatSysUiVisibility(int, boolean, boolean)
     */
    public void updateCompatSysUiVisibility(@InternalInsetsType int type, boolean visible,
            boolean hasControl) {
        mViewRoot.updateCompatSysUiVisibility(type, visible, hasControl);
    }

    /**
     * Called when current window gains focus.
     */
    public void onWindowFocusGained() {
        getSourceConsumer(ITYPE_IME).onWindowFocusGained();
    }

    /**
     * Called when current window loses focus.
     */
    public void onWindowFocusLost() {
        getSourceConsumer(ITYPE_IME).onWindowFocusLost();
    }

    ViewRootImpl getViewRoot() {
        return mViewRoot;
    }

    /**
     * Used by {@link ImeInsetsSourceConsumer} when IME decides to be shown/hidden.
     * @hide
     */
    @VisibleForTesting
    public void applyImeVisibility(boolean setVisible) {
        if (setVisible) {
            show(Type.IME, true /* fromIme */);
        } else {
            hide(Type.IME);
        }
    }

    @VisibleForTesting
    public @AnimationType int getAnimationType(@InternalInsetsType int type) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlImpl control = mRunningAnimations.get(i).control;
            if (control.controlsInternalType(type)) {
                return mRunningAnimations.get(i).type;
            }
        }
        return ANIMATION_TYPE_NONE;
    }

    private InsetsSourceConsumer createConsumerOfType(int type) {
        if (type == ITYPE_IME) {
            return new ImeInsetsSourceConsumer(mState, Transaction::new, this);
        } else {
            return new InsetsSourceConsumer(type, mState, Transaction::new, this);
        }
    }

    /**
     * Sends the local visibility state back to window manager.
     */
    private void sendStateToWindowManager() {
        InsetsState tmpState = new InsetsState();
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if (consumer.getControl() != null) {
                tmpState.addSource(mState.getSource(consumer.getType()));
            }
        }

        // TODO: Put this on a dispatcher thread.
        try {
            mViewRoot.mWindowSession.insetsModified(mViewRoot.mWindow, tmpState);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call insetsModified", e);
        }
    }

    private void applyAnimation(@InsetsType final int types, boolean show, boolean fromIme) {
        if (types == 0) {
            // nothing to animate.
            return;
        }

        final DefaultAnimationControlListener listener = new DefaultAnimationControlListener(show);
        // Show/hide animations always need to be relative to the display frame, in order that shown
        // and hidden state insets are correct.
        controlAnimationUnchecked(
                types, listener, mState.getDisplayFrame(), fromIme, listener.getDurationMs(),
                true /* fade */, show ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE,
                show ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                        : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN);
    }

    private void hideDirectly(@InsetsType int types) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).hide();
        }
    }

    private void showDirectly(@InsetsType int types) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).show();
        }
    }

    /**
     * Cancel on-going animation to show/hide {@link InsetsType}.
     */
    @VisibleForTesting
    public void cancelExistingAnimation() {
        cancelExistingControllers(all());
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix); pw.println("InsetsController:");
        mState.dump(prefix + "  ", pw);
    }

    @VisibleForTesting
    @Override
    public void startAnimation(InsetsAnimationControlImpl controller,
            WindowInsetsAnimationControlListener listener, int types, InsetsAnimation animation,
            AnimationBounds bounds, int layoutDuringAnimation) {
        if (layoutDuringAnimation == LAYOUT_INSETS_DURING_ANIMATION_SHOWN) {
            showDirectly(types);
        } else {
            hideDirectly(types);
        }
        mViewRoot.mView.dispatchWindowInsetsAnimationPrepare(animation);
        mViewRoot.mView.getViewTreeObserver().addOnPreDrawListener(new OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mViewRoot.mView.getViewTreeObserver().removeOnPreDrawListener(this);
                mViewRoot.mView.dispatchWindowInsetsAnimationStart(animation, bounds);
                listener.onReady(controller, types);
                return true;
            }
        });
        mViewRoot.mView.invalidate();
    }

    @VisibleForTesting
    public void dispatchAnimationFinished(InsetsAnimation animation) {
        mViewRoot.mView.dispatchWindowInsetsAnimationFinish(animation);
    }

    @VisibleForTesting
    @Override
    public void scheduleApplyChangeInsets() {
        if (mStartingAnimation) {
            mAnimCallback.run();
            mAnimCallbackScheduled = false;
            return;
        }
        if (!mAnimCallbackScheduled) {
            mViewRoot.mChoreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION,
                    mAnimCallback, null /* token*/);
            mAnimCallbackScheduled = true;
        }
    }

    @Override
    public void setSystemBarsAppearance(@Appearance int appearance) {
        if (mViewRoot.mWindowAttributes.insetsFlags.appearance != appearance) {
            mViewRoot.mWindowAttributes.insetsFlags.appearance = appearance;
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }

    @Override
    public void setSystemBarsBehavior(@Behavior int behavior) {
        if (mViewRoot.mWindowAttributes.insetsFlags.behavior != behavior) {
            mViewRoot.mWindowAttributes.insetsFlags.behavior = behavior;
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }
}

/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.res.Resources;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.util.Log;

import com.android.systemui.R;

public class FODAnimation extends ImageView {

    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();

    private boolean mShowing = false;
    private Context mContext;
    private int mAnimationSize;
    private int mAnimationOffset;
    private AnimationDrawable recognizingAnim;
    private WindowManager mWindowManager;
    private boolean mIsKeyguard;
    private boolean mIsRecognizingAnimEnabled;

    private int mSelectedAnim;
    private String[] ANIMATION_STYLES_NAMES = {
        "fod_miui_normal_recognizing_anim",
        "fod_miui_aod_recognizing_anim",
        "fod_miui_aurora_recognizing_anim",
        "fod_miui_aurora_cas_recognizing_anim",
        "fod_miui_light_recognizing_anim",
        "fod_miui_pop_recognizing_anim",
        "fod_miui_pulse_recognizing_anim",
        "fod_miui_pulse_recognizing_white_anim",
        "fod_miui_rhythm_recognizing_anim",
        "fod_miui_star_cas_recognizing_anim",
        "fod_op_cosmos_recognizing_anim",
        "fod_op_energy_recognizing_anim",
        "fod_op_mclaren_recognizing_anim",
        "fod_op_ripple_recognizing_anim",
        "fod_op_scanning_recognizing_anim",
        "fod_op_stripe_recognizing_anim",
        "fod_op_wave_recognizing_anim",
        "fod_pureview_dna_recognizing_anim",
        "fod_pureview_future_recognizing_anim",
        "fod_pureview_halo_ring_recognizing_anim",
        "fod_pureview_molecular_recognizing_anim",
        "fod_rog_fusion_recognizing_anim",
        "fod_rog_pulsar_recognizing_anim",
        "fod_rog_supernova_recognizing_anim",
        "fod_recog_animation_shine",
        "fod_recog_animation_smoke",
        "fod_recog_animation_strings",
        "fod_recog_animation_quantum",
        "fod_recog_animation_redwave",
    };

    private final String mFodAnimationPackage;

    private static final boolean DEBUG = true;
    private static final String LOG_TAG = "FODAnimations";

    public FODAnimation(Context context, WindowManager windowManager, int mPositionX, int mPositionY) {
        super(context);

        mContext = context;
        mWindowManager = windowManager;
        mFodAnimationPackage = mContext.getResources().getString(com.android.internal.R.string.config_fodAnimationPackage);
        mAnimationSize = mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size);
        mAnimationOffset = mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_offset);
        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;

        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind FOD icon
        mAnimParams.flags =  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        mAnimParams.y = mPositionY - (mAnimationSize / 2) + mAnimationOffset;

        setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        mIsRecognizingAnimEnabled = Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.FOD_RECOGNIZING_ANIMATION, 0) != 0;

        update(mIsRecognizingAnimEnabled);
    }

    private void updateAnimationStyle(String drawableName) {
        if (DEBUG) Log.i(LOG_TAG, "Updating animation style to:" + drawableName);
        int resId = 0;
        try {
            PackageManager pm = mContext.getPackageManager();
            Resources mApkResources = pm.getResourcesForApplication(mFodAnimationPackage);
            resId = mApkResources.getIdentifier(drawableName, "drawable", mFodAnimationPackage);
            if (DEBUG) Log.i(LOG_TAG, "Got resource id: "+ resId +" from package" );
            setBackgroundDrawable(mApkResources.getDrawable(resId));
            recognizingAnim = (AnimationDrawable) getBackground();
        }
        catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void update(boolean isEnabled) {
        mSelectedAnim = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ANIM, 0);

        if (isEnabled)
            setAlpha(1.0f);
        else
            setAlpha(0.0f);

        updateAnimationStyle(ANIMATION_STYLES_NAMES[mSelectedAnim]);
    }

    public void updateParams(int mDreamingOffsetY) {
        mAnimParams.y = mDreamingOffsetY - (mAnimationSize / 2) + mAnimationOffset;
    }

    public void setAnimationKeyguard(boolean state) {
        mIsKeyguard = state;
    }

    public void showFODanimation() {
        if (mAnimParams != null && !mShowing && mIsKeyguard) {
            mShowing = true;
            if (getWindowToken() == null){
                mWindowManager.addView(this, mAnimParams);
                mWindowManager.updateViewLayout(this, mAnimParams);
            }
            if (recognizingAnim != null) {
                recognizingAnim.start();
            }
        }
    }

    public void hideFODanimation() {
        if (mShowing) {
            mShowing = false;
            if (recognizingAnim != null) {
                clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            if (getWindowToken() != null) {
                mWindowManager.removeView(this);
            }
        }
    }
}

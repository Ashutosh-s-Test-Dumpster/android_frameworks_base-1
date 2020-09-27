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

import android.app.WallpaperColors;
import android.app.WallpaperManager;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.net.Uri;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.palette.graphics.Palette;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.lang.IllegalStateException;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView implements ConfigurationListener {

    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mShouldBoostBrightness;
    private final Paint mPaintFingerprintBackground = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager.LayoutParams mPressedParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mDreamingOffsetX;
    private int mDreamingOffsetY;

    private int mColorBackground;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsPulsing;
    private boolean mIsKeyguard;
    private boolean mIsCircleShowing;
    private boolean mCanUnlockWithFp;
    private boolean mSupportsFodGesture;

    private boolean mDozeEnabled;
    private boolean mFodGestureEnable;
    private boolean mPressPending;
    private boolean mScreenTurnedOn;

    private Context mContext;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    private Handler mHandler;

    private final ImageView mPressedView;

    private LockPatternUtils mLockPatternUtils;

    private Timer mBurnInProtectionTimer;
    private int iconcolor = 0xFF3980FF;

    private FODAnimation mFODAnimation;
    private boolean mIsRecognizingAnimEnabled;

    private int mPressedIcon;
    private final int[] PRESSED_STYLES = {
        R.drawable.fod_icon_pressed_miui_cyan_light,
        R.drawable.fod_icon_pressed_miui_white_light,
        R.drawable.fod_icon_pressed_realme_green_shadow,
        R.drawable.fod_icon_pressed_vivo_cyan,
        R.drawable.fod_icon_pressed_vivo_cyan_shadow,
        R.drawable.fod_icon_pressed_vivo_cyan_shadow_et713,
        R.drawable.fod_icon_pressed_vivo_green,
        R.drawable.fod_icon_pressed_vivo_yellow_shadow
    };

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            if (mSupportsFodGesture && mFodGestureEnable && !mScreenTurnedOn) {
                if (mDozeEnabled) {
                    mHandler.post(() -> mContext.sendBroadcast(new Intent("com.android.systemui.doze.pulse")));
                } else {
                    mWakeLock.acquire(3000);
                    mHandler.post(() -> mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_GESTURE, FODCircleView.class.getSimpleName()));
                }
                mPressPending = true;
            } else {
                mHandler.post(() -> showCircle());
            }
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
            if (mSupportsFodGesture && mPressPending) {
                mPressPending = false;
            }
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateAlpha();

            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
                mDreamingOffsetX = 0;
                mDreamingOffsetY = 0;
                mHandler.post(() -> updatePosition());
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mIsKeyguard = showing;
            updatePosition();
            if (mFODAnimation != null) {
                mFODAnimation.setAnimationKeyguard(mIsKeyguard);
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            if (mIsKeyguard && mUpdateMonitor.isFingerprintDetectionRunning()) {
                if (isPinOrPattern(mUpdateMonitor.getCurrentUser()) || !isBouncer) {
                    show();
                } else {
                    hide();
                }
            } else {
                hide();
            }
        }

        @Override
        public void onPulsing(boolean pulsing) {
            super.onPulsing(pulsing);
            mIsPulsing = pulsing;
	        if (mIsPulsing) {
               mIsDreaming = false;
	        }
        }

        @Override
        public void onScreenTurnedOff() {
            mScreenTurnedOn = false;
            if (mSupportsFodGesture){
                hideCircle();
            }else{
                hide();
            }
        }


        @Override
        public void onScreenTurnedOn() {
            if (!mSupportsFodGesture && mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
            if (mSupportsFodGesture && mPressPending) {
                mHandler.post(() -> showCircle());
                mPressPending = false;
            }
            mScreenTurnedOn = true;
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            mCanUnlockWithFp = canUnlockWithFp();
            if (!mCanUnlockWithFp){
                hide();
            }
        }
        
        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            if (msgId == -1){ // Auth error
                hideCircle();
                mHandler.post(() -> mFODAnimation.hideFODanimation());
            }
        }
    };

    private boolean canUnlockWithFp() {
        int currentUser = ActivityManager.getCurrentUser();
        boolean biometrics = mUpdateMonitor.isUnlockingWithBiometricsPossible(currentUser);
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mUpdateMonitor.getStrongAuthTracker();
        int strongAuth = strongAuthTracker.getStrongAuthForUser(currentUser);
        if (biometrics && (!strongAuthTracker.hasUserAuthenticatedSinceBoot() && !isForceKeyguardOnRebootEnabled())) {
            return false;
        } else if (biometrics && isForceKeyguardOnRebootEnabled()) {
            return true;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_TIMEOUT) != 0) {
            return false;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW) != 0) {
            return false;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_LOCKOUT) != 0) {
            return false;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN) != 0) {
            return false;
        }
        return true;
    }

    private boolean isForceKeyguardOnRebootEnabled() {
        return (Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.FP_UNLOCK_KEYSTORE, 1) == 1);
    }

    private class FodGestureSettingsObserver extends ContentObserver {
        Context mContext;

        FodGestureSettingsObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        void registerListener() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(
                    Settings.Secure.DOZE_ENABLED),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(
                    Settings.System.FOD_GESTURE),
                    false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateSettings();
        }

        public void updateSettings() {
            mDozeEnabled = Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.DOZE_ENABLED, 1,
                    UserHandle.USER_CURRENT) == 1;
            mFodGestureEnable = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.FOD_GESTURE, 1,
                    UserHandle.USER_CURRENT) == 1;
        }
    }

    private boolean mCutoutMasked;
    private int mStatusbarHeight;
    private FodGestureSettingsObserver mFodGestureSettingsObserver;

    public FODCircleView(Context context) {
        super(context);

        mContext = context;

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = context.getResources();

        mSupportsFodGesture = context.getResources().getBoolean(
            com.android.internal.R.bool.config_supportScreenOffFod);

        mColorBackground = res.getColor(R.color.config_fodColorBackground);
        mPaintFingerprintBackground.setColor(mColorBackground);
        mPaintFingerprintBackground.setAntiAlias(true);

        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                 FODCircleView.class.getSimpleName());

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mPressedParams.copyFrom(mParams);
        mPressedParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;

        mParams.setTitle("Fingerprint on display");
        mPressedParams.setTitle("Fingerprint on display.touched");

        mPressedView = new ImageView(context)  {
            @Override
            protected void onDraw(Canvas canvas) {
                if (mIsCircleShowing) {
                    setImageResource(PRESSED_STYLES[mPressedIcon]);
                }
                super.onDraw(canvas);
            }
        };

        mWindowManager.addView(this, mParams);

        updatePosition();
        hide();

        mLockPatternUtils = new LockPatternUtils(mContext);

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        mCanUnlockWithFp = canUnlockWithFp();

        mFODAnimation = new FODAnimation(context, mPositionX, mPositionY);

        if (mSupportsFodGesture){
            mFodGestureSettingsObserver = new FodGestureSettingsObserver(context, mHandler);
            mFodGestureSettingsObserver.registerListener();
        }

        updateCutoutFlags();

        Dependency.get(ConfigurationController.class).addCallback(this);

    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mIsCircleShowing) {
            setImageResource(PRESSED_STYLES[mPressedIcon]);
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            if (mIsRecognizingAnimEnabled && (!mIsDreaming || mIsPulsing)) {
                mFODAnimation.showFODanimation();
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            mFODAnimation.hideFODanimation();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            return true;
        }
        mFODAnimation.hideFODanimation();
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        setDim(true);
        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchPress();
        });

        setImageDrawable(null);
        invalidate();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        setFODIcon();
        if (mFODAnimation != null) {
            mFODAnimation.setFODAnim();
        }
        invalidate();

        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchRelease();
        });
        setDim(false);

        setKeepScreenOn(false);
    }

    private boolean useWallpaperColor() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ICON_WALLPAPER_COLOR, 0) != 0;
    }

    private int getFODIcon() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ICON, 0);
    }

    private void setFODIcon() {
        int fodicon = getFODIcon();

        mIsRecognizingAnimEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_RECOGNIZING_ANIMATION, 0) != 0;

        mPressedIcon = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_PRESSED_STATE, 0);

        if (fodicon == 0) {
            this.setImageResource(R.drawable.fod_icon_default_0);
        } else if (fodicon == 1) {
            this.setImageResource(R.drawable.fod_icon_default_1);
        } else if (fodicon == 2) {
            this.setImageResource(R.drawable.fod_icon_default_2);
        } else if (fodicon == 3) {
            this.setImageResource(R.drawable.fod_icon_default_3);
        } else if (fodicon == 4) {
            this.setImageResource(R.drawable.fod_icon_default_4);
        } else if (fodicon == 5) {
            this.setImageResource(R.drawable.fod_icon_default_5);
        } else if (fodicon == 6) {
            this.setImageResource(R.drawable.fod_icon_arc_reactor);
        } else if (fodicon == 7) {
            this.setImageResource(R.drawable.fod_icon_cpt_america_flat);
        } else if (fodicon == 8) {
            this.setImageResource(R.drawable.fod_icon_cpt_america_flat_gray);
        } else if (fodicon == 9) {
            this.setImageResource(R.drawable.fod_icon_dragon_black_flat);
        } else if (fodicon == 10) {
            this.setImageResource(R.drawable.fod_icon_future);
        } else if (fodicon == 11) {
            this.setImageResource(R.drawable.fod_icon_glow_circle);
        } else if (fodicon == 12) {
            this.setImageResource(R.drawable.fod_icon_neon_arc);
        } else if (fodicon == 13) {
            this.setImageResource(R.drawable.fod_icon_neon_arc_gray);
        } else if (fodicon == 14) {
            this.setImageResource(R.drawable.fod_icon_neon_circle_pink);
        } else if (fodicon == 15) {
            this.setImageResource(R.drawable.fod_icon_neon_triangle);
        } else if (fodicon == 16) {
            this.setImageResource(R.drawable.fod_icon_paint_splash_circle);
        } else if (fodicon == 17) {
            this.setImageResource(R.drawable.fod_icon_rainbow_horn);
        } else if (fodicon == 18) {
            this.setImageResource(R.drawable.fod_icon_shooky);
        } else if (fodicon == 19) {
            this.setImageResource(R.drawable.fod_icon_spiral_blue);
        } else if (fodicon == 20) {
            this.setImageResource(R.drawable.fod_icon_sun_metro);
        } else if (fodicon == 21) {
            this.setImageResource(0);
        } else if (fodicon == 22) {
            this.setImageResource(R.drawable.fod_icon_default_6);            
        }

        if (useWallpaperColor()) {
            try {
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(mContext);
                Drawable wallpaperDrawable = wallpaperManager.getDrawable();
                Bitmap bitmap = ((BitmapDrawable)wallpaperDrawable).getBitmap();
                if (bitmap != null) {
                    Palette p = Palette.from(bitmap).generate();
                    int wallColor = p.getDominantColor(iconcolor);
                    if (iconcolor != wallColor) {
                        iconcolor = wallColor;
                    }
                    this.setColorFilter(lighter(iconcolor, 3));
                }
            } catch (Exception e) {
                // Nothing to do
            }
        } else if (fodicon == 1) {
            this.setColorFilter(Color.parseColor("#878787"));
        } 
        else {
            this.setColorFilter(null);
        }
    }

    private static int lighter(int color, int factor) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);

        blue = blue * factor;
        green = green * factor;
        blue = blue * factor;

        blue = blue > 255 ? 255 : blue;
        green = green > 255 ? 255 : green;
        red = red > 255 ? 255 : red;

        return Color.argb(Color.alpha(color), red, green, blue);
    }

    public void show() {
        if (!mSupportsFodGesture && !mUpdateMonitor.isScreenOn()) {
            // Keyguard is shown just after screen turning off
            return;
        }

        if (mIsBouncer && !isPinOrPattern(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }

        if (!mCanUnlockWithFp){
            // Ignore when unlocking with fp is not possible
            return;
        }

        updatePosition();

        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchShow();
        });
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        setVisibility(View.GONE);
        hideCircle();
        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchHide();
        });
    }

    private void updateAlpha() {
        setAlpha(mIsDreaming ? 0.5f : 1.0f);
    }


    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int cutoutMaskedExtra = mCutoutMasked ? mStatusbarHeight : 0;

        int x, y;
        switch (rotation) {
            case Surface.ROTATION_0:
                x = mPositionX;
                y = mPositionY - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_90:
                x = mPositionY;
                y = mPositionX - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_180:
                x = mPositionX;
                y = size.y - mPositionY - mSize - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_270:
                x = size.x - mPositionY - mSize - mNavigationBarSize - cutoutMaskedExtra;
                y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }


        if (mIsKeyguard) {
            x = mPositionX;
            y = mPositionY - cutoutMaskedExtra;
        }

        mPressedParams.x = mParams.x = x;
        mPressedParams.y = mParams.y = y;

        if (mIsDreaming) {
            //mParams.x += mDreamingOffsetX;
            mParams.y += mDreamingOffsetY;
            mFODAnimation.updateParams(mParams.y);
        }

        mWindowManager.updateViewLayout(this, mParams);

        if (mPressedView.getParent() != null) {
            mWindowManager.updateViewLayout(mPressedView, mPressedParams);
        }
    }

    private void setDim(boolean dim) {
        if (dim) {
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 100);
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(curBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 1.0f;
            }

            mPressedParams.dimAmount = dimAmount / 255.0f;
            if (mPressedView.getParent() == null) {
                mWindowManager.addView(mPressedView, mPressedParams);
            } else {
                mWindowManager.updateViewLayout(mPressedView, mPressedParams);
            }
        } else {
            mPressedParams.screenBrightness = 0.0f;
            mPressedParams.dimAmount = 0.0f;
            if (mPressedView.getParent() != null) {
                mWindowManager.removeView(mPressedView);
            }
        }
    }

    private boolean isPinOrPattern(int userId) {
        int passwordQuality = mLockPatternUtils.getActivePasswordQuality(userId);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return true;
        }

        return false;
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;

            mDreamingOffsetX = (int) (now % (mDreamingMaxOffset * 4));
            if (mDreamingOffsetX > mDreamingMaxOffset * 2) {
                mDreamingOffsetX = mDreamingMaxOffset * 4 - mDreamingOffsetX;
            }

            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            if (mDreamingOffsetY > mDreamingMaxOffset * 2) {
                mDreamingOffsetY = mDreamingMaxOffset * 4 - mDreamingOffsetY;
            }

            mDreamingOffsetX -= mDreamingMaxOffset;
            mDreamingOffsetY -= mDreamingMaxOffset;
            mHandler.post(() -> updatePosition());
        }
    };

    @Override
    public void onOverlayChanged() {
        updateCutoutFlags();
    }

    private void updateCutoutFlags() {
        mStatusbarHeight = getContext().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_portrait);
        boolean cutoutMasked = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout);
        if (mCutoutMasked != cutoutMasked){
            mCutoutMasked = cutoutMasked;
            updatePosition();
        }
    }
}

class FODAnimation extends ImageView {

    private Context mContext;
    private int mAnimationPositionY;
    private LayoutInflater mInflater;
    private WindowManager mWindowManager;
    private boolean mShowing = false;
    private boolean mIsKeyguard;
    private AnimationDrawable recognizingAnim;
    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();

    public FODAnimation(Context context, int mPositionX, int mPositionY) {
        super(context);

        mContext = context;
        mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        mAnimParams.height = mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size);
        mAnimParams.width = mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size);

        mAnimationPositionY = (int) Math.round(mPositionY - (mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size) / 2));

        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind FOD icon
        mAnimParams.flags =  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        mAnimParams.y = mAnimationPositionY;

        this.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        setFODAnim();
    }

    protected void setFODAnim(){
        this.setBackgroundResource(getFODAnimResource());
        recognizingAnim = (AnimationDrawable) this.getBackground();
    }
    private int getFODAnim() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ANIM, 0);
    }

    private int getFODAnimResource() {
        switch (getFODAnim()) {
            case 1:
                return R.drawable.fod_miui_aod_recognizing_anim;
            case 2:
                return R.drawable.fod_miui_light_recognizing_anim;
            case 3:
                return R.drawable.fod_miui_pop_recognizing_anim;
            case 4:
                return R.drawable.fod_miui_pulse_recognizing_anim;
            case 5:
                return R.drawable.fod_miui_pulse_recognizing_anim_white;
            case 6:
                return R.drawable.fod_miui_rhythm_recognizing_anim;
            case 7:
                return R.drawable.fod_op_cosmos_recognizing_anim;
            case 8:
                return R.drawable.fod_op_mclaren_recognizing_anim;
            case 9:
                return R.drawable.fod_pureview_future_recognizing_anim;
            case 10:
                return R.drawable.fod_pureview_molecular_recognizing_anim;
            case 11:
                return R.drawable.fod_op_energy_recognizing_anim;
            case 12:
                return R.drawable.fod_blue_firework_recognizing_anim;
            case 13:
                return R.drawable.fod_pureview_halo_ring_recognizing_anim;
            case 14:
                return R.drawable.fod_pureview_dna_recognizing_anim;
            case 15:
                return R.drawable.fod_op_stripe_recognizing_anim;
            case 16:
                return R.drawable.fod_op_wave_recognizing_anim;
            case 17:
                return R.drawable.fod_op_ripple_recognizing_anim;
            case 18:
                return R.drawable.fod_coloros7_1_recognizing_anim;
            case 19:
                return R.drawable.fod_coloros7_2_recognizing_anim;
            case 20:
                return R.drawable.fod_miui_aurora_recognizing_anim;
            case 21:
                return R.drawable.fod_miui_whirlwind_recognizing_anim;
            case 22:
                return R.drawable.fod_miui_nebula_recognizing_anim;
            case 23:
                return R.drawable.fod_realmecloud_recognizing_anim;
            case 24:
                return R.drawable.fod_realmeripple_recognizing_anim;
            case 25:
                return R.drawable.fod_miui_neon_recognizing_anim;
            case 26:
                return R.drawable.fod_miui_star_cas_recognizing_anim;
            case 27:
                return R.drawable.fod_miui_aurora_cas_recognizing_anim;
            case 28:
                return R.drawable.fod_vivoendless_recognizing_anim;
            case 29:
                return R.drawable.fod_rogfusion_recognizing_anim;
            case 30:
                return R.drawable.fod_rogpulsar_recognizing_anim;
            case 31:
                return R.drawable.fod_rogsupernova_recognizing_anim;
            case 32:
                return R.drawable.fod_xiaomi_minimal_recognizing_anim;
            case 33:
                return R.drawable.fod_gxzw_mirage_recognizing_anim;
            case 34:
                return R.drawable.fod_zte_linewave_recognizing_anim;
            case 35:
                return R.drawable.fod_zte_polar_lights_recognizing_anim;
            case 36:
                return R.drawable.fod_oppo_elemental_recognizing_anim;

        }
        return R.drawable.fod_miui_normal_recognizing_anim;
    }

    public void updateParams(int mDreamingOffsetY) {
        mAnimationPositionY = (int) Math.round(mDreamingOffsetY - (mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size) / 2));
        mAnimParams.y = mAnimationPositionY;
    }

    public void setAnimationKeyguard(boolean state) {
        mIsKeyguard = state;
    }

    public void showFODanimation() {
        if (mAnimParams != null && !mShowing && mIsKeyguard) {
            mShowing = true;
            try {
                mWindowManager.addView(this, mAnimParams);
            } catch (IllegalStateException e) {
                // Do nothing - View already added
            }
            recognizingAnim.start();
        }
    }

    public void hideFODanimation() {
        if (mShowing) {
            mShowing = false;
            if (recognizingAnim != null) {
                this.clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            if (this.getWindowToken() != null) {
                mWindowManager.removeView(this);
            }
        }
    }
}

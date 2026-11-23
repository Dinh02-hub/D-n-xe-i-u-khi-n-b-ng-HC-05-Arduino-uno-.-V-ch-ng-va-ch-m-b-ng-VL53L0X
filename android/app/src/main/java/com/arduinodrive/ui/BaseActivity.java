package com.arduinodrive.ui;

import android.content.res.Configuration;
import android.content.Context;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.animation.ValueAnimator;
import android.provider.Settings;
import android.util.TypedValue;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.arduinodrive.R;
import com.arduinodrive.settings.AppSettings;

public abstract class BaseActivity extends AppCompatActivity {
    protected AppSettings settings;
    private boolean immersiveMode;
    private String appliedTheme;
    @Override protected void onCreate(Bundle state) {
        settings = new AppSettings(this);
        appliedTheme = settings.getTheme();
        settings.applyTheme();
        setTheme(activityTheme(appliedTheme));
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // Vẽ nền thanh điều hướng theo giao diện của ứng dụng.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getWindow().setNavigationBarContrastEnforced(false);
    }

    protected void applyInsets(boolean immersive) {
        immersiveMode = immersive;
        View root = findViewById(R.id.root);
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom);
            return insets;
        });
        updateSystemBars();
        ViewCompat.requestApplyInsets(root);
    }

    private void updateSystemBars() {
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        boolean light = useLightSystemBars();
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);
        if (immersiveMode) {
            bars.hide(WindowInsetsCompat.Type.systemBars());
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else bars.show(WindowInsetsCompat.Type.systemBars());
    }

    protected int activityTheme(String theme) {
        return "system".equals(theme) ? R.style.Theme_ArduinoDrive_System : R.style.Theme_ArduinoDrive;
    }

    protected boolean useLightSystemBars() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
    }

    @Override protected void onResume() {
        super.onResume();
        // Giao diện Sáng và Hệ thống có thể cùng uiMode nhưng dùng bảng màu khác nhau.
        if (!settings.getTheme().equals(appliedTheme)) { recreate(); return; }
        updateSystemBars();
    }

    public static int themeColor(Context context, int attribute) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true))
            throw new IllegalArgumentException("Missing theme color");
        return value.data;
    }
    protected int themeColor(int attribute) { return themeColor(this, attribute); }

    protected final boolean motionEnabled() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        return (manager == null || !manager.isLowRamDevice()) && systemAnimationsEnabled();
    }

    protected final boolean systemAnimationsEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? ValueAnimator.areAnimatorsEnabled()
                : Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    protected final Bundle screenTransition() {
        return motionEnabled()
                ? ActivityOptions.makeCustomAnimation(this, R.anim.drive_fade_in, R.anim.drive_hold).toBundle()
                : ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateSystemBars();
    }
}

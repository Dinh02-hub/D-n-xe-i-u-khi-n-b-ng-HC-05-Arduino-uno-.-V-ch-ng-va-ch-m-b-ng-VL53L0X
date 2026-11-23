package com.arduinodrive.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import com.arduinodrive.R;
import com.arduinodrive.control.ControlCommand;
import com.arduinodrive.settings.AppSettings;
import com.arduinodrive.transport.BluetoothTransport;
import com.arduinodrive.transport.TelemetryParser;
import com.google.android.material.slider.Slider;
import java.lang.ref.WeakReference;
import org.junit.Before;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.assertion.ViewAssertions.matches;

/** Dùng xác nhận mô phỏng để kiểm tra luồng cấu hình của giao diện. */
public class SettingsUiTest {
    @Rule public ActivityTestRule<SettingsActivity> rule = new ActivityTestRule<>(SettingsActivity.class, false, false);
    private BluetoothTransport session;
    private final int[] sliderIds = {R.id.distanceSlider, R.id.intervalSlider, R.id.zoneSlider, R.id.brakeSlider};
    private final int[] valueIds = {R.id.distanceValue, R.id.intervalValue, R.id.zoneValue, R.id.brakeValue};
    private void set(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = BluetoothTransport.class.getDeclaredField(name);
            field.setAccessible(true); field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private Integer[] pending() {
        try {
            java.lang.reflect.Field field = BluetoothTransport.class.getDeclaredField("pendingSafety");
            field.setAccessible(true); return (Integer[]) field.get(session);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void render() {
        try {
            java.lang.reflect.Method method = SettingsActivity.class.getDeclaredMethod("renderSafety");
            method.setAccessible(true); method.invoke(rule.getActivity());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void ack(int distance, int interval) {
        ack(distance, interval, 60, 30);
    }
    private void ack(int distance, int interval, int zone, int brake) {
        set(session, "safetySettings", new TelemetryParser.SafetySettings(distance, interval, zone, brake));
        set(session, "safetySettingsReceivedAt", SystemClock.elapsedRealtime());
        set(session, "safetySettingsVersion", session.getSafetySettingsVersion() + 1);
        render();
    }
    private String text(int id) { return ((TextView) rule.getActivity().findViewById(id)).getText().toString(); }
    private void ui(Runnable action) throws Throwable { rule.runOnUiThread(action); }
    private void touch(Slider slider, int action, float x) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, slider.getHeight() / 2f, 0);
        slider.dispatchTouchEvent(event); event.recycle();
    }
    private void scrollToSlider(int index) throws Exception {
        // Vuốt dọc để đưa Slider vào vùng nhìn thấy trên API 35.
        for (int attempt = 0; attempt < 4; attempt++) {
            try { onView(withId(sliderIds[index])).check(matches(isCompletelyDisplayed())); return; }
            catch (AssertionError notVisibleYet) { onView(withId(R.id.root)).perform(swipeUp()); }
        }
        onView(withId(sliderIds[index])).check(matches(isCompletelyDisplayed()));
    }
    @Before public void launch() throws Throwable {
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("drive_settings", 0).edit().clear().commit();
        session = new BluetoothTransport(new BluetoothTransport.Listener() {
            @Override public void onConnectionChanged(boolean connected, String error) { }
            @Override public void onCommandSent(ControlCommand command) { }
        });
        set(session, "connected", true);
        set(null, "activeSession", new WeakReference<>(session));
        rule.launchActivity(new Intent());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        ui(() -> ack(40, 60));
    }
    @After public void close() {
        session.close();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : new java.util.ArrayList<>(androidx.test.runner.lifecycle
                    .ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)))
                if (activity instanceof SettingsActivity) activity.finish();
        });
    }

    @Test public void defaultsRangesAndFirstLaunchLightAreCorrect() throws Throwable {
        ui(() -> {
            assertEquals("light", new AppSettings(rule.getActivity()).getTheme());
            assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode());
            assertEquals(0xff242424, rule.getActivity().getColor(R.color.accent));
            int[] min = {10,40,0,1}, max = {140,200,160,60}, defaults = {40,60,60,30};
            for (int i = 0; i < sliderIds.length; i++) {
                Slider slider = rule.getActivity().findViewById(sliderIds[i]);
                assertTrue(slider.isEnabled());
                assertEquals(min[i], slider.getValueFrom(), 0);
                assertEquals(max[i], slider.getValueTo(), 0);
                assertEquals(defaults[i], slider.getValue(), 0);
            }
            assertEquals("40 cm", text(R.id.distanceValue));
            assertEquals("60 ms", text(R.id.intervalValue));
            assertEquals("60 cm", text(R.id.zoneValue));
            assertEquals("30 ms", text(R.id.brakeValue));
        });
    }
    @Test public void slidersSendOnlyOnReleaseAndLabelsWaitForExactAck() throws Throwable {
        int[] targets = {10,40,0,1};
        int[] acknowledged = {40,60,60,30};
        for (int i = 0; i < sliderIds.length; i++) {
            final int index = i;
            scrollToSlider(i);
            ui(() -> {
                ack(acknowledged[0], acknowledged[1], acknowledged[2], acknowledged[3]);
                Slider slider = rule.getActivity().findViewById(sliderIds[index]);
                float targetX = targets[index] == slider.getValueFrom() ? 1 : slider.getWidth() - 1;
                String before = text(valueIds[index]);
                touch(slider, MotionEvent.ACTION_DOWN, slider.getWidth() / 2f);
                for (int step = 0; step < 10; step++) touch(slider, MotionEvent.ACTION_MOVE, targetX);
                assertNull(pending()[index]);
                touch(slider, MotionEvent.ACTION_UP, targetX);
                assertEquals(Integer.valueOf(targets[index]), pending()[index]);
                assertEquals(before, text(valueIds[index]));
                assertEquals(rule.getActivity().getString(R.string.settings_sending), text(R.id.safetySettingsStatus));
                ack(acknowledged[0], acknowledged[1], acknowledged[2], acknowledged[3]);
                assertEquals(before, text(valueIds[index]));
                acknowledged[index] = targets[index];
                ack(acknowledged[0], acknowledged[1], acknowledged[2], acknowledged[3]);
                assertEquals(rule.getActivity().getString(R.string.settings_confirmed), text(R.id.safetySettingsStatus));
            });
        }
    }
    @Test public void linkedLimitsAndLegacyFirmwareLeaveOnlySupportedAdjustmentsEnabled() throws Throwable {
        ui(() -> {
            ack(10, 60, 190, 30);
            Slider distance = rule.getActivity().findViewById(R.id.distanceSlider);
            Slider zone = rule.getActivity().findViewById(R.id.zoneSlider);
            Slider brake = rule.getActivity().findViewById(R.id.brakeSlider);
            assertFalse(distance.isEnabled());
            assertEquals(10, distance.getValue(), 0);
            assertEquals(190, zone.getValueTo(), 0);
            ack(150, 60, 50, 30);
            assertTrue(distance.isEnabled());
            assertEquals(150, distance.getValueTo(), 0);
            assertEquals(50, zone.getValueTo(), 0);
            ack(150, 60, 0, 0);
            assertTrue(distance.isEnabled());
            assertFalse(zone.isEnabled());
            assertFalse(brake.isEnabled());
            assertEquals("—", text(R.id.zoneValue));
            assertEquals("—", text(R.id.brakeValue));
        });
    }
    @Test public void silentOldFirmwareStaleDataAndDisconnectCannotClaimConfirmation() throws Throwable {
        ui(() -> {
            set(session, "safetySettings", null); render();
            assertFalse(rule.getActivity().findViewById(R.id.distanceSlider).isEnabled());
            assertEquals("—", text(R.id.distanceValue));
            ack(10, 40);
            set(session, "safetySettingsReceivedAt", SystemClock.elapsedRealtime() - 1100); render();
            assertFalse(rule.getActivity().findViewById(R.id.distanceSlider).isEnabled());
            assertEquals("—", text(R.id.distanceValue));
            ack(40, 60); session.close(); render();
            assertFalse(rule.getActivity().findViewById(R.id.intervalSlider).isEnabled());
            assertEquals(rule.getActivity().getString(R.string.safety_connect), text(R.id.safetySettingsStatus));
        });
    }
    @Test public void unacknowledgedRequestTimesOutInsteadOfShowingSuccess() throws Throwable {
        onView(withId(R.id.distanceSlider)).perform(scrollTo());
        ui(() -> {
            ack(40, 60);
            Slider slider = rule.getActivity().findViewById(R.id.distanceSlider);
            touch(slider, MotionEvent.ACTION_DOWN, 1); touch(slider, MotionEvent.ACTION_MOVE, 1);
            touch(slider, MotionEvent.ACTION_UP, 1);
        });
        SystemClock.sleep(2100);
        ui(() -> {
            ack(40, 60); // Khung mới vẫn xác nhận 40 thay vì giá trị 10 vừa gửi.
            assertEquals("40 cm", text(R.id.distanceValue));
            assertEquals(rule.getActivity().getString(R.string.settings_unconfirmed), text(R.id.safetySettingsStatus));
        });
    }
    @Test public void returningToOldValueReplacesPendingRequestAndRequiresANewFrame() throws Throwable {
        ui(() -> {
            ack(40, 60);
            Slider slider = rule.getActivity().findViewById(R.id.distanceSlider);
            try {
                java.lang.reflect.Method send = SettingsActivity.class.getDeclaredMethod("sendSafety", int.class);
                send.setAccessible(true);
                slider.setValue(100); send.invoke(rule.getActivity(), 0);
                slider.setValue(40); send.invoke(rule.getActivity(), 0);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            assertEquals(Integer.valueOf(40), pending()[0]);
            render(); // Giá trị 40 lưu sẵn chưa xác nhận yêu cầu mới.
            assertEquals(rule.getActivity().getString(R.string.settings_sending), text(R.id.safetySettingsStatus));
            ack(40, 60);
            assertEquals(rule.getActivity().getString(R.string.settings_confirmed), text(R.id.safetySettingsStatus));
        });
    }
    @Test public void settingsAreVisibleAndCapturedInLightAndDark() throws Throwable {
        for (String theme : new String[]{"light", "dark"}) {
            rule.finishActivity();
            InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getSharedPreferences("drive_settings", 0).edit().putString("theme", theme).commit();
            rule.launchActivity(new Intent());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            ui(() -> ack(40, 60));
            android.os.Handler stream = new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable periodic = new Runnable() {
                @Override public void run() { ack(150, 200, 50, 30); stream.postDelayed(this, 200); }
            };
            ui(() -> stream.post(periodic));
            try { for (int i = 0; i < sliderIds.length; i++) {
                scrollToSlider(i);
                final int index = i;
                ui(() -> {
                    // Gửi xác nhận định kỳ trong lúc chụp màn hình.
                    android.view.View view = rule.getActivity().findViewById(sliderIds[index]);
                    assertTrue(view.isEnabled());
                    android.graphics.Rect visible = new android.graphics.Rect();
                    assertTrue(view.getGlobalVisibleRect(visible));
                    assertEquals(view.getHeight(), visible.height());
                    assertEquals(view.getWidth(), visible.width());
                });
                if (i == 0 || i == sliderIds.length - 1) capture("settings-" + theme + "-" + i + ".png");
            } } finally { ui(() -> stream.removeCallbacksAndMessages(null)); }
        }
    }
    private void capture(String name) throws Exception {
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        java.io.File target = new java.io.File(rule.getActivity().getExternalFilesDir(null), name);
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(target)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }
    @Test public void defaultLightIgnoresDeviceDarkButExplicitSystemFollowsDevice() throws Throwable {
        rule.finishActivity();
        try {
            shell("cmd uimode night yes");
            rule.launchActivity(new Intent());
            ui(() -> assertEquals(Configuration.UI_MODE_NIGHT_NO, rule.getActivity().getResources()
                    .getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
            for (String mode : new String[]{"yes", "no"}) {
                rule.finishActivity();
                shell("cmd uimode night " + mode);
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getSharedPreferences("drive_settings", 0).edit().putString("theme", "system").commit();
                rule.launchActivity(new Intent());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                ui(() -> {
                    assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode());
                    assertEquals(mode.equals("yes") ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO,
                            rule.getActivity().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                    assertEquals(mode.equals("yes") ? 0xff8fc5f4 : 0xff244a70,
                            BaseActivity.themeColor(rule.getActivity(), R.attr.driveAccent));
                    assertEquals(mode.equals("yes") ? 0xff11253b : 0xffeaf0f6,
                            BaseActivity.themeColor(rule.getActivity(), R.attr.driveBackground));
                    int[] attributes = {R.attr.driveBackground, R.attr.driveSurface, R.attr.driveInk,
                            R.attr.driveMuted, R.attr.driveLine, R.attr.driveAccent, R.attr.driveOnAccent,
                            R.attr.driveAccentSoft, R.attr.driveDanger, R.attr.driveWarningSurface,
                            R.attr.driveWarningInk, R.attr.driveOnDanger, R.attr.driveHero,
                            R.attr.driveHeroMuted, R.attr.driveHeroText, R.attr.driveScrim};
                    int[] colors = {R.color.system_background, R.color.system_surface, R.color.system_ink, R.color.system_muted,
                            R.color.system_line, R.color.system_accent, R.color.system_on_accent, R.color.system_accent_soft,
                            R.color.system_danger, R.color.system_warning_surface, R.color.system_warning_ink, R.color.system_on_danger,
                            R.color.system_hero, R.color.system_hero_muted, R.color.system_hero_text, R.color.system_scrim};
                    for (int i = 0; i < attributes.length; i++) assertEquals(
                            rule.getActivity().getColor(colors[i]), BaseActivity.themeColor(rule.getActivity(), attributes[i]));
                });
            }
        } finally { rule.finishActivity(); shell("cmd uimode night auto"); }
    }
    @Test public void systemRestoresItsOwnPaletteWhenUiModeRemainsLight() throws Throwable {
        try {
            shell("cmd uimode night no");
            onView(withId(R.id.themeSystem)).perform(click());
            onView(withId(R.id.root)).check((view, error) -> {
                if (error != null) throw error;
                assertEquals(0xffeaf0f6, BaseActivity.themeColor(view.getContext(), R.attr.driveBackground));
                assertEquals(0xff244a70, BaseActivity.themeColor(view.getContext(), R.attr.driveAccent));
            });
            onView(withId(R.id.themeLight)).perform(click());
            onView(withId(R.id.root)).check((view, error) -> {
                if (error != null) throw error;
                assertEquals(0xfff5f5f5, BaseActivity.themeColor(view.getContext(), R.attr.driveBackground));
                assertEquals(0xff242424, BaseActivity.themeColor(view.getContext(), R.attr.driveAccent));
            });
        } finally { shell("cmd uimode night auto"); }
    }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) != -1) { }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}

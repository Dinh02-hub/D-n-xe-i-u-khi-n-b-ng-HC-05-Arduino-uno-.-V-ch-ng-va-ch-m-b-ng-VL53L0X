package com.arduinodrive.ui;

import android.content.Intent;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import com.arduinodrive.R;
import com.arduinodrive.transport.TelemetryParser;
import com.arduinodrive.transport.BluetoothTransport;
import com.google.android.material.slider.Slider;
import java.nio.charset.StandardCharsets;
import com.google.android.material.switchmaterial.SwitchMaterial;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControlUiTest {
    @Rule public ActivityTestRule<ControlActivity> rule = new ActivityTestRule<>(ControlActivity.class, false, false);
    @Before public void launch() {
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("drive_settings", 0).edit().clear().commit();
        rule.launchActivity(new Intent().putExtra("simulation", true));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        waitForFocus();
    }
    private void waitForFocus() {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!rule.getActivity().hasWindowFocus() && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(20);
        assertTrue(rule.getActivity().hasWindowFocus());
    }
    private void ui(Runnable action) throws Throwable { rule.runOnUiThread(action); }
    private String newest() { return ((TextView) rule.getActivity().findViewById(R.id.commandLog))
            .getText().toString().split("\n")[0]; }
    private void touch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    @Test public void holdPriorityFallbackStopAndFreshTouch() throws Throwable {
        ui(() -> {
            View forward = rule.getActivity().findViewById(R.id.forwardButton);
            View left = rule.getActivity().findViewById(R.id.leftButton);
            touch(forward, MotionEvent.ACTION_DOWN, 2, 2);
            assertTrue(newest().endsWith(" F"));
            touch(left, MotionEvent.ACTION_DOWN, 2, 2);
            assertTrue(newest().endsWith(" L"));
            touch(left, MotionEvent.ACTION_UP, 2, 2);
            assertTrue(newest().endsWith(" F"));
            touch(left, MotionEvent.ACTION_DOWN, 2, 2);
            touch(forward, MotionEvent.ACTION_UP, 2, 2);
            assertTrue(newest().endsWith(" L"));
            rule.getActivity().findViewById(R.id.stopButton).performClick();
            assertTrue(newest().endsWith(" S"));
            touch(left, MotionEvent.ACTION_MOVE, 2, 2);
            assertTrue(newest().endsWith(" S"));
            touch(left, MotionEvent.ACTION_UP, 2, 2);
        });
        SystemClock.sleep(400);
        ui(() -> assertTrue(newest().endsWith(" S")));
    }
    @Test public void cancelAndOutsideBoundsReleaseOnlyTheirOwnButton() throws Throwable {
        ui(() -> {
            View reverse = rule.getActivity().findViewById(R.id.reverseButton);
            View right = rule.getActivity().findViewById(R.id.rightButton);
            touch(right, MotionEvent.ACTION_DOWN, 2, 2);
            touch(reverse, MotionEvent.ACTION_DOWN, 2, 2);
            assertTrue(newest().endsWith(" B"));
            touch(reverse, MotionEvent.ACTION_CANCEL, 2, 2);
            assertTrue(newest().endsWith(" R"));
            touch(right, MotionEvent.ACTION_MOVE, -5, 2);
            assertTrue(newest().endsWith(" S"));
        });
    }
    @Test public void heartbeatContinuesWhileTurningAlone() throws Throwable {
        ui(() -> {
            touch(rule.getActivity().findViewById(R.id.leftButton), MotionEvent.ACTION_DOWN, 2, 2);
            assertTrue(newest().endsWith(" L"));
        });
        java.util.concurrent.atomic.AtomicBoolean threeLeftCommands = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 2000;
        do {
            ui(() -> {
                String[] lines = ((TextView) rule.getActivity().findViewById(R.id.commandLog))
                        .getText().toString().split("\n");
                assertTrue(lines[0].endsWith(" L"));
                threeLeftCommands.set(lines.length == 3
                        && lines[1].endsWith(" L") && lines[2].endsWith(" L"));
            });
            if (threeLeftCommands.get()) break;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("Expected two left-turn heartbeats while holding the button", threeLeftCommands.get());
        ui(() -> {
            touch(rule.getActivity().findViewById(R.id.leftButton), MotionEvent.ACTION_UP, 2, 2);
            assertTrue(newest().endsWith(" S"));
        });
    }
    @Test public void realMultiPointerDispatchPreservesOtherHeldButtons() throws Throwable {
        ui(() -> {
            View root = rule.getActivity().findViewById(R.id.root);
            View forward = rule.getActivity().findViewById(R.id.forwardButton);
            View left = rule.getActivity().findViewById(R.id.leftButton);
            View reverse = rule.getActivity().findViewById(R.id.reverseButton);
            float[] a = centerIn(root, forward), b = centerIn(root, left), c = centerIn(root, reverse);
            long down = SystemClock.uptimeMillis();
            send(root, down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[][]{a});
            assertTrue(newest().endsWith(" F"));
            send(root, down, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), new int[]{0, 1}, new float[][]{a, b});
            assertTrue(newest().endsWith(" L"));
            send(root, down, MotionEvent.ACTION_POINTER_UP, new int[]{0, 1}, new float[][]{a, b});
            assertTrue(newest().endsWith(" L"));
            send(root, down, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), new int[]{1, 2}, new float[][]{b, c});
            assertTrue(newest().endsWith(" B"));
            send(root, down, MotionEvent.ACTION_POINTER_UP | (1 << 8), new int[]{1, 2}, new float[][]{b, c});
            assertTrue(newest().endsWith(" L"));
            send(root, down, MotionEvent.ACTION_UP, new int[]{1}, new float[][]{b});
            assertTrue(newest().endsWith(" S"));
        });
    }
    @Test public void allFourControlsSupportConcurrentPointersAndFallback() throws Throwable {
        ui(() -> {
            View root = rule.getActivity().findViewById(R.id.root);
            int[] views = {R.id.forwardButton, R.id.leftButton, R.id.reverseButton, R.id.rightButton};
            float[][] p = new float[4][];
            for (int i = 0; i < 4; i++) p[i] = centerIn(root, rule.getActivity().findViewById(views[i]));
            long down = SystemClock.uptimeMillis();
            send(root, down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[][]{p[0]});
            send(root, down, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), new int[]{0, 1}, new float[][]{p[0], p[1]});
            send(root, down, MotionEvent.ACTION_POINTER_DOWN | (2 << 8), new int[]{0, 1, 2}, new float[][]{p[0], p[1], p[2]});
            send(root, down, MotionEvent.ACTION_POINTER_DOWN | (3 << 8), new int[]{0, 1, 2, 3}, p);
            assertTrue(newest().endsWith(" R"));
            send(root, down, MotionEvent.ACTION_POINTER_UP | (3 << 8), new int[]{0, 1, 2, 3}, p);
            assertTrue(newest().endsWith(" B"));
            send(root, down, MotionEvent.ACTION_POINTER_UP | (2 << 8), new int[]{0, 1, 2}, new float[][]{p[0], p[1], p[2]});
            assertTrue(newest().endsWith(" L"));
            send(root, down, MotionEvent.ACTION_POINTER_UP | (1 << 8), new int[]{0, 1}, new float[][]{p[0], p[1]});
            assertTrue(newest().endsWith(" F"));
            send(root, down, MotionEvent.ACTION_UP, new int[]{0}, new float[][]{p[0]});
            assertTrue(newest().endsWith(" S"));
        });
    }
    @Test public void simulationOffStopsAndBlocksControls() throws Throwable {
        ui(() -> {
            View forward = rule.getActivity().findViewById(R.id.forwardButton);
            touch(forward, MotionEvent.ACTION_DOWN, 2, 2);
            ((SwitchMaterial) rule.getActivity().findViewById(R.id.simulationSwitch)).setChecked(false);
            assertTrue(newest().endsWith(" S"));
            assertFalse(forward.isEnabled());
            assertFalse(rule.getActivity().findViewById(R.id.leftButton).isEnabled());
            ((SwitchMaterial) rule.getActivity().findViewById(R.id.simulationSwitch)).setChecked(true);
            assertTrue(forward.isEnabled());
            assertTrue(newest().endsWith(" S"));
        });
    }
    @Test public void debugPanelStopsAndLocksControls() throws Throwable {
        ui(() -> {
            View forward = rule.getActivity().findViewById(R.id.forwardButton);
            touch(forward, MotionEvent.ACTION_DOWN, 2, 2);
            rule.getActivity().findViewById(R.id.logButton).performClick();
            assertTrue(newest().endsWith(" S"));
            assertFalse(forward.isEnabled());
            assertFalse(rule.getActivity().findViewById(R.id.rightButton).isEnabled());
        });
        androidx.test.espresso.Espresso.pressBack();
        SystemClock.sleep(500);
        ui(() -> {
            assertTrue(rule.getActivity().findViewById(R.id.forwardButton).isEnabled());
            assertTrue(newest().endsWith(" S"));
            assertFalse(rule.getActivity().isFinishing());
        });
    }
    @Test public void leavingForSettingsStopsAndDoesNotResumeOnReturn() throws Throwable {
        ui(() -> {
            touch(rule.getActivity().findViewById(R.id.forwardButton), MotionEvent.ACTION_DOWN, 2, 2);
            rule.getActivity().findViewById(R.id.settingsButton).performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        waitForFocusedActivity(SettingsActivity.class);
        ui(() -> assertTrue(newest().endsWith(" S")));
        androidx.test.espresso.Espresso.pressBack();
        waitForFocusedActivity(ControlActivity.class);
        ui(() -> {
            assertTrue(newest().endsWith(" S"));
            assertTrue(rule.getActivity().findViewById(R.id.forwardButton).isEnabled());
        });
    }
    @Test public void settingsReusesTheExistingSessionWhileControlIsPaused() throws Throwable {
        java.util.concurrent.atomic.AtomicReference<BluetoothTransport> retained = new java.util.concurrent.atomic.AtomicReference<>();
        ui(() -> {
            BluetoothTransport session = settingsSession(); retained.set(session);
            try {
                java.lang.reflect.Field active = BluetoothTransport.class.getDeclaredField("activeSession");
                active.setAccessible(true); active.set(null, new java.lang.ref.WeakReference<>(session));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            rule.getActivity().findViewById(R.id.settingsButton).performClick();
        });
        waitForFocusedActivity(SettingsActivity.class);
        ui(() -> {
            assertSame(retained.get(), BluetoothTransport.getActiveSession());
            assertTrue(retained.get().isAvailable());
        });
        androidx.test.espresso.Espresso.pressBack();
        waitForFocusedActivity(ControlActivity.class);
        ui(() -> assertTrue(retained.get().isAvailable()));
    }
    @Test public void realSessionWithoutDeviceCannotPretendToControlCar() throws Throwable {
        rule.finishActivity();
        rule.launchActivity(new Intent().putExtra("simulation", false));
        waitForFocus();
        ui(() -> {
            assertFalse(rule.getActivity().findViewById(R.id.forwardButton).isEnabled());
            assertFalse(rule.getActivity().findViewById(R.id.leftButton).isEnabled());
            SwitchMaterial simulationSwitch = rule.getActivity().findViewById(R.id.simulationSwitch);
            assertFalse(simulationSwitch.isChecked());
            assertFalse(simulationSwitch.isEnabled());
            assertEquals(rule.getActivity().getString(R.string.select_device_first),
                    ((TextView) rule.getActivity().findViewById(R.id.controlStatus)).getText().toString());
        });
    }
    // Kiểm tra Activity bằng dữ liệu đã giải mã và kết nối mô phỏng.
    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field field = ControlActivity.class.getDeclaredField(name);
            field.setAccessible(true); field.set(rule.getActivity(), value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private Object field(Object object, String name) {
        try {
            java.lang.reflect.Field item = object.getClass().getDeclaredField(name);
            item.setAccessible(true); return item.get(object);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void confirmSettings(boolean enabled, int speed) {
        try {
            java.lang.reflect.Method method = ControlActivity.class.getDeclaredMethod("acceptSettings", boolean.class, int.class, long.class);
            method.setAccessible(true); method.invoke(rule.getActivity(), enabled, speed, SystemClock.elapsedRealtime());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void requestSpeed(int speed) {
        try {
            java.lang.reflect.Method method = ControlActivity.class.getDeclaredMethod("requestSpeed", int.class);
            method.setAccessible(true); method.invoke(rule.getActivity(), speed);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private BluetoothTransport settingsSession() {
        BluetoothTransport result = new BluetoothTransport(new BluetoothTransport.Listener() {
            @Override public void onConnectionChanged(boolean connected, String error) { }
            @Override public void onCommandSent(com.arduinodrive.control.ControlCommand command) { }
        });
        try {
            java.lang.reflect.Field connected = BluetoothTransport.class.getDeclaredField("connected");
            connected.setAccessible(true); connected.set(result, true);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        setField("simulation", false);
        setField("bluetooth", result);
        confirmSettings(true, 60);
        return result;
    }
    @Test public void avoidanceSwitchWaitsForArduinoAckAndModeChangeClearsHeldForward() throws Throwable {
        ui(() -> {
            BluetoothTransport session = settingsSession();
            SwitchMaterial toggle = rule.getActivity().findViewById(R.id.avoidanceSwitch);
            View forward = rule.getActivity().findViewById(R.id.forwardButton);
            touch(forward, MotionEvent.ACTION_DOWN, 2, 2);
            toggle.setChecked(false);
            assertTrue(toggle.isChecked()); // Giữ giá trị Arduino xác nhận lần gần nhất.
            assertFalse(toggle.isEnabled());
            assertFalse(forward.isPressed());
            assertEquals(Boolean.FALSE, field(session, "pendingAvoidance"));
            confirmSettings(false, 60);
            assertFalse(toggle.isChecked());
            assertTrue(toggle.isEnabled());
            sensorFrame("DIST:8.0\nOBSTACLE:1\nSTATE:BLOCKED\n");
            assertEquals(rule.getActivity().getString(R.string.avoidance_off), sensorText(R.id.frontStatus));
        });
    }
    @Test public void speedIsThrottledConfirmedAndCannotRestartAfterZero() throws Throwable {
        ui(() -> {
            settingsSession();
            touch(rule.getActivity().findViewById(R.id.forwardButton), MotionEvent.ACTION_DOWN, 2, 2);
            for (int speed = 1; speed <= 80; ++speed) requestSpeed(speed);
        });
        SystemClock.sleep(250);
        ui(() -> {
            assertEquals(Integer.valueOf(80), field(field(rule.getActivity(), "bluetooth"), "pendingSpeed"));
            assertEquals(rule.getActivity().getString(R.string.speed_value, 80), sensorText(R.id.speedValue));
            assertEquals(60, field(rule.getActivity(), "confirmedSpeed"));
            assertEquals(rule.getActivity().getString(R.string.speed_preview_status, 60), sensorText(R.id.settingsStatus));
            confirmSettings(true, 80);
            assertEquals(rule.getActivity().getString(R.string.speed_value, 80), sensorText(R.id.speedValue));
            requestSpeed(0);
            assertFalse(rule.getActivity().findViewById(R.id.forwardButton).isPressed());
        });
        SystemClock.sleep(180);
        ui(() -> {
            confirmSettings(true, 0);
            assertEquals('S', ((com.arduinodrive.control.DriveController) field(rule.getActivity(), "controller")).getActiveCommand());
            requestSpeed(60);
        });
        SystemClock.sleep(180);
        ui(() -> {
            confirmSettings(true, 60);
            assertEquals('S', ((com.arduinodrive.control.DriveController) field(rule.getActivity(), "controller")).getActiveCommand());
        });
    }
    @Test public void reconnectRequiresDefaultAckAndStaleSettingsBecomeUnknown() throws Throwable {
        ui(() -> {
            settingsSession(); confirmSettings(false, 35);
            setField("sessionDefaultsPending", true);
            setField("settingsReceivedAt", 0L);
            confirmSettings(false, 35); // CFG còn đệm thuộc phiên trước.
            assertFalse(rule.getActivity().findViewById(R.id.speedSlider).isEnabled());
            assertEquals(rule.getActivity().getString(R.string.speed_unknown), sensorText(R.id.speedValue));
            confirmSettings(true, 60);
            assertTrue(((SwitchMaterial) rule.getActivity().findViewById(R.id.avoidanceSwitch)).isChecked());
            assertEquals(60f, ((Slider) rule.getActivity().findViewById(R.id.speedSlider)).getValue(), 0);
        });
        SystemClock.sleep(1250);
        ui(() -> {
            assertFalse(rule.getActivity().findViewById(R.id.speedSlider).isEnabled());
            assertFalse(rule.getActivity().findViewById(R.id.avoidanceSwitch).isEnabled());
            assertEquals(rule.getActivity().getString(R.string.speed_unknown), sensorText(R.id.speedValue));
        });
    }
    @Test public void arcAndNumberPreviewTogetherAndBecomeUnavailableWithStaleSettings() throws Throwable {
        ui(() -> {
            settingsSession(); requestSpeed(100);
            assertEquals(100, field(rule.getActivity().findViewById(R.id.speedArc), "targetPercent"));
            assertEquals(60, field(rule.getActivity(), "confirmedSpeed"));
        });
        SystemClock.sleep(250);
        ui(() -> {
            assertEquals(100f, (Float) field(rule.getActivity().findViewById(R.id.speedArc), "shownPercent"), 0.01);
            assertEquals(rule.getActivity().getString(R.string.speed_value, 100), sensorText(R.id.speedValue));
            confirmSettings(true, 100);
        });
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 2000;
        do {
            SystemClock.sleep(50);
            ui(() -> expired.set(Boolean.FALSE.equals(field(rule.getActivity().findViewById(R.id.speedArc), "available"))));
        } while (!expired.get() && SystemClock.uptimeMillis() < deadline);
        assertTrue("Stale data must expire on a subsequent 175 ms UI tick", expired.get());
    }
    @Test public void unconfirmedGaugePreviewReturnsToTheArduinoValueAfterTimeout() throws Throwable {
        ui(() -> { settingsSession(); requestSpeed(90); });
        for (int i = 0; i < 14; i++) {
            SystemClock.sleep(175);
            ui(() -> confirmSettings(true, 60)); // Telemetry mới vẫn chưa xác nhận mức 90.
        }
        SystemClock.sleep(250);
        ui(() -> {
            assertEquals(60, field(rule.getActivity().findViewById(R.id.speedArc), "targetPercent"));
            assertEquals(rule.getActivity().getString(R.string.speed_value, 60), sensorText(R.id.speedValue));
            assertEquals(rule.getActivity().getString(R.string.settings_unconfirmed), sensorText(R.id.settingsStatus));
        });
    }
    private void renderSensor() {
        try {
            java.lang.reflect.Method method = ControlActivity.class.getDeclaredMethod("renderTelemetry");
            method.setAccessible(true); method.invoke(rule.getActivity());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void sensorFrame(String frame) {
        setField("simulation", false);
        TelemetryParser parser = new TelemetryParser(snapshot -> {
            setField("frontTelemetry", snapshot);
            setField("telemetryReceivedAt", SystemClock.elapsedRealtime());
            renderSensor();
        });
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        parser.accept(data, data.length);
    }
    private String sensorText(int id) {
        return ((TextView) rule.getActivity().findViewById(id)).getText().toString();
    }
    @Test public void sensorShowsBlockedThenClearWithoutChangingHeldSteering() throws Throwable {
        ui(() -> {
            View left = rule.getActivity().findViewById(R.id.leftButton);
            touch(left, MotionEvent.ACTION_DOWN, 2, 2);
            sensorFrame("DIST:12.5\nOBSTACLE:1\nSTATE:BLOCKED\n");
            assertEquals(rule.getActivity().getString(R.string.obstacle_blocked), sensorText(R.id.frontStatus));
            sensorFrame("DIST:15.0\nOBSTACLE:0\nSTATE:READY\n");
            assertEquals(rule.getActivity().getString(R.string.sensor_ready), sensorText(R.id.frontStatus));
            assertTrue(left.isPressed());
            touch(left, MotionEvent.ACTION_UP, 2, 2);
        });
    }
    @Test public void staleSensorDataExpiresOnTheActivityTick() throws Throwable {
        ui(() -> sensorFrame("DIST:20.0\nOBSTACLE:0\nSTATE:READY\n"));
        SystemClock.sleep(1250);
        ui(() -> {
            assertEquals(rule.getActivity().getString(R.string.front_distance_unknown), sensorText(R.id.frontDistance));
            assertEquals(rule.getActivity().getString(R.string.sensor_no_data), sensorText(R.id.frontStatus));
        });
    }
    @Test public void invalidSensorAndDisconnectedSessionNeverKeepOldDistance() throws Throwable {
        ui(() -> {
            sensorFrame("DIST:20.0\nOBSTACLE:0\nSTATE:READY\n");
            sensorFrame("DIST:-1\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n");
            View warning = rule.getActivity().findViewById(R.id.sensorWarning);
            assertEquals("--", sensorText(R.id.frontDistance));
            assertEquals(View.VISIBLE, warning.getVisibility());
            assertEquals(rule.getActivity().getString(R.string.sensor_fault), sensorText(R.id.sensorWarning));
            confirmSettings(false, 60);
            assertEquals(View.VISIBLE, warning.getVisibility());
            confirmSettings(true, 60);
            sensorFrame("DIST:9.0\nOBSTACLE:1\nSTATE:BLOCKED\n");
            assertEquals(View.GONE, warning.getVisibility());
            sensorFrame("DIST:-1\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n");
            ((SwitchMaterial) rule.getActivity().findViewById(R.id.simulationSwitch)).setChecked(false);
            renderSensor();
            assertEquals(rule.getActivity().getString(R.string.front_distance_unknown), sensorText(R.id.frontDistance));
            assertEquals(rule.getActivity().getString(R.string.sensor_no_data), sensorText(R.id.frontStatus));
            assertEquals(View.GONE, warning.getVisibility());
        });
    }
    @Test public void unavailableSensorKeepsManualButtonsAndHeldDirectionAvailable() throws Throwable {
        ui(() -> {
            View forward = rule.getActivity().findViewById(R.id.forwardButton);
            touch(forward, MotionEvent.ACTION_DOWN, 2, 2);
            // Chấp nhận cờ vật cản của firmware cũ và ưu tiên hiển thị lỗi cảm biến.
            sensorFrame("DIST:-1\nOBSTACLE:1\nSTATE:SENSOR_FAULT\n");
            assertEquals(View.VISIBLE, rule.getActivity().findViewById(R.id.sensorWarning).getVisibility());
            assertEquals("--", sensorText(R.id.frontDistance));
            assertEquals(BaseActivity.themeColor(rule.getActivity(), R.attr.driveMuted),
                    ((TextView) rule.getActivity().findViewById(R.id.frontStatus)).getCurrentTextColor());
            assertTrue(forward.isPressed());
            for (int id : new int[]{R.id.forwardButton, R.id.reverseButton, R.id.leftButton, R.id.rightButton})
                assertTrue(rule.getActivity().findViewById(id).isEnabled());
            sensorFrame("DIST:20.0\nOBSTACLE:0\nSTATE:READY\n");
            assertEquals(View.GONE, rule.getActivity().findViewById(R.id.sensorWarning).getVisibility());
            assertTrue(forward.isPressed());
            assertEquals(rule.getActivity().getString(R.string.sensor_ready), sensorText(R.id.frontStatus));
            rule.getActivity().findViewById(R.id.stopButton).performClick();
            assertFalse(forward.isPressed());
            assertEquals(rule.getActivity().getString(R.string.requested_motion,
                    rule.getActivity().getString(R.string.stopped)), sensorText(R.id.motionText));
        });
    }
    @Test public void stopRemainsAvailableDuringSensorFault() throws Throwable {
        ui(() -> {
            sensorFrame("DIST:-1\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n");
            assertEquals(rule.getActivity().getString(R.string.sensor_no_data), sensorText(R.id.frontStatus));
            assertEquals("--", sensorText(R.id.frontDistance));
            assertEquals(View.VISIBLE, rule.getActivity().findViewById(R.id.sensorWarning).getVisibility());
            View stop = rule.getActivity().findViewById(R.id.stopButton);
            assertTrue(stop.isEnabled());
            stop.performClick();
            assertEquals(rule.getActivity().getString(R.string.requested_motion,
                    rule.getActivity().getString(R.string.stopped)), sensorText(R.id.motionText));
        });
    }
    @Test public void faultBannerStaysAboveTheControlsUntilAValidMeasurement() throws Throwable {
        ui(() -> {
            sensorFrame("DIST:-1\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n");
            setField("telemetryReceivedAt", SystemClock.elapsedRealtime() - 1250);
            renderSensor();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        ui(() -> {
            TextView warning = rule.getActivity().findViewById(R.id.sensorWarning);
            View forward = rule.getActivity().findViewById(R.id.forwardButton);
            android.graphics.Rect warningBounds = new android.graphics.Rect();
            android.graphics.Rect forwardBounds = new android.graphics.Rect();
            assertTrue(warning.getGlobalVisibleRect(warningBounds));
            assertTrue(forward.getGlobalVisibleRect(forwardBounds));
            assertTrue(warningBounds.bottom <= forwardBounds.top);
            assertEquals("--", sensorText(R.id.frontDistance));
            sensorFrame("DIST:80.2\nOBSTACLE:0\nSTATE:READY\n");
            assertEquals(View.GONE, warning.getVisibility());
        });
    }
    @Test public void sensorCardFitsControlsInBothThemes() throws Throwable {
        try { for (String theme : new String[]{"light", "dark", "system-light", "system-dark"}) {
            rule.finishActivity();
            setDeviceNight(theme.endsWith("dark") ? "yes" : "no");
            InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getSharedPreferences("drive_settings", 0).edit()
                    .putString("theme", theme.startsWith("system-") ? "system" : theme).commit();
            rule.launchActivity(new Intent().putExtra("simulation", true));
            waitForFocus();
            for (String state : new String[]{"READY", "BLOCKED", "SENSOR_FAULT"}) {
                ui(() -> {
                    String distance = state.equals("SENSOR_FAULT") ? "-1" : state.equals("READY") ? "20.0" : "9.0";
                    sensorFrame("DIST:" + distance + "\nOBSTACLE:" + (state.equals("READY") ? "0" : "1")
                            + "\nSTATE:" + state + "\n");
                    confirmSettings(true, 60);
                    int background = BaseActivity.themeColor(rule.getActivity(), R.attr.driveBackground);
                    int expected = theme.equals("light") ? 0xfff5f5f5 : theme.equals("dark")
                            ? 0xff1c1c1c : theme.equals("system-light") ? 0xffeaf0f6 : 0xff11253b;
                    assertEquals(expected, background);
                    for (int icon : new int[]{R.id.backButton, R.id.logButton, R.id.settingsButton, R.id.closeLogButton}) {
                        android.widget.ImageButton button = rule.getActivity().findViewById(icon);
                        assertNotNull(button.getImageTintList());
                        assertEquals(BaseActivity.themeColor(rule.getActivity(), R.attr.driveInk),
                                button.getImageTintList().getDefaultColor());
                    }
                    for (int attribute : new int[]{R.attr.driveInk, R.attr.driveMuted}) {
                        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                                BaseActivity.themeColor(rule.getActivity(), attribute), background) >= 4.5);
                    }
                    assertGaugeContainsLabel();
                    for (int id : new int[]{R.id.sensorCard, R.id.forwardButton, R.id.reverseButton,
                            R.id.leftButton, R.id.rightButton, R.id.stopButton,
                            R.id.speedSlider, R.id.speedArc, R.id.speedValue, R.id.settingsStatus}) {
                        View view = rule.getActivity().findViewById(id);
                        android.graphics.Rect visible = new android.graphics.Rect();
                        assertTrue(view.getGlobalVisibleRect(visible));
                        assertEquals(view.getHeight(), visible.height());
                        assertEquals(view.getWidth(), visible.width());
                    }
                });
                SystemClock.sleep(200);
                android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation().takeScreenshot();
                assertNotNull(screenshot);
                java.io.File target = new java.io.File(rule.getActivity().getExternalFilesDir(null),
                        "sensor-" + theme + "-" + state + ".png");
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(target)) {
                    assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
                } finally { screenshot.recycle(); }
            }
        } } finally { setDeviceNight("auto"); }
    }
    private void assertGaugeContainsLabel() {
        View gauge = rule.getActivity().findViewById(R.id.speedArc);
        View label = rule.getActivity().findViewById(R.id.speedValue);
        android.graphics.RectF circle = (android.graphics.RectF) field(gauge, "bounds");
        assertEquals(circle.width(), circle.height(), 0.1f);
        assertTrue(label.getBottom() <= circle.centerY());
        float radius = circle.width() / 2f;
        for (float x : new float[]{label.getLeft(), label.getRight()}) {
            float normalizedX = (x - circle.centerX()) / radius;
            float normalizedY = (label.getTop() - circle.centerY()) / radius;
            assertTrue("Number must fit inside semicircle", normalizedX * normalizedX + normalizedY * normalizedY < 1);
        }
    }
    private void setDeviceNight(String mode) throws Exception {
        try (android.os.ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand("cmd uimode night " + mode);
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) != -1) { }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void waitForFocusedActivity(Class<?> expected) throws Throwable {
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            ui(() -> {
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (expected.isInstance(activity) && activity.hasWindowFocus()) focused.set(true);
                }
            });
            if (focused.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Activity did not receive focus: " + expected.getSimpleName());
    }
    private float[] centerIn(View root, View view) {
        int[] origin = new int[2], position = new int[2];
        root.getLocationOnScreen(origin); view.getLocationOnScreen(position);
        return new float[]{position[0] - origin[0] + view.getWidth() / 2f,
                position[1] - origin[1] + view.getHeight() / 2f};
    }
    private void send(View root, long down, int action, int[] ids, float[][] points) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = points[i][0]; coords[i].y = points[i][1];
            coords[i].pressure = 1; coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                ids.length, properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        root.dispatchTouchEvent(event); event.recycle();
    }
}

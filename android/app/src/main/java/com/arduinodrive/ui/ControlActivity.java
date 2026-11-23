package com.arduinodrive.ui;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import com.arduinodrive.R;
import com.arduinodrive.control.CommandJournal;
import com.arduinodrive.control.ControlCommand;
import com.arduinodrive.control.DifferentialMixer;
import com.arduinodrive.control.DriveController;
import com.arduinodrive.control.MotorState;
import com.arduinodrive.simulation.SimulationTransport;
import com.arduinodrive.transport.BluetoothTransport;
import com.arduinodrive.transport.CommandTransport;
import com.arduinodrive.transport.TelemetryParser;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.slider.Slider;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ControlActivity extends BaseActivity {
    private static final String TAG = "DriveControl";
    private final String activityId = Integer.toHexString(System.identityHashCode(this));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<View, Integer> heldPointers = new HashMap<>();
    private DriveController controller;
    private CommandTransport transport;
    private SimulationTransport simulationTransport;
    private BluetoothTransport bluetooth;
    private boolean simulation;
    private String connectionMessage;
    private CommandJournal journal;
    private TextView motion, motors, timer, log, logCount;
    private TelemetryParser.Snapshot frontTelemetry;
    private long telemetryReceivedAt;
    private static final long TELEMETRY_STALE_MS = 1000;
    private Slider speedSlider;
    private SwitchMaterial avoidanceSwitch;
    private boolean applyingSettings, speedDragging, speedQueued, confirmedAvoidance = true;
    private boolean sessionDefaultsPending = true, settingsTimedOut;
    private int confirmedSpeed = 60, sliderTarget = 60;
    private Boolean requestedAvoidance;
    private Integer requestedSpeed;
    private long settingsReceivedAt, settingsRequestAt, lastSpeedSentAt;
    private static final long SPEED_SEND_INTERVAL_MS = 120;
    private final Runnable sendSpeed = () -> {
        speedQueued = false;
        if (!canConfigure()) { renderSettings(); return; }
        requestedSpeed = sliderTarget;
        settingsRequestAt = lastSpeedSentAt = SystemClock.elapsedRealtime();
        settingsTimedOut = false;
        if (simulation) acceptSettings(confirmedAvoidance, sliderTarget, settingsRequestAt);
        else bluetooth.setSpeed(sliderTarget);
        renderSettings();
    };
    private BottomSheetBehavior<View> debugSheet;
    private OnBackPressedCallback debugBack;
    private boolean debugOpen;
    private long sessionStart;
    private boolean resumed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            controller.heartbeat();
            renderTelemetry();
            renderSettings();
            long seconds = (SystemClock.elapsedRealtime() - sessionStart) / 1000;
            String elapsed = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
            if (!elapsed.contentEquals(timer.getText())) timer.setText(elapsed);
            handler.postDelayed(this, 175);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_control);
        applyInsets(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        motion = findViewById(R.id.motionText);
        motors = findViewById(R.id.motorValues);
        timer = findViewById(R.id.sessionTimer);
        log = findViewById(R.id.commandLog);
        logCount = findViewById(R.id.logCount);
        sessionStart = SystemClock.elapsedRealtime();
        journal = new CommandJournal(getFilesDir());
        simulation = getIntent().getBooleanExtra("simulation", false);
        Log.i(TAG, "activity=" + activityId + " onCreate simulation=" + simulation
                + " restored=" + (state != null) + " device=" + getIntent().getStringExtra("device_address"));
        if (simulation) {
            simulationTransport = new SimulationTransport(true);
            transport = simulationTransport;
        } else {
            connectionMessage = getString(R.string.connecting);
            bluetooth = new BluetoothTransport(new BluetoothTransport.Listener() {
                @Override public void onConnectionChanged(boolean connected, String error) {
                    Log.i(TAG, "activity=" + activityId + " connection_changed connected=" + connected + " error=" + error);
                    if (isDestroyed()) return;
                    frontTelemetry = null;
                    handler.removeCallbacks(sendSpeed);
                    speedQueued = false;
                    requestedAvoidance = null;
                    requestedSpeed = null;
                    confirmedAvoidance = true;
                    confirmedSpeed = sliderTarget = 60;
                    settingsReceivedAt = 0;
                    sessionDefaultsPending = true;
                    settingsTimedOut = false;
                    safetyStop(); // Đổi kết nối yêu cầu nhấn lại nút lái.
                    connectionMessage = connected ? getString(R.string.bluetooth_connected)
                            : getString(R.string.connection_lost, error);
                    updateAvailability();
                    render(ControlCommand.stop());
                    renderTelemetry();
                }
                @Override public void onCommandSent(ControlCommand command) {
                    if (!isDestroyed()) onCommand(command);
                }
                @Override public void onTelemetry(TelemetryParser.Snapshot snapshot, long receivedAt) {
                    if (isDestroyed()) return;
                    frontTelemetry = snapshot;
                    telemetryReceivedAt = receivedAt;
                    renderTelemetry();
                }
                @Override public void onSettings(boolean enabled, int speed, long receivedAt) {
                    if (!isDestroyed()) acceptSettings(enabled, speed, receivedAt);
                }
            });
            transport = bluetooth;
        }
        // Bluetooth báo sau khi ghi thành công, còn mô phỏng báo ngay.
        controller = new DriveController(transport, command -> {
            if (simulation) onCommand(command);
        });
        setupDebugPanel();
        setupConfiguration();
        bindHoldButton(findViewById(R.id.forwardButton), 'F');
        bindHoldButton(findViewById(R.id.reverseButton), 'B');
        bindHoldButton(findViewById(R.id.leftButton), 'L');
        bindHoldButton(findViewById(R.id.rightButton), 'R');
        View stop = findViewById(R.id.stopButton);
        stop.setOnClickListener(view -> safetyStop());
        stop.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) view.performClick();
            return true;
        });
        SwitchMaterial mode = findViewById(R.id.simulationSwitch);
        mode.setChecked(simulation);
        mode.setEnabled(simulation); // Chỉ cho đổi chế độ từ phiên mô phỏng.
        mode.setOnCheckedChangeListener((button, enabled) -> {
            safetyStop();
            simulationTransport.setEnabled(enabled);
            updateAvailability();
            render(ControlCommand.stop());
        });
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.settingsButton).setOnClickListener(view -> {
            safetyStop();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        updateAvailability();
        render(ControlCommand.stop());
        renderTelemetry();
        if (!simulation) connectBluetooth();
    }

    @SuppressLint("MissingPermission")
    private void connectBluetooth() {
        String address = getIntent().getStringExtra("device_address");
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (address == null || adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "activity=" + activityId + " connect_preflight_failed address=" + address
                        + " adapterPresent=" + (adapter != null));
                connectionMessage = getString(R.string.select_device_first);
                updateAvailability();
                return;
            }
            Log.i(TAG, "activity=" + activityId + " connect_dispatch address=" + address);
            bluetooth.connect(adapter, adapter.getRemoteDevice(address));
        } catch (SecurityException | IllegalArgumentException error) {
            Log.e(TAG, "activity=" + activityId + " connect_preflight_failure", error);
            connectionMessage = getString(R.string.connection_lost, error.getMessage());
            updateAvailability();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindHoldButton(View button, char direction) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!canDrive() || heldPointers.containsKey(view)) return true;
                    heldPointers.put(view, event.getPointerId(0));
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.setPressed(true);
                    controller.press(direction);
                    animatePress(view, true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    Integer pointer = heldPointers.get(view);
                    if (pointer != null && pointer >= 0) {
                        int index = event.findPointerIndex(pointer);
                        if (index < 0 || event.getX(index) < 0 || event.getX(index) > view.getWidth()
                                || event.getY(index) < 0 || event.getY(index) > view.getHeight())
                            releaseButton(view, direction);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    Integer owner = heldPointers.get(view);
                    if (owner != null && event.getPointerId(event.getActionIndex()) == owner)
                        releaseButton(view, direction);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseButton(view, direction);
                    return true;
                default: return true;
            }
        });
        button.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER
                    && keyCode != KeyEvent.KEYCODE_SPACE) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && canDrive() && !heldPointers.containsKey(view)) {
                heldPointers.put(view, -2);
                view.setPressed(true);
                controller.press(direction);
                animatePress(view, true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) releaseButton(view, direction);
            return true;
        });
    }

    private boolean canDrive() { return resumed && !debugOpen && controller.isAvailable(); }
    private void releaseButton(View view, char direction) {
        if (heldPointers.remove(view) == null) return;
        view.setPressed(false);
        controller.release(direction);
        animatePress(view, false);
    }
    private void safetyStop() {
        if (controller == null) return;
        for (View view : heldPointers.keySet()) {
            view.setPressed(false);
            animatePress(view, false);
        }
        heldPointers.clear();
        controller.stop();
        render(ControlCommand.stop());
    }
    private void updateAvailability() {
        boolean ready = transport.isAvailable() && resumed && !debugOpen;
        int[] ids = {R.id.forwardButton, R.id.reverseButton, R.id.leftButton, R.id.rightButton};
        for (int id : ids) findViewById(id).setEnabled(ready);
        ((TextView) findViewById(R.id.controlStatus)).setText(simulation
                ? getString(transport.isAvailable() ? R.string.simulation_on : R.string.simulation_off)
                : connectionMessage);
        renderSettings();
    }
    private boolean settingsFresh() {
        return transport.isAvailable() && (simulation || (!sessionDefaultsPending
                && SystemClock.elapsedRealtime() - settingsReceivedAt < TELEMETRY_STALE_MS));
    }
    private boolean canConfigure() { return canDrive() && settingsFresh(); }
    private void setupConfiguration() {
        speedSlider = findViewById(R.id.speedSlider);
        ((SpeedArcView) findViewById(R.id.speedArc)).bindValueLabel(findViewById(R.id.speedValue));
        avoidanceSwitch = findViewById(R.id.avoidanceSwitch);
        if (simulation) sessionDefaultsPending = false;
        avoidanceSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (applyingSettings) return;
            if (!canConfigure()) { renderSettings(); return; }
            safetyStop(); // Đổi chế độ yêu cầu nhấn lại hướng lái.
            requestedAvoidance = enabled;
            settingsRequestAt = SystemClock.elapsedRealtime();
            settingsTimedOut = false;
            if (simulation) acceptSettings(enabled, confirmedSpeed, settingsRequestAt);
            else bluetooth.setAvoidance(enabled);
            renderSettings(); // Giữ giá trị đã xác nhận cho tới khi nhận CFG mới.
        });
        speedSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && !applyingSettings) requestSpeed(Math.round(value));
        });
        speedSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(Slider slider) { speedDragging = true; }
            @Override public void onStopTrackingTouch(Slider slider) {
                speedDragging = false;
                requestSpeed(Math.round(slider.getValue()));
            }
        });
        renderSettings();
    }
    private void requestSpeed(int value) {
        if (!canConfigure()) return;
        sliderTarget = value;
        if (value == 0) safetyStop(); // Xóa lệnh F đang giữ trước khi có thể tăng tốc lại.
        handler.removeCallbacks(sendSpeed);
        speedQueued = true;
        long remaining = SPEED_SEND_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastSpeedSentAt);
        handler.postDelayed(sendSpeed, Math.max(0, remaining));
        renderSettings(); // Hiện ngay giá trị xem trước cùng trạng thái xác nhận của Arduino.
    }
    private void acceptSettings(boolean enabled, int speed, long receivedAt) {
        // Bỏ CFG còn đệm từ phiên trước cho tới khi xác nhận cấu hình khởi đầu.
        if (sessionDefaultsPending && (!enabled || speed != 60)) { renderSettings(); return; }
        sessionDefaultsPending = false;
        settingsReceivedAt = receivedAt;
        confirmedAvoidance = enabled;
        confirmedSpeed = speed;
        if (requestedAvoidance != null && requestedAvoidance == enabled) requestedAvoidance = null;
        if (requestedSpeed != null && requestedSpeed == speed) requestedSpeed = null;
        if (speed == 0 && controller.getActiveCommand() != 'S') safetyStop();
        renderSettings();
        renderTelemetry();
    }
    private void renderSettings() {
        if (speedSlider == null) return;
        if ((requestedAvoidance != null || requestedSpeed != null)
                && SystemClock.elapsedRealtime() - settingsRequestAt >= 2000) {
            requestedAvoidance = null;
            requestedSpeed = null;
            settingsTimedOut = true;
        }
        boolean fresh = settingsFresh();
        boolean pending = speedQueued || requestedAvoidance != null || requestedSpeed != null;
        applyingSettings = true;
        avoidanceSwitch.setChecked(confirmedAvoidance);
        if (!speedDragging && !speedQueued && requestedSpeed == null) speedSlider.setValue(confirmedSpeed);
        applyingSettings = false;
        avoidanceSwitch.setEnabled(canConfigure() && !pending);
        speedSlider.setEnabled(canConfigure() && requestedAvoidance == null);
        boolean preview = fresh && (speedQueued || requestedSpeed != null);
        int shown = preview ? sliderTarget : confirmedSpeed;
        ((SpeedArcView) findViewById(R.id.speedArc)).setSpeed(shown, fresh);
        ((TextView) findViewById(R.id.settingsStatus)).setText(!fresh ? getString(R.string.settings_syncing)
                : settingsTimedOut ? getString(R.string.settings_unconfirmed)
                : preview ? getString(R.string.speed_preview_status, confirmedSpeed)
                : pending ? getString(R.string.settings_sending)
                : simulation ? getString(R.string.simulation_on) : getString(R.string.settings_confirmed));
    }
    private void setupDebugPanel() {
        View panel = findViewById(R.id.debugPanel);
        debugSheet = BottomSheetBehavior.from(panel);
        debugSheet.setSkipCollapsed(true);
        debugSheet.setState(BottomSheetBehavior.STATE_HIDDEN);
        debugBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { debugSheet.setState(BottomSheetBehavior.STATE_HIDDEN); }
        };
        getOnBackPressedDispatcher().addCallback(this, debugBack);
        debugSheet.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(View sheet, int state) {
                setDebugOpen(state != BottomSheetBehavior.STATE_HIDDEN);
            }
            @Override public void onSlide(View sheet, float offset) { }
        });
        findViewById(R.id.logButton).setOnClickListener(view -> {
            safetyStop();
            setDebugOpen(true);
            debugSheet.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        findViewById(R.id.closeLogButton).setOnClickListener(view -> debugSheet.setState(BottomSheetBehavior.STATE_HIDDEN));
        findViewById(R.id.debugScrim).setOnClickListener(view -> debugSheet.setState(BottomSheetBehavior.STATE_HIDDEN));
    }
    private void setDebugOpen(boolean open) {
        debugOpen = open;
        if (open) safetyStop();
        debugBack.setEnabled(open);
        findViewById(R.id.debugScrim).setVisibility(open ? View.VISIBLE : View.GONE);
        findViewById(R.id.debugPanel).setImportantForAccessibility(open
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        updateAvailability();
    }
    private void onCommand(ControlCommand command) {
        journal.append(SystemClock.elapsedRealtime() - sessionStart, command);
        log.setText(journal.getTail());
        logCount.setText(journal.hasWriteFailed() ? getString(R.string.log_error)
                : getString(R.string.log_count, journal.getCount()));
        // Giá trị motor biểu thị hướng theo lệnh lái hiện tại.
        render(ControlCommand.of(controller.getActiveCommand()));
    }
    private void render(ControlCommand command) {
        if (!transport.isAvailable() || !resumed) command = ControlCommand.stop();
        MotorState state = DifferentialMixer.mix(command);
        motors.setText(getString(R.string.motor_value, state.left, state.right));
        int text;
        switch (command.value) {
            case 'F': text = R.string.moving_forward; break;
            case 'B': text = R.string.moving_reverse; break;
            case 'L': text = R.string.turn_left; break;
            case 'R': text = R.string.turn_right; break;
            default: text = R.string.stopped;
        }
        motion.setText(getString(R.string.requested_motion, getString(text)));
    }
    private void animatePress(View view, boolean pressed) {
        view.animate().cancel();
        view.animate().scaleX(pressed ? 0.975f : 1f).scaleY(pressed ? 0.975f : 1f)
                .setDuration(80).start();
    }
    private void renderTelemetry() {
        boolean hasTelemetry = !simulation && transport.isAvailable() && frontTelemetry != null;
        boolean fresh = hasTelemetry && SystemClock.elapsedRealtime() - telemetryReceivedAt < TELEMETRY_STALE_MS;
        TextView distance = findViewById(R.id.frontDistance);
        TextView status = findViewById(R.id.frontStatus);
        MaterialCardView card = findViewById(R.id.sensorCard);
        boolean sensorFault = hasTelemetry && frontTelemetry.state == TelemetryParser.State.SENSOR_FAULT;
        View sensorWarning = findViewById(R.id.sensorWarning);
        int warningVisibility = sensorFault ? View.VISIBLE : View.GONE;
        if (sensorWarning.getVisibility() != warningVisibility) sensorWarning.setVisibility(warningVisibility);
        boolean warning = fresh && !sensorFault && frontTelemetry.obstacle;
        String distanceText = sensorFault ? getString(R.string.front_distance_fault)
                : fresh && frontTelemetry.distanceCm >= 0
                ? getString(R.string.front_distance, frontTelemetry.distanceCm)
                : getString(R.string.front_distance_unknown);
        int statusText = R.string.sensor_no_data;
        if (fresh) {
            switch (frontTelemetry.state) {
                case BLOCKED: statusText = R.string.obstacle_blocked; break;
                case SENSOR_FAULT: break;
                default: statusText = R.string.sensor_ready;
            }
            if (!sensorFault && settingsFresh() && !confirmedAvoidance) statusText = R.string.avoidance_off;
        }
        if (!distanceText.contentEquals(distance.getText())) distance.setText(distanceText);
        if (!getString(statusText).contentEquals(status.getText())) {
            status.setText(statusText);
            status.setAlpha(0.65f);
            status.animate().alpha(1f).setDuration(120).start();
        }
        int color = themeColor(warning ? R.attr.driveWarningInk : R.attr.driveMuted);
        status.setTextColor(color);
        distance.setTextColor(themeColor(warning ? R.attr.driveWarningInk : R.attr.driveInk));
        card.setCardBackgroundColor(themeColor(warning ? R.attr.driveWarningSurface : R.attr.driveSurface));
        card.setStrokeColor(themeColor(warning ? R.attr.driveDanger : R.attr.driveLine));
    }
    @Override protected void onResume() {
        super.onResume();
        if (controller == null) return;
        resumed = true;
        safetyStop();
        updateAvailability();
        handler.postDelayed(tick, 175);
    }
    @Override protected void onPause() {
        Log.i(TAG, "activity=" + activityId + " onPause stop_controls socket_retained");
        resumed = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(sendSpeed);
        speedQueued = false;
        safetyStop();
        updateAvailability();
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && resumed && controller != null) safetyStop();
    }
    @Override protected void onDestroy() {
        Log.i(TAG, "activity=" + activityId + " onDestroy finishing=" + isFinishing()
                + " changingConfigurations=" + isChangingConfigurations() + " closing_socket=" + (bluetooth != null));
        handler.removeCallbacks(tick);
        handler.removeCallbacks(sendSpeed);
        speedQueued = false;
        if (bluetooth != null) bluetooth.close();
        if (journal != null) journal.close();
        super.onDestroy();
    }
}

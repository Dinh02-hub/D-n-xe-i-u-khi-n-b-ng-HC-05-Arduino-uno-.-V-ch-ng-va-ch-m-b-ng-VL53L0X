package com.arduinodrive.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;
import com.arduinodrive.R;
import com.arduinodrive.transport.BluetoothTransport;
import com.arduinodrive.transport.TelemetryParser;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

public final class SettingsActivity extends BaseActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int DISTANCE = 0, ZONE = 2;
    private final Slider[] safetySliders = new Slider[4];
    private final TextView[] safetyValues = new TextView[safetySliders.length];
    private final Integer[] requested = new Integer[safetySliders.length];
    private final long[] requestedAt = new long[requested.length];
    private final long[] requestedVersion = new long[requested.length];
    private final boolean[] dragging = new boolean[safetySliders.length];
    private final boolean[] queued = new boolean[safetySliders.length];
    private final Runnable[] sendChanges = new Runnable[safetySliders.length];
    private BluetoothTransport session;
    private boolean timedOut;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { renderSafety(); handler.postDelayed(this, 200); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        applyInsets(false);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        MaterialButtonToggleGroup theme = findViewById(R.id.themeToggle);
        theme.check("light".equals(settings.getTheme()) ? R.id.themeLight
                : "dark".equals(settings.getTheme()) ? R.id.themeDark : R.id.themeSystem);
        theme.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            String next = id == R.id.themeLight ? "light" : id == R.id.themeDark ? "dark" : "system";
            if (!next.equals(settings.getTheme())) { settings.setTheme(next); recreate(); }
        });
        setupSafety();
        SwitchMaterial simulation = findViewById(R.id.defaultSimulationSwitch);
        simulation.setChecked(settings.isSimulationDefault());
        simulation.setOnCheckedChangeListener((button, checked) -> settings.setSimulationDefault(checked));
        findViewById(R.id.aboutButton).setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name).setMessage(R.string.about_text)
                .setPositiveButton(R.string.close, null).show());
    }

    private void setupSafety() {
        int[] sliders = {R.id.distanceSlider, R.id.intervalSlider, R.id.zoneSlider, R.id.brakeSlider};
        int[] values = {R.id.distanceValue, R.id.intervalValue, R.id.zoneValue, R.id.brakeValue};
        for (int i = 0; i < sliders.length; i++) {
            final int index = i;
            safetySliders[i] = findViewById(sliders[i]);
            safetyValues[i] = findViewById(values[i]);
            sendChanges[i] = () -> { queued[index] = false; sendSafety(index); };
            safetySliders[i].setLabelFormatter(value -> formatValue(index, Math.round(value)));
            safetySliders[i].addOnChangeListener((slider, value, fromUser) -> {
                if (!fromUser) return;
                handler.removeCallbacks(sendChanges[index]);
                queued[index] = !dragging[index];
                if (queued[index]) handler.postDelayed(sendChanges[index], 250); // Dành cho bàn phím và trợ năng.
            });
            safetySliders[i].addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override public void onStartTrackingTouch(Slider slider) {
                    dragging[index] = true;
                    queued[index] = false;
                    handler.removeCallbacks(sendChanges[index]);
                }
                @Override public void onStopTrackingTouch(Slider slider) {
                    dragging[index] = false;
                    sendSafety(index); // Chỉ gửi một khung khi thả tay.
                }
            });
        }
        renderSafety();
    }

    private String formatValue(int index, int value) {
        return getString(index == DISTANCE || index == ZONE ? R.string.distance_value : R.string.duration_value, value);
    }

    private boolean supportsSetting(int index, TelemetryParser.SafetySettings confirmed) {
        return confirmed != null && (index < ZONE || confirmed.supportsExtendedSettings());
    }

    private int maximumFor(int index, TelemetryParser.SafetySettings confirmed) {
        if (index == DISTANCE) {
            return Math.min(TelemetryParser.MAX_STOP_DISTANCE_CM,
                    TelemetryParser.MAX_SENSOR_DISTANCE_CM - confirmed.slowdownZoneCm);
        }
        if (index == ZONE) {
            return TelemetryParser.MAX_SENSOR_DISTANCE_CM - confirmed.distanceCm;
        }
        return Math.round(safetySliders[index].getValueTo());
    }

    private boolean isEditing(int index) {
        return dragging[index] || queued[index] || requested[index] != null;
    }

    private void sendSafety(int index) {
        if (session == null || !session.hasFreshSafetySettings()) { renderSafety(); return; }
        TelemetryParser.SafetySettings current = session.getSafetySettings();
        int value = Math.round(safetySliders[index].getValue());
        if (!supportsSetting(index, current) || value > maximumFor(index, current)) { renderSafety(); return; }
        if ((index == DISTANCE && requested[ZONE] != null) || (index == ZONE && requested[DISTANCE] != null)) {
            renderSafety();
            return;
        }
        int confirmed = current.valueAt(index);
        if (requested[index] == null && confirmed == value) {
            timedOut = false;
            renderSafety();
            return;
        }
        requested[index] = value;
        requestedAt[index] = SystemClock.elapsedRealtime();
        requestedVersion[index] = session.getSafetySettingsVersion();
        timedOut = false;
        session.setSafetySetting(index, value);
        renderSafety();
    }

    private void renderSafety() {
        BluetoothTransport current = BluetoothTransport.getActiveSession();
        if (session != current) {
            session = current;
            java.util.Arrays.fill(requested, null);
            java.util.Arrays.fill(queued, false);
            for (Runnable change : sendChanges) if (change != null) handler.removeCallbacks(change);
            timedOut = false;
        }
        boolean fresh = session != null && session.hasFreshSafetySettings();
        TelemetryParser.SafetySettings confirmed = fresh ? session.getSafetySettings() : null;
        boolean pending = false;
        for (int i = 0; i < requested.length; i++) {
            if (requested[i] != null) {
                if (supportsSetting(i, confirmed) && session.getSafetySettingsVersion() > requestedVersion[i]
                        && requested[i] == confirmed.valueAt(i)) requested[i] = null;
                else if (SystemClock.elapsedRealtime() - requestedAt[i] >= 2000) {
                    requested[i] = null;
                    timedOut = true;
                }
            }
            pending |= requested[i] != null;
        }
        for (int i = 0; i < safetySliders.length; i++) {
            Slider slider = safetySliders[i];
            if (!supportsSetting(i, confirmed)) {
                slider.setEnabled(false);
                safetyValues[i].setText(R.string.value_unknown);
                continue;
            }

            int maximum = maximumFor(i, confirmed);
            // Hai khoảng cách dùng chung giới hạn đo. Chờ xác nhận một thanh trước khi chỉnh thanh kia.
            boolean otherDistanceEditing = (i == DISTANCE && isEditing(ZONE)) || (i == ZONE && isEditing(DISTANCE));
            slider.setEnabled(maximum > slider.getValueFrom() && !otherDistanceEditing);
            float displayMaximum = Math.max(maximum, slider.getValueFrom() + 1);
            if (slider.getValueTo() != displayMaximum) slider.setValueTo(displayMaximum);
            if (!isEditing(i) || slider.getValue() > maximum) slider.setValue(confirmed.valueAt(i));
            safetyValues[i].setText(formatValue(i, confirmed.valueAt(i)));
        }
        ((TextView) findViewById(R.id.safetySettingsStatus)).setText(session == null
                ? R.string.safety_connect : confirmed == null ? R.string.safety_waiting
                : timedOut ? R.string.settings_unconfirmed
                : pending ? R.string.settings_sending : !confirmed.supportsExtendedSettings()
                ? R.string.safety_legacy : R.string.settings_confirmed);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }
    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        java.util.Arrays.fill(dragging, false);
        java.util.Arrays.fill(queued, false);
        super.onPause();
    }
}

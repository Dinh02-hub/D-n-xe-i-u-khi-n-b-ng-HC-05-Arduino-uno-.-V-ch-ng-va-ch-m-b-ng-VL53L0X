package com.arduinodrive.ui;

import android.content.Intent;
import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.view.View;
import android.view.animation.PathInterpolator;
import com.arduinodrive.R;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

public final class ConnectionActivity extends BaseActivity {
    private static final String TAG = "DriveConnection";
    private final PathInterpolator settle = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private CarPreviewView hero;
    private View summary, deviceCard;
    private boolean entrancePlayed;
    private boolean connectionFlowInProgress;
    private boolean openingControl;
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Log.i(TAG, "permission_result " + result);
                if (hasBluetoothPermissions()) chooseDevice();
                else showError(getString(R.string.bluetooth_permission_denied));
            });
    private final ActivityResultLauncher<Intent> enableBluetooth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Log.i(TAG, "enable_bluetooth_result code=" + result.getResultCode());
                if (result.getResultCode() == RESULT_OK) chooseDevice();
                else showError(getString(R.string.bluetooth_enable_required));
            });
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_connection);
        applyInsets(false);
        hero = findViewById(R.id.heroCar);
        hero.setHero(true);
        summary = findViewById(R.id.connectionSummary);
        deviceCard = findViewById(R.id.pairedDeviceCard);
        entrancePlayed = state != null;
        findViewById(R.id.openControlButton).setOnClickListener(view -> {
            if (connectionFlowInProgress || openingControl) {
                Log.w(TAG, "open_ignored connection_flow=" + connectionFlowInProgress + " opening_control=" + openingControl);
                return;
            }
            connectionFlowInProgress = true;
            String permissionSummary = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? "connect=" + ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        + " scan=" + ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    : "legacyBluetooth=" + ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                        + " legacyAdmin=" + ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN);
            Log.i(TAG, "open_control sdk=" + Build.VERSION.SDK_INT + " simulation=" + settings.isSimulationDefault()
                    + " permissions " + permissionSummary);
            if (settings.isSimulationDefault()) {
                Log.w(TAG, "simulation_selected Bluetooth connection will not be attempted");
                openControl(new Intent(this, ControlActivity.class).putExtra("simulation", true));
            } else if (!hasBluetoothPermissions()) {
                Log.i(TAG, "request_nearby_permissions");
                permissions.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN});
            } else chooseDevice();
        });
        findViewById(R.id.settingsButton).setOnClickListener(view ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }
    private void openControl(Intent intent) {
        if (openingControl) { Log.w(TAG, "duplicate_control_launch_ignored"); return; }
        openingControl = true;
        connectionFlowInProgress = false;
        startActivity(intent);
    }
    private boolean hasBluetoothPermissions() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
    }
    @SuppressLint("MissingPermission")
    private void chooseDevice() {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "bonded_devices_permission_missing");
            showError(getString(R.string.bluetooth_permission_denied));
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            Log.w(TAG, "adapter_unavailable");
            showError(getString(R.string.bluetooth_unavailable)); return;
        }
        try {
            Log.i(TAG, "adapter enabled=" + adapter.isEnabled());
            if (!adapter.isEnabled()) {
                enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                return;
            }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            Log.i(TAG, "bonded_device_count=" + devices.size());
            Collections.sort(devices, (a, b) -> deviceLabel(a).compareToIgnoreCase(deviceLabel(b)));
            if (devices.isEmpty()) { showError(getString(R.string.no_paired_devices)); return; }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                labels[i] = deviceLabel(devices.get(i));
                Log.i(TAG, "bonded_device " + labels[i] + " bondState=" + devices.get(i).getBondState());
            }
            androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.choose_device)
                    .setItems(labels, (ignored, which) -> {
                        Log.i(TAG, "device_selected " + labels[which]);
                        openControl(new Intent(this, ControlActivity.class)
                            .putExtra("device_address", devices.get(which).getAddress())
                            .putExtra("device_name", labels[which]).putExtra("simulation", false));
                    })
                    .setOnDismissListener(ignored -> connectionFlowInProgress = false)
                    .setNegativeButton(R.string.close, null).show();
            if (motionEnabled() && dialog.getListView() != null)
                enter(dialog.getListView(), 6f, 0, 220);
        } catch (SecurityException error) {
            Log.e(TAG, "bonded_devices_permission_failure", error);
            showError(getString(R.string.bluetooth_permission_denied));
        }
    }

    @SuppressLint("MissingPermission")
    private String deviceLabel(BluetoothDevice device) {
        String name = device.getName();
        return (name == null ? "Bluetooth" : name) + " · " + device.getAddress();
    }
    private void showError(String message) {
        connectionFlowInProgress = false;
        Log.w(TAG, "connection_screen_error " + message);
        setStatus(getString(R.string.disconnected), message);
        if (motionEnabled()) enter(summary, 4f, 0, 200);
    }

    private void setStatus(String status, String detail) {
        ((TextView) findViewById(R.id.connectionStatus)).setText(status);
        ((TextView) findViewById(R.id.connectionDetail)).setText(detail);
    }

    private void enter(View view, float fromDp, long delay, long duration) {
        view.animate().cancel();
        view.setAlpha(0.72f);
        view.setTranslationY(fromDp * getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                .setDuration(duration).setInterpolator(settle).start();
    }
    @Override protected void onResume() {
        super.onResume();
        openingControl = false;
        Log.i(TAG, "onResume simulation=" + settings.isSimulationDefault());
        boolean simulation = settings.isSimulationDefault();
        setStatus(getString(simulation ? R.string.simulation_ready : R.string.disconnected),
                getString(simulation ? R.string.simulation_detail : R.string.offline_detail));
        if (!entrancePlayed && motionEnabled()) {
            enter(hero, 12f, 0, 400);
            enter(summary, 8f, 55, 310);
            enter(deviceCard, 8f, 95, 310);
        }
        hero.setAmbientMotion(motionEnabled());
        entrancePlayed = true;
    }
    @Override protected void onPause() {
        Log.i(TAG, "onPause opening_control=" + openingControl + " (no socket owned here)");
        hero.setAmbientMotion(false);
        for (View view : new View[]{hero, summary, deviceCard}) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setTranslationY(0f);
        }
        super.onPause();
    }
}

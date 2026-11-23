package com.arduinodrive.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.arduinodrive.control.ControlCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.lang.ref.WeakReference;

/** Phiên Bluetooth SPP do Activity quản lý. Socket chạy ngoài luồng giao diện. */
public final class BluetoothTransport implements CommandTransport {
    private static final String TAG = "DriveBluetooth";
    // Cài đặt dùng chung kết nối HC-05 của màn hình điều khiển.
    private static volatile WeakReference<BluetoothTransport> activeSession = new WeakReference<>(null);
    private TelemetryParser.SafetySettings safetySettings;
    private long safetySettingsReceivedAt;
    private long safetySettingsVersion;
    private static final String SAFETY_COMMANDS = "DIZE";
    private static final int[] SAFETY_MIN = {TelemetryParser.MIN_STOP_DISTANCE_CM, 40, 0, TelemetryParser.MIN_BRAKE_MS};
    private static final int[] SAFETY_MAX = {TelemetryParser.MAX_STOP_DISTANCE_CM, 200,
            TelemetryParser.MAX_SLOWDOWN_ZONE_CM, TelemetryParser.MAX_BRAKE_MS};
    private final Integer[] pendingSafety = new Integer[SAFETY_COMMANDS.length()];
    public interface Listener {
        void onConnectionChanged(boolean connected, String error);
        void onCommandSent(ControlCommand command);
        default void onTelemetry(TelemetryParser.Snapshot snapshot, long receivedAt) { }
        default void onSettings(boolean avoidanceEnabled, int speedPercent, long receivedAt) { }
    }
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private BluetoothSocket socket;
    private boolean connected;
    private boolean closed;
    private boolean started;
    private final String session = Integer.toHexString(System.identityHashCode(this));
    private ControlCommand pending;
    private long pendingAt;
    private Boolean pendingAvoidance;
    private Integer pendingSpeed;
    private final Runnable connectTimeout = () -> fail("connect_timeout", "Connection timed out (12 s)", null);

    public BluetoothTransport(Listener listener) { this.listener = listener; }

    @SuppressLint("MissingPermission") // ConnectionActivity kiểm tra quyền trước khi kết nối.
    public void connect(BluetoothAdapter adapter, BluetoothDevice device) {
        synchronized (this) {
            if (started || closed) {
                Log.w(TAG, prefix() + "connect_ignored started=" + started + " closed=" + closed);
                return;
            }
            started = true;
        }
        Log.i(TAG, prefix() + "connect_requested address=" + device.getAddress() + " uuid=" + SPP + " secure=true");
        main.postDelayed(connectTimeout, 12000);
        new Thread(() -> {
            String stage = "socket_create";
            long began = SystemClock.elapsedRealtime();
            try {
                Log.i(TAG, prefix() + stage + " bondState=" + device.getBondState());
                BluetoothSocket candidate = device.createRfcommSocketToServiceRecord(SPP);
                Log.i(TAG, prefix() + "socket_created");
                synchronized (this) {
                    if (closed) {
                        Log.i(TAG, prefix() + "connect_cancelled_before_socket_publish");
                        candidate.close(); return;
                    }
                    socket = candidate;
                }
                stage = "cancel_discovery";
                Log.i(TAG, prefix() + stage + " result=" + adapter.cancelDiscovery());
                stage = "socket_connect";
                Log.i(TAG, prefix() + stage + " begin");
                candidate.connect();
                Log.i(TAG, prefix() + "socket_connected elapsedMs=" + (SystemClock.elapsedRealtime() - began));
                stage = "open_streams_initial_stop";
                OutputStream output = candidate.getOutputStream();
                InputStream input = candidate.getInputStream();
                // Dừng xe và tắt tự lùi trên firmware cũ trước khi bật chống va chạm.
                output.write("ST:0\nA:1\nV:60\n".getBytes(StandardCharsets.US_ASCII));
                Log.i(TAG, prefix() + "streams_ready initial_stop_written");
                synchronized (this) {
                    if (closed) return;
                    connected = true;
                }
                activeSession = new WeakReference<>(this);
                main.removeCallbacks(connectTimeout);
                main.post(() -> {
                    Listener current = currentListener();
                    if (current != null && isAvailable()) current.onConnectionChanged(true, null);
                });
                new Thread(() -> writeLoop(output), "hc05-writer").start();
                TelemetryParser parser = new TelemetryParser(new TelemetryParser.Listener() {
                    @Override public void onTelemetry(TelemetryParser.Snapshot snapshot) {
                        long receivedAt = SystemClock.elapsedRealtime();
                        main.post(() -> {
                            Listener current = currentListener();
                            if (current != null && isAvailable()
                                    && SystemClock.elapsedRealtime() - receivedAt < 1000)
                                current.onTelemetry(snapshot, receivedAt);
                        });
                    }
                    @Override public void onSettings(boolean avoidanceEnabled, int speedPercent) {
                        long receivedAt = SystemClock.elapsedRealtime();
                        main.post(() -> {
                            Listener current = currentListener();
                            if (current != null && isAvailable()
                                    && SystemClock.elapsedRealtime() - receivedAt < 1000)
                                current.onSettings(avoidanceEnabled, speedPercent, receivedAt);
                        });
                    }
                    @Override public void onSafetySettings(TelemetryParser.SafetySettings settings) {
                        long receivedAt = SystemClock.elapsedRealtime();
                        main.post(() -> {
                            synchronized (BluetoothTransport.this) {
                                if (!isAvailable() || SystemClock.elapsedRealtime() - receivedAt >= 1000) return;
                                safetySettings = settings;
                                safetySettingsReceivedAt = receivedAt;
                                safetySettingsVersion++;
                            }
                        });
                    }
                });
                // Dùng EOF hoặc IOException để phát hiện mất kết nối, kể cả với firmware không gửi telemetry.
                byte[] buffer = new byte[64];
                stage = "socket_read";
                int count;
                while ((count = input.read(buffer)) != -1) parser.accept(buffer, count);
                fail("read_eof", "Bluetooth socket closed", null);
            } catch (IOException | SecurityException error) {
                Log.i(TAG, prefix() + "operation_ended stage=" + stage + " elapsedMs="
                        + (SystemClock.elapsedRealtime() - began));
                fail(stage, error.getMessage(), error);
            }
        }, "hc05-connect-read").start();
    }

    @Override public synchronized boolean isAvailable() { return connected && !closed; }

    public static BluetoothTransport getActiveSession() {
        BluetoothTransport current = activeSession.get();
        return current != null && current.isAvailable() ? current : null;
    }
    public synchronized TelemetryParser.SafetySettings getSafetySettings() { return safetySettings; }
    public synchronized long getSafetySettingsVersion() { return safetySettingsVersion; }
    public synchronized boolean hasFreshSafetySettings() {
        return isAvailable() && safetySettings != null
                && SystemClock.elapsedRealtime() - safetySettingsReceivedAt < 1000;
    }
    public synchronized void setSafetySetting(int index, int value) {
        if (index < 0 || index >= pendingSafety.length || value < SAFETY_MIN[index] || value > SAFETY_MAX[index])
            throw new IllegalArgumentException("Safety setting out of range");
        if (!isAvailable()) return;
        pendingSafety[index] = value;
        notifyAll();
    }
    private boolean hasPendingSafety() {
        for (Integer value : pendingSafety) if (value != null) return true;
        return false;
    }

    @Override public synchronized void send(ControlCommand command) {
        if (!isAvailable()) return;
        if (pending != null && pending.value == 'S' && command.value != 'S') return;
        // Chỉ giữ lệnh lái mới nhất.
        pending = command;
        pendingAt = SystemClock.elapsedRealtime();
        notifyAll();
    }

    public synchronized void setAvoidance(boolean enabled) {
        if (!isAvailable()) return;
        pendingAvoidance = enabled;
        notifyAll();
    }

    public synchronized void setSpeed(int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Speed must be 0..100");
        if (!isAvailable()) return;
        pendingSpeed = percent; // Chỉ giữ giá trị mới nhất từ thanh trượt.
        notifyAll();
    }

    private void writeLoop(OutputStream output) {
        try {
            while (true) {
                ControlCommand command;
                String frame;
                synchronized (this) {
                    while (!closed && pending == null && pendingAvoidance == null && pendingSpeed == null
                            && !hasPendingSafety()) wait();
                    if (closed) return;
                    command = pending;
                    if (command != null) {
                        pending = null;
                        if (command.value != 'S' && SystemClock.elapsedRealtime() - pendingAt > 300) continue;
                        frame = command.toAscii();
                    } else if (pendingAvoidance != null) {
                        frame = "A:" + (pendingAvoidance ? "1" : "0") + "\n";
                        pendingAvoidance = null;
                    } else if (pendingSpeed != null) {
                        frame = "V:" + pendingSpeed + "\n";
                        pendingSpeed = null;
                    } else {
                        int index = 0;
                        while (pendingSafety[index] == null) index++;
                        frame = SAFETY_COMMANDS.charAt(index) + ":" + pendingSafety[index] + "\n";
                        pendingSafety[index] = null;
                    }
                }
                Runnable timeout = () -> fail("write_timeout", "Bluetooth write timed out", null);
                main.postDelayed(timeout, 400);
                try { output.write(frame.getBytes(StandardCharsets.US_ASCII)); }
                finally { main.removeCallbacks(timeout); }
                if (command != null) main.post(() -> {
                    Listener current = currentListener();
                    if (current != null && isAvailable()) current.onCommandSent(command);
                });
            }
        } catch (IOException | SecurityException error) { fail("socket_write", error.getMessage(), error); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); fail("socket_write", "Writer interrupted", error);
        }
    }

    private synchronized Listener currentListener() { return listener; }

    private String prefix() { return "session=" + session + " "; }

    private void fail(String stage, String error, Throwable cause) {
        synchronized (this) {
            if (closed) {
                Log.d(TAG, prefix() + "failure_after_close stage=" + stage);
                return;
            }
            closed = true;
            connected = false;
            pending = null;
            pendingAvoidance = null;
            pendingSpeed = null;
            java.util.Arrays.fill(pendingSafety, null);
            safetySettings = null;
            notifyAll();
        }
        Log.e(TAG, prefix() + "disconnected stage=" + stage + " message=" + error, cause);
        main.removeCallbacks(connectTimeout);
        closeSocketAsync();
        main.post(() -> {
            Listener current = currentListener();
            if (current != null) current.onConnectionChanged(false,
                    stage + ": " + (error == null ? "Bluetooth I/O error" : error));
        });
    }

    public void close() {
        Log.i(TAG, prefix() + "close_requested thread=" + Thread.currentThread().getName());
        synchronized (this) {
            listener = null;
            closed = true;
            connected = false;
            pending = null;
            pendingAvoidance = null;
            pendingSpeed = null;
            java.util.Arrays.fill(pendingSafety, null);
            safetySettings = null;
            notifyAll();
        }
        main.removeCallbacks(connectTimeout);
        closeSocketAsync();
    }

    private void closeSocketAsync() {
        final BluetoothSocket current;
        synchronized (this) { current = socket; socket = null; }
        if (current != null) new Thread(() -> {
            try {
                current.close();
                Log.i(TAG, prefix() + "socket_closed");
            } catch (IOException error) { Log.w(TAG, prefix() + "socket_close_failure", error); }
        }, "hc05-close").start();
    }
}

package com.arduinodrive.transport;

import androidx.test.platform.app.InstrumentationRegistry;
import com.arduinodrive.control.ControlCommand;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothTransportTest {
    private void connected(BluetoothTransport transport) throws Exception {
        java.lang.reflect.Field field = BluetoothTransport.class.getDeclaredField("connected");
        field.setAccessible(true); field.set(transport, true);
    }
    private void verifyWriter(boolean stopDuringFrame) throws Exception {
        BluetoothTransport transport = new BluetoothTransport(new BluetoothTransport.Listener() {
            @Override public void onConnectionChanged(boolean connected, String error) { }
            @Override public void onCommandSent(ControlCommand command) { }
        });
        connected(transport);
        transport.setAvoidance(true); transport.setAvoidance(false);
        transport.setSpeed(80); transport.setSpeed(35);
        if (!stopDuringFrame) {
            transport.send(ControlCommand.stop());
            transport.send(ControlCommand.of('F')); // Giữ STOP đã xếp hàng.
        }
        String expected = stopDuringFrame ? "A:0\nSV:35\n" : "SA:0\nV:35\n";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override public void write(int value) {
                bytes.write(value);
                if (stopDuringFrame && bytes.size() == 1) {
                    transport.send(ControlCommand.stop());
                    transport.send(ControlCommand.of('F'));
                }
                if (bytes.size() == expected.length()) transport.close();
            }
        };
        Thread writer = new Thread(() -> {
            try {
                java.lang.reflect.Method method = BluetoothTransport.class.getDeclaredMethod("writeLoop", java.io.OutputStream.class);
                method.setAccessible(true); method.invoke(transport, output);
            } catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        });
        try {
            writer.start();
            assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(expected, bytes.toString("US-ASCII"));
        } finally { transport.close(); writer.join(1000); }
    }
    @Test public void writerCoalescesSettingsAndSendsStopBeforeConfiguration() throws Exception { verifyWriter(false); }
    @Test public void stopDuringConfigurationNeverInterleavesOrGetsOverwrittenByForward() throws Exception { verifyWriter(true); }
    @Test public void destroyedSessionRejectsLateConnectAndDoesNotReportDelivery() {
        AtomicInteger callbacks = new AtomicInteger();
        BluetoothTransport transport = new BluetoothTransport(new BluetoothTransport.Listener() {
            @Override public void onConnectionChanged(boolean connected, String error) { callbacks.incrementAndGet(); }
            @Override public void onCommandSent(ControlCommand command) { callbacks.incrementAndGet(); }
        });
        transport.close();
        // Bỏ yêu cầu đến sau khi Activity đã bị hủy.
        transport.connect(null, null);
        transport.send(ControlCommand.of('F'));
        transport.close();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(transport.isAvailable());
        assertEquals(0, callbacks.get());
    }
    @Test public void safetyWriterKeepsLatestValuesAndNeverUsesRightTurnAsConfiguration() throws Exception {
        BluetoothTransport transport = new BluetoothTransport(new BluetoothTransport.Listener() {
            @Override public void onConnectionChanged(boolean connected, String error) { }
            @Override public void onCommandSent(ControlCommand command) { }
        });
        connected(transport);
        transport.setSafetySetting(0, 150); transport.setSafetySetting(0, 10);
        transport.setSafetySetting(1, 40);
        transport.setSafetySetting(2, 120); transport.setSafetySetting(2, 0);
        transport.setSafetySetting(3, 47);
        transport.send(ControlCommand.stop()); transport.send(ControlCommand.of('R'));
        String expected = "SD:10\nI:40\nZ:0\nE:47\n";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override public void write(int value) {
                bytes.write(value);
                if (bytes.size() == expected.length()) transport.close();
            }
        };
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                java.lang.reflect.Method method = BluetoothTransport.class.getDeclaredMethod("writeLoop", java.io.OutputStream.class);
                method.setAccessible(true); method.invoke(transport, output);
            } catch (Throwable error) { failure.set(error); }
        });
        try {
            writer.start(); writer.join(3000);
            assertFalse(writer.isAlive()); assertNull(failure.get());
            assertEquals(expected, bytes.toString("US-ASCII"));
            assertFalse(bytes.toString("US-ASCII").contains("R:"));
        } finally { transport.close(); writer.join(1000); }
    }
    @Test public void safetyWriterRejectsInvalidValuesBeforeQueueing() throws Exception {
        BluetoothTransport transport = new BluetoothTransport(new BluetoothTransport.Listener() {
            @Override public void onConnectionChanged(boolean connected, String error) { }
            @Override public void onCommandSent(ControlCommand command) { }
        });
        connected(transport);
        try {
            for (int[] entry : new int[][]{{0,-1},{0,0},{0,9},{0,151},{1,39},{1,201},{2,-1},{2,191},{3,0},{3,61},{4,0}}) {
                try { transport.setSafetySetting(entry[0], entry[1]); fail("Accepted invalid safety parameter"); }
                catch (IllegalArgumentException expected) { }
            }
        } finally { transport.close(); }
    }
    @Test public void ioFailureDisconnectsAndDropsQueuedConfiguration() throws Exception {
        AtomicInteger disconnected = new AtomicInteger();
        BluetoothTransport transport = new BluetoothTransport(new BluetoothTransport.Listener() {
            @Override public void onConnectionChanged(boolean connected, String error) {
                if (!connected && error.contains("socket_write")) disconnected.incrementAndGet();
            }
            @Override public void onCommandSent(ControlCommand command) { fail("Failed write reported as sent"); }
        });
        connected(transport);
        for (int i = 0; i < 2; i++) transport.setSafetySetting(i, i == 1 ? 40 : 10);
        transport.send(ControlCommand.stop());
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("Test link loss"); }
        };
        java.lang.reflect.Method writer = BluetoothTransport.class.getDeclaredMethod("writeLoop", java.io.OutputStream.class);
        writer.setAccessible(true); writer.invoke(transport, output);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(transport.isAvailable()); assertNull(transport.getSafetySettings());
        assertEquals(1, disconnected.get());
        java.lang.reflect.Field queue = BluetoothTransport.class.getDeclaredField("pendingSafety");
        queue.setAccessible(true);
        for (Integer pending : (Integer[]) queue.get(transport)) assertNull(pending);
        transport.close();
    }
}

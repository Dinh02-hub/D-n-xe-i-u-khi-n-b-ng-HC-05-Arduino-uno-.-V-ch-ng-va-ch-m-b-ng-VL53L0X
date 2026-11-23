package com.arduinodrive.transport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TelemetryParserTest {
    @Test public void settingsAckIsAtomicAndIndependentOfSensorFrame() {
        List<String> settings = new ArrayList<>();
        List<TelemetryParser.Snapshot> sensors = new ArrayList<>();
        TelemetryParser decoder = new TelemetryParser(new TelemetryParser.Listener() {
            @Override public void onTelemetry(TelemetryParser.Snapshot snapshot) { sensors.add(snapshot); }
            @Override public void onSettings(boolean enabled, int speed) { settings.add(enabled + ":" + speed); }
        });
        for (String chunk : new String[]{"CF", "G:0,", "35", "\r\nDIST:50.0\nCFG:1,60\n",
                "OBSTACLE:0\nSTATE:READY\nCFG:0,0\nCFG:1,100\n"}) {
            byte[] bytes = chunk.getBytes(StandardCharsets.US_ASCII);
            decoder.accept(bytes, bytes.length);
        }
        assertEquals(List.of("false:35", "true:60", "false:0", "true:100"), settings);
        assertEquals(1, sensors.size());
    }
    @Test public void malformedSettingsNeverProduceAnAcknowledgement() {
        List<Integer> settings = new ArrayList<>();
        TelemetryParser decoder = new TelemetryParser(new TelemetryParser.Listener() {
            @Override public void onTelemetry(TelemetryParser.Snapshot snapshot) { }
            @Override public void onSettings(boolean enabled, int speed) { settings.add(speed); }
        });
        for (String line : new String[]{"CFG:2,60", "CFG:1,-1", "CFG:1,101", "CFG:1,9999999",
                "CFG:1,NaN", "CFG:1,", "CFG:1,60,0", "CFG:1,6F", "CFG:1,+60", "CFG:1,060"}) {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.US_ASCII);
            decoder.accept(bytes, bytes.length);
        }
        assertTrue(settings.isEmpty());
    }
    private final List<TelemetryParser.Snapshot> frames = new ArrayList<>();
    private final TelemetryParser parser = new TelemetryParser(frames::add);
    private void feed(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        parser.accept(bytes, bytes.length);
    }
    @Test public void fragmentedAndCoalescedFramesCommitOnlyAtFinalNewline() {
        feed("DI"); feed("ST:10.0\r\nOBST"); feed("ACLE:1\nSTATE:BLOC");
        assertTrue(frames.isEmpty());
        feed("KED\nDIST:12.5\nOBSTACLE:1\nSTATE:BLOCKED\nDIST:15.0\nOBSTACLE:0\nSTATE:READY");
        assertEquals(2, frames.size());
        assertEquals(TelemetryParser.State.BLOCKED, frames.get(0).state);
        assertEquals(12.5, frames.get(1).distanceCm, 0);
        feed("\n"); assertEquals(3, frames.size());
        assertFalse(frames.get(2).obstacle);
    }
    @Test public void oneByteReadsWorkAndFaultDoesNotMeanClear() {
        for (char c : "DIST:-1\nOBSTACLE:1\nSTATE:SENSOR_FAULT\n".toCharArray()) feed(String.valueOf(c));
        assertEquals(1, frames.size());
        assertEquals(-1, frames.get(0).distanceCm, 0);
        assertTrue(frames.get(0).obstacle);
    }
    @Test public void malformedNonFiniteAndIncompleteFramesCannotRefreshData() {
        for (String distance : new String[]{"NaN", "Infinity", "-Infinity", "0", "-2", "201", "819.0", "8190", "text"}) {
            feed("DIST:" + distance + "\nOBSTACLE:0\nSTATE:READY\n");
        }
        feed("DIST:20\nSTATE:READY\n");
        feed("DIST:20\nOBSTACLE:2\nSTATE:READY\n");
        feed("DIST:20\nOBSTACLE:0\nSTATE:UNKNOWN\n");
        feed("DIST:-1\nOBSTACLE:0\nSTATE:READY\n");
        feed("DIST:20\nOBSTACLE:0\nSTATE:BACKING\n");
        feed("DIST:20\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n");
        assertTrue(frames.isEmpty());
        feed("DIST:20\nOBSTACLE:0\nSTATE:READY\n");
        assertEquals(1, frames.size());
    }
    @Test public void vl53l0xDistanceIsAlreadyInCentimeters() {
        feed("DIST:16.7\nOBSTACLE:1\nSTATE:BLOCKED\n");
        assertEquals(1, frames.size());
        assertEquals(16.7, frames.get(0).distanceCm, 0);
    }
    @Test public void overflowAndNoiseAreDiscardedUntilANewFrame() {
        feed("DIST:20\nOBSTACLE:0\n" + "x".repeat(10000) + "\nSTATE:READY\n");
        feed("DIST:20\nOBSTACLE:0\n\u0000\nSTATE:READY\n");
        assertTrue(frames.isEmpty());
        feed("DIST:15\nOBSTACLE:0\nSTATE:READY\n");
        assertEquals(1, frames.size());
    }
    @Test public void validCloseDistanceStillDisplaysWhenTelemetryReportsReady() {
        feed("DIST:2.0\nOBSTACLE:0\nSTATE:READY\n");
        assertEquals(1, frames.size());
        assertEquals(2.0, frames.get(0).distanceCm, 0);
    }
    @Test public void safetySettingsAreAtomicBoundedAndIndependentOfOldConfiguration() {
        List<TelemetryParser.SafetySettings> settings = new ArrayList<>();
        List<Integer> speeds = new ArrayList<>();
        TelemetryParser decoder = new TelemetryParser(new TelemetryParser.Listener() {
            @Override public void onTelemetry(TelemetryParser.Snapshot snapshot) { }
            @Override public void onSettings(boolean enabled, int speed) { speeds.add(speed); }
            @Override public void onSafetySettings(TelemetryParser.SafetySettings value) { settings.add(value); }
        });
        for (String chunk : new String[]{"SA", "FE:40,60,60,30", "\r\nCFG:1,60\nSAFE:10,40,0,0\n",
                "SAFE:10,200,190,60\nSAFE:150,60,0,1\n",
                "SAFE:9,40,0,0\nSAFE:151,60,0,30\nSAFE:40,39,60,30\nSAFE:40,201,60,30\n",
                "SAFE:150,200,51,60\nSAFE:40,60,60,61\nSAFE:40,60,60,0\nSAFE:40,60,-1,30\n",
                "SAFE:040,60,60,30\nSAFE:40,60,60\nSAFE:40,60,60,30,1\nSAFE:40,60,60,3F\n"}) {
            byte[] bytes = chunk.getBytes(StandardCharsets.US_ASCII);
            decoder.accept(bytes, bytes.length);
        }
        assertEquals(List.of(60), speeds);
        assertEquals(4, settings.size());
        assertEquals(40, settings.get(0).distanceCm);
        assertEquals(60, settings.get(0).valueAt(2));
        assertEquals(30, settings.get(0).valueAt(3));
        assertTrue(settings.get(0).supportsExtendedSettings());
        assertEquals(40, settings.get(1).intervalMs);
        assertEquals(10, settings.get(1).distanceCm);
        assertFalse(settings.get(1).supportsExtendedSettings());
        assertEquals(190, settings.get(2).slowdownZoneCm);
        assertEquals(60, settings.get(2).brakeMs);
        assertTrue(settings.get(3).supportsExtendedSettings());
        assertEquals(0, settings.get(3).slowdownZoneCm);
        assertEquals(1, settings.get(3).brakeMs);
    }
}

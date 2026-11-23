package com.arduinodrive.transport;

/** Giới hạn độ dài dòng ASCII và chỉ phát khung DIST/OBSTACLE/STATE đầy đủ. */
public final class TelemetryParser {
    public static final int MIN_STOP_DISTANCE_CM = 10;
    public static final int MAX_STOP_DISTANCE_CM = 150;
    public static final int MAX_SENSOR_DISTANCE_CM = 200;
    public static final int MAX_SLOWDOWN_ZONE_CM = MAX_SENSOR_DISTANCE_CM - MIN_STOP_DISTANCE_CM;
    public static final int MIN_BRAKE_MS = 1;
    public static final int MAX_BRAKE_MS = 60;
    public enum State { READY, BLOCKED, SENSOR_FAULT }
    public static final class SafetySettings {
        public final int distanceCm, intervalMs, slowdownZoneCm, brakeMs;
        public SafetySettings(int distanceCm, int intervalMs, int slowdownZoneCm, int brakeMs) {
            if (distanceCm < MIN_STOP_DISTANCE_CM || distanceCm > MAX_STOP_DISTANCE_CM
                    || intervalMs < 40 || intervalMs > 200
                    || slowdownZoneCm < 0 || slowdownZoneCm > MAX_SENSOR_DISTANCE_CM - distanceCm
                    || (brakeMs == 0 ? slowdownZoneCm != 0 : brakeMs < MIN_BRAKE_MS || brakeMs > MAX_BRAKE_MS))
                throw new IllegalArgumentException("Safety settings out of range");
            this.distanceCm = distanceCm;
            this.intervalMs = intervalMs;
            this.slowdownZoneCm = slowdownZoneCm;
            this.brakeMs = brakeMs;
        }
        // Firmware trước đây gửi 0,0 ở hai trường cuối.
        public boolean supportsExtendedSettings() { return brakeMs != 0; }

        public int valueAt(int index) {
            switch (index) {
                case 0: return distanceCm;
                case 1: return intervalMs;
                case 2: return slowdownZoneCm;
                case 3: return brakeMs;
                default: throw new IllegalArgumentException("Unknown safety setting");
            }
        }
    }
    public static final class Snapshot {
        public final double distanceCm;
        public final boolean obstacle;
        public final State state;
        Snapshot(double distanceCm, boolean obstacle, State state) {
            this.distanceCm = distanceCm;
            this.obstacle = obstacle;
            this.state = state;
        }
    }
    public interface Listener {
        void onTelemetry(Snapshot snapshot);
        default void onSettings(boolean avoidanceEnabled, int speedPercent) { }
        default void onSafetySettings(SafetySettings settings) { }
    }
    private final Listener listener;
    private final StringBuilder line = new StringBuilder();
    private boolean discard, haveDistance, haveObstacle, obstacle;
    private double distance;

    public TelemetryParser(Listener listener) { this.listener = listener; }

    public void accept(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            int value = bytes[i] & 0xff;
            if (value == '\n') {
                if (!discard) parseLine(line.toString());
                line.setLength(0);
                discard = false;
            } else if (value != '\r' && !discard) {
                if (value < 32 || value > 126 || line.length() >= 48) {
                    discard = true;
                    haveDistance = haveObstacle = false;
                    line.setLength(0);
                } else line.append((char) value);
            }
        }
    }

    private void parseLine(String text) {
        try {
            if (text.startsWith("SAFE:")) {
                String[] fields = text.substring(5).split(",", -1);
                if (fields.length != 4) return;
                int[] values = new int[4];
                for (int i = 0; i < values.length; i++) {
                    if (!fields[i].matches("0|[1-9][0-9]{0,2}")) return;
                    values[i] = Integer.parseInt(fields[i]);
                }
                listener.onSafetySettings(new SafetySettings(values[0], values[1], values[2], values[3]));
            } else if (text.startsWith("CFG:")) {
                String[] fields = text.substring(4).split(",", -1);
                if (fields.length == 2 && (fields[0].equals("0") || fields[0].equals("1"))
                        && fields[1].matches("0|[1-9][0-9]{0,2}")) {
                    int speed = Integer.parseInt(fields[1]);
                    if (speed <= 100) listener.onSettings(fields[0].equals("1"), speed);
                }
            } else if (text.startsWith("DIST:")) {
                haveDistance = haveObstacle = false;
                distance = Double.parseDouble(text.substring(5));
                haveDistance = distance == -1 || (!Double.isNaN(distance) && !Double.isInfinite(distance)
                        && distance >= 2 && distance <= MAX_SENSOR_DISTANCE_CM);
            } else if (text.startsWith("OBSTACLE:") && haveDistance) {
                String flag = text.substring(9);
                haveObstacle = flag.equals("0") || flag.equals("1");
                obstacle = flag.equals("1");
            } else if (text.startsWith("STATE:")) {
                State state = State.valueOf(text.substring(6));
                if (haveDistance && haveObstacle
                        && ((distance == -1) == (state == State.SENSOR_FAULT))
                        && (state != State.READY || !obstacle)
                        && (state != State.BLOCKED || obstacle)) {
                    listener.onTelemetry(new Snapshot(distance, obstacle, state));
                }
                haveDistance = haveObstacle = false;
            } else {
                haveDistance = haveObstacle = false;
            }
        } catch (IllegalArgumentException ignored) {
            haveDistance = haveObstacle = false;
        }
    }
}

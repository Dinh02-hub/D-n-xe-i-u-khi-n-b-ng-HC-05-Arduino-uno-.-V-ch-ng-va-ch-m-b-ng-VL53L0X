package com.arduinodrive.control;

/** V1 sends exactly one ASCII byte per command, with no delimiter. */
public final class ControlCommand {
    public final char value;
    private ControlCommand(char value) { this.value = value; }

    public static ControlCommand of(char value) {
        if ("FBLRS".indexOf(value) < 0) throw new IllegalArgumentException("Unknown command: " + value);
        return new ControlCommand(value);
    }
    public static ControlCommand stop() { return of('S'); }
    public String toAscii() { return String.valueOf(value); }
}
package com.arduinodrive.control;

/** Expected motor direction only, never telemetry or PWM: -1 / 0 / +1. */
public final class DifferentialMixer {
    private DifferentialMixer() { }
    public static MotorState mix(ControlCommand command) {
        switch (command.value) {
            case 'F': return new MotorState(1, 1);
            case 'B': return new MotorState(-1, -1);
            case 'L': return new MotorState(1, -1);
            case 'R': return new MotorState(-1, 1);
            default: return new MotorState(0, 0);
        }
    }
}

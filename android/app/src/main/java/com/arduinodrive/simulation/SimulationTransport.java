package com.arduinodrive.simulation;

import com.arduinodrive.control.ControlCommand;
import com.arduinodrive.control.DifferentialMixer;
import com.arduinodrive.control.MotorState;
import com.arduinodrive.transport.CommandTransport;

public final class SimulationTransport implements CommandTransport {
    private boolean enabled;
    private MotorState motors = new MotorState(0, 0);

    public SimulationTransport(boolean enabled) { this.enabled = enabled; }
    @Override public boolean isAvailable() { return enabled; }
    @Override public void send(ControlCommand command) {
        if (!enabled) throw new IllegalStateException("Simulation is disabled");
        motors = DifferentialMixer.mix(command);
    }
    public MotorState getMotors() { return motors; }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        motors = new MotorState(0, 0);
    }
}

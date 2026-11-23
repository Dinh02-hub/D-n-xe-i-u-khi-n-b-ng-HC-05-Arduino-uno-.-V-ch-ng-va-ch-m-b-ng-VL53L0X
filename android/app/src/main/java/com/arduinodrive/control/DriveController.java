package com.arduinodrive.control;

import com.arduinodrive.transport.CommandTransport;
import java.util.LinkedHashSet;

/** Main-thread input state: the most recently pressed, still-held direction wins. */
public final class DriveController {
    public interface Listener { void onCommand(ControlCommand command); }
    private final CommandTransport transport;
    private final Listener listener;
    private final LinkedHashSet<Character> held = new LinkedHashSet<>();

    public DriveController(CommandTransport transport, Listener listener) {
        this.transport = transport;
        this.listener = listener;
    }
    public void press(char direction) {
        if (!isAvailable() || "FBLR".indexOf(direction) < 0) return;
        if (held.add(direction)) emitCurrent(); // Key repeats must not reorder held buttons.
    }
    public void release(char direction) {
        if (held.remove(direction)) emitCurrent();
    }
    public void stop() {
        held.clear();
        emit(ControlCommand.stop());
    }
    public void heartbeat() {
        if (!isAvailable()) { held.clear(); return; }
        if (!held.isEmpty()) emitCurrent();
    }
    public char getActiveCommand() {
        char active = 'S';
        for (char direction : held) active = direction;
        return active;
    }
    public boolean isAvailable() { return transport.isAvailable(); }
    private void emitCurrent() { emit(ControlCommand.of(getActiveCommand())); }
    private void emit(ControlCommand command) {
        if (!isAvailable()) return;
        transport.send(command);
        listener.onCommand(command);
    }
}
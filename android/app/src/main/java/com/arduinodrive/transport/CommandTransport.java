package com.arduinodrive.transport;

import com.arduinodrive.control.ControlCommand;

/** Both simulation and HC-05 use the same F/B/L/R/S encoder. */
public interface CommandTransport {
    boolean isAvailable();
    void send(ControlCommand command);
}

package com.arduinodrive.control;

import org.junit.Test;
import static org.junit.Assert.*;

public class DifferentialMixerTest {
    @Test public void matchesFirmwareTwoWheelTruthTable() {
        String commands = "FBLRS";
        int[][] wheels = {{1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {0, 0}};
        for (int i = 0; i < commands.length(); i++) {
            MotorState state = DifferentialMixer.mix(ControlCommand.of(commands.charAt(i)));
            assertEquals(wheels[i][0], state.left);
            assertEquals(wheels[i][1], state.right);
        }
    }
}

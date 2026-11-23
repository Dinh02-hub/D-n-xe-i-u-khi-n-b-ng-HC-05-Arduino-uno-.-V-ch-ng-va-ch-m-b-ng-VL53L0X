package com.arduinodrive.control;

import com.arduinodrive.simulation.SimulationTransport;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class DriveControllerTest {
    private final List<String> commands = new ArrayList<>();
    private final SimulationTransport simulation = new SimulationTransport(true);
    private final DriveController controller = new DriveController(simulation,
            command -> commands.add(command.toAscii()));
    private String last() { return commands.get(commands.size() - 1); }

    @Test public void protocolIsExactlyOneByte() {
        for (char value : "FBLRS".toCharArray()) assertEquals(String.valueOf(value), ControlCommand.of(value).toAscii());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnknownCommand() {
        ControlCommand.of('X');
    }
    @Test public void newestHeldWinsAndReleasingItRestoresPrevious() {
        controller.press('F');
        controller.press('L');
        controller.press('B');
        controller.release('B');
        controller.release('F'); // Releasing an older button must preserve L.
        controller.release('L');
        assertEquals(Arrays.asList("F", "L", "B", "L", "L", "S"), commands);
    }
    @Test public void changingThrottleDoesNotClearSteering() {
        controller.press('L');
        controller.press('F');
        controller.release('F');
        controller.press('B');
        controller.release('B');
        assertEquals("L", last());
        controller.heartbeat();
        assertEquals("L", last());
        controller.release('L');
        assertEquals("S", last());
    }
    @Test public void repeatsDoNotChangePriorityAndAllDirectionsHaveHeartbeat() {
        for (char value : "FBLR".toCharArray()) {
            controller.press(value);
            int before = commands.size();
            controller.heartbeat();
            assertEquals(before + 1, commands.size());
            assertEquals(String.valueOf(value), last());
            controller.release(value);
        }
        controller.press('F'); controller.press('R'); controller.press('F');
        assertEquals('R', controller.getActiveCommand());
    }
    @Test public void stopClearsEveryHoldAndRequiresFreshPress() {
        controller.press('F'); controller.press('L'); controller.stop();
        int count = commands.size();
        controller.heartbeat(); controller.release('L'); controller.release('F');
        assertEquals(count, commands.size());
        assertEquals('S', controller.getActiveCommand());
        controller.press('R');
        assertEquals("R", last());
    }
    @Test public void unavailableTransportCannotFakeDeliveryOrResumeOldHolds() {
        controller.press('F');
        simulation.setEnabled(false);
        int count = commands.size();
        controller.press('L'); controller.heartbeat();
        assertEquals(count, commands.size());
        assertEquals('S', controller.getActiveCommand());
        simulation.setEnabled(true);
        controller.heartbeat(); controller.release('F');
        assertEquals(count, commands.size());
        controller.press('B');
        assertEquals("B", last());
    }
    @Test public void allPressAndReleaseOrdersObeyLastHeldRule() {
        for (String press : permutations("FBLR")) {
            for (String release : permutations("FBLR")) {
                controller.stop();
                List<Character> held = new ArrayList<>();
                for (char c : press.toCharArray()) {
                    held.add(c); controller.press(c);
                    assertEquals(c, controller.getActiveCommand());
                }
                for (char c : release.toCharArray()) {
                    held.remove(Character.valueOf(c)); controller.release(c);
                    char expected = held.isEmpty() ? 'S' : held.get(held.size() - 1);
                    assertEquals(expected, controller.getActiveCommand());
                    assertEquals(String.valueOf(expected), last());
                }
            }
        }
    }
    private List<String> permutations(String source) {
        List<String> result = new ArrayList<>();
        if (source.isEmpty()) result.add("");
        for (int i = 0; i < source.length(); i++)
            for (String tail : permutations(source.substring(0, i) + source.substring(i + 1)))
                result.add(source.charAt(i) + tail);
        return result;
    }
}
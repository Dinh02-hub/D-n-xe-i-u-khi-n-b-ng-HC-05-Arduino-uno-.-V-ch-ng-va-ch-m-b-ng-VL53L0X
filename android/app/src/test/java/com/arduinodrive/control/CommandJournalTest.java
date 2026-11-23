package com.arduinodrive.control;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CommandJournalTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void tailShowsLatestThreeButFileContainsEveryCommand() throws Exception {
        File root = temp.newFolder();
        CommandJournal journal = new CommandJournal(root);
        for (int i = 0; i < 20; i++) journal.append(i * 100L, ControlCommand.of('F'));
        journal.append(2000, ControlCommand.stop());
        journal.close();
        File directory = new File(root, "command-logs");
        long deadline = System.currentTimeMillis() + 3000;
        while (directory.listFiles().length == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        File[] logs = directory.listFiles();
        assertNotNull(logs);
        assertEquals(1, logs.length);
        while (Files.readAllLines(logs[0].toPath()).size() < 21 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(21, Files.readAllLines(logs[0].toPath()).size());
        assertEquals(21, journal.getCount());
        assertEquals(3, journal.getTail().split("\n").length);
        assertTrue(journal.getTail().startsWith("00:02.000  S"));
        assertFalse(journal.hasWriteFailed());
    }
    @Test public void onlyTenMostRecentSessionsAreRetained() throws Exception {
        File root = temp.newFolder();
        File directory = new File(root, "command-logs");
        assertTrue(directory.mkdir());
        for (int i = 0; i < 15; i++) {
            File old = new File(directory, "old-" + i + ".txt");
            assertTrue(old.createNewFile()); assertTrue(old.setLastModified(1000L + i));
        }
        CommandJournal journal = new CommandJournal(root);
        journal.append(0, ControlCommand.stop()); journal.close();
        long deadline = System.currentTimeMillis() + 3000;
        while (directory.listFiles().length < 10 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(10, directory.listFiles().length);
        assertFalse(new File(directory, "old-5.txt").exists());
        assertTrue(new File(directory, "old-6.txt").exists());
    }
}

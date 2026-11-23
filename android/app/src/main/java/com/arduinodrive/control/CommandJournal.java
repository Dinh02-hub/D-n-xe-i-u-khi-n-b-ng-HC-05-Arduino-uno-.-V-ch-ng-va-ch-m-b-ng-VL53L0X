package com.arduinodrive.control;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small UI tail, full ASCII journal on a serial background writer. Retain 10 sessions. */
public final class CommandJournal {
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final File file;
    private int count;
    private volatile boolean writeFailed;

    public CommandJournal(File filesDir) {
        File directory = new File(filesDir, "command-logs");
        if (!directory.isDirectory() && !directory.mkdirs()) writeFailed = true;
        File[] old = directory.listFiles((dir, name) -> name.endsWith(".txt"));
        if (old != null) {
            Arrays.sort(old, (first, second) -> Long.compare(first.lastModified(), second.lastModified()));
            for (int i = 0; i <= old.length - 10; i++) {
                if (!old[i].delete()) writeFailed = true;
            }
        }
        file = new File(directory, "session-" + System.currentTimeMillis() + ".txt");
    }
    public void append(long elapsedMillis, ControlCommand command) {
        count++;
        String line = String.format(Locale.US, "%02d:%02d.%03d  %s",
                elapsedMillis / 60000, elapsedMillis / 1000 % 60, elapsedMillis % 1000,
                command.toAscii().trim());
        tail.addFirst(line);
        while (tail.size() > 3) tail.removeLast();
        writer.execute(() -> {
            try (FileOutputStream stream = new FileOutputStream(file, true)) {
                stream.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            } catch (IOException error) { writeFailed = true; }
        });
    }
    public String getTail() {
        StringBuilder lines = new StringBuilder();
        for (String line : tail) {
            if (lines.length() > 0) lines.append('\n');
            lines.append(line);
        }
        return lines.toString();
    }
    public int getCount() { return count; }
    public boolean hasWriteFailed() { return writeFailed; }
    public void close() { writer.shutdown(); }
}

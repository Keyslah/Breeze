package com.aaron.breeze;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable diagnostic observations; a cloud match is not proof of physical IR reception. */
final class DriftJournal {
    private static final Pattern INCIDENT_NAME = Pattern.compile("incident-[0-9]{19}-[0-9a-f-]{36}\\.jsonl");
    private final File directory;
    private final File current;
    private final File incidentDirectory;
    private long lastIncidentStamp;

    DriftJournal(File directory) throws IOException, JSONException {
        this.directory = directory.getCanonicalFile();
        current = new File(this.directory, "current.jsonl");
        incidentDirectory = new File(this.directory, "incidents");
        Files.createDirectories(this.directory.toPath());
        Files.createDirectories(incidentDirectory.toPath());
        for (File incident : incidents()) {
            long stamp = Long.parseLong(incident.getName().substring(9, 28));
            lastIncidentStamp = Math.max(lastIncidentStamp, stamp);
        }
        ensureCurrent();
    }

    synchronized void append(JSONObject event) throws IOException, JSONException {
        ensureCurrent();
        appendLine(event);
    }

    /** Only the current, explicitly disposable window is replaced. Saved incidents are untouched. */
    synchronized void matched(JSONObject checkpoint) throws IOException, JSONException {
        String contents = line(windowHeader(true)) + line(checkpoint);
        ensureCurrent();
        recoverPendingArchives();
        replaceCurrent(contents);
    }

    /** The returned archive is complete and flushed before a caller can send a correction. */
    synchronized String mismatch(JSONObject observation) throws IOException, JSONException {
        if (observation == null) throw new JSONException("Mismatch observation is required.");
        ensureCurrent();
        long stamp = Math.max(System.currentTimeMillis(), lastIncidentStamp + 1);
        lastIncidentStamp = stamp;
        String name = String.format(Locale.ROOT, "incident-%019d-%s.jsonl", stamp, UUID.randomUUID());
        JSONObject marker = new JSONObject(observation.toString());
        marker.put("type", "mismatch").put("incident", name);
        appendLine(marker);
        archiveCurrent(name);
        return name;
    }

    private void archiveCurrent(String name) throws IOException {
        Files.createDirectories(incidentDirectory.toPath());
        Path destination = new File(incidentDirectory, name).toPath();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing to replace an existing drift incident: " + name);
        Path temporary = Files.createTempFile(incidentDirectory.toPath(), ".incident-", ".tmp");
        try {
            try (FileInputStream input = new FileInputStream(current);
                 FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                output.getFD().sync();
            }
            publish(temporary, destination, false);
        } finally {
            // This unpublished file was created solely for this operation.
            Files.deleteIfExists(temporary);
        }
    }

    /** A persisted marker is also the recovery record if publication failed or the app stopped. */
    private void recoverPendingArchives() throws IOException, JSONException {
        LinkedHashSet<String> pending = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(current.toPath(), StandardCharsets.UTF_8)) {
            String text;
            while ((text = reader.readLine()) != null) {
                if (text.trim().isEmpty()) continue;
                JSONObject event;
                try { event = new JSONObject(text); }
                catch (JSONException invalid) {
                    throw new IOException("Incomplete drift journal retained; cannot discard the current window.", invalid);
                }
                if (!"mismatch".equals(event.optString("type"))) continue;
                String name = event.optString("incident", "");
                if (!INCIDENT_NAME.matcher(name).matches())
                    throw new IOException("Unidentified drift incident retained; cannot discard the current window.");
                Path archive = new File(incidentDirectory, name).toPath();
                if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) == 0)
                    pending.add(name);
            }
        }
        for (String name : pending) {
            // Check before appending recovery metadata so an unavailable directory causes no mutation.
            Files.createDirectories(incidentDirectory.toPath());
            if (Files.exists(new File(incidentDirectory, name).toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Incomplete drift archive retained; cannot discard the current window: " + name);
            appendLine(new JSONObject().put("type", "incident_archive_recovery")
                    .put("incident", name).put("recoveredAt", System.currentTimeMillis()));
            // Save the whole current window, including anything recorded after the failed archive.
            archiveCurrent(name);
        }
    }

    synchronized List<File> incidents() throws IOException {
        File[] files = incidentDirectory.listFiles();
        if (files == null) throw new IOException("Could not read drift incident directory.");
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (INCIDENT_NAME.matcher(file.getName()).matches()
                    && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) result.add(file);
        }
        result.sort(Comparator.comparing(File::getName).reversed());
        return result;
    }

    File currentFile() { return current; }

    synchronized String readIncident(String name) throws IOException {
        if (name == null || !INCIDENT_NAME.matcher(name).matches())
            throw new IOException("Invalid drift incident filename.");
        Path path = new File(incidentDirectory, name).toPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Drift incident is unavailable.");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private void ensureCurrent() throws IOException, JSONException {
        if (!current.exists() || current.length() == 0) replaceCurrent(line(windowHeader(false)));
    }

    private static JSONObject windowHeader(boolean baselineConfirmed) throws JSONException {
        return new JSONObject().put("type", "window_open")
                .put("baselineConfirmed", baselineConfirmed)
                .put("openedAt", System.currentTimeMillis()).put("windowId", UUID.randomUUID().toString());
    }

    private static String line(JSONObject event) throws JSONException {
        if (event == null) throw new JSONException("Diagnostic event is required.");
        return event.toString() + "\n";
    }

    private void appendLine(JSONObject event) throws IOException, JSONException {
        byte[] bytes = line(event).getBytes(StandardCharsets.UTF_8);
        boolean separate = false;
        if (current.length() > 0) {
            try (RandomAccessFile input = new RandomAccessFile(current, "r")) {
                input.seek(input.length() - 1);
                separate = input.read() != '\n';
            }
        }
        try (FileOutputStream output = new FileOutputStream(current, true)) {
            // Retain a crash-written tail byte for byte, keeping later events on their own lines.
            if (separate) output.write('\n');
            output.write(bytes);
            output.flush();
            // The temporary window survives process restarts without forcing a disk flush on
            // the BLE handler for every packet. Checkpoints and published archives are synced.
        }
    }

    private void replaceCurrent(String contents) throws IOException {
        Path temporary = Files.createTempFile(directory.toPath(), ".window-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(contents.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            publish(temporary, current.toPath(), true);
        } finally {
            // This unpublished file was created solely for this operation.
            Files.deleteIfExists(temporary);
        }
    }

    private static void publish(Path temporary, Path destination, boolean replace) throws IOException {
        try {
            if (replace) Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (replace) Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, destination);
        }
    }
}

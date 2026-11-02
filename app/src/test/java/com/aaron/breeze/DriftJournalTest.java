package com.aaron.breeze;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class DriftJournalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static JSONObject event(String type, int value) throws Exception {
        return new JSONObject().put("type", type).put("value", value);
    }

    private static List<JSONObject> events(String contents) throws Exception {
        List<JSONObject> events = new ArrayList<>();
        for (String line : contents.split("\n")) if (!line.isEmpty()) events.add(new JSONObject(line));
        return events;
    }

    private static String current(DriftJournal journal) throws Exception {
        return new String(Files.readAllBytes(journal.currentFile().toPath()), StandardCharsets.UTF_8);
    }

    @Test public void startupIsExplicitlyUnanchoredAndRestartRetainsTheWindow() throws Exception {
        File directory = temporary.newFolder();
        DriftJournal journal = new DriftJournal(directory);
        journal.append(event("controller", 4));
        List<JSONObject> lines = events(current(journal));
        assertEquals("window_open", lines.get(0).getString("type"));
        assertFalse(lines.get(0).getBoolean("baselineConfirmed"));
        assertEquals("controller", lines.get(1).getString("type"));
        String preserved = current(journal);
        DriftJournal restarted = new DriftJournal(directory);
        assertEquals(preserved, current(restarted));
        restarted.append(event("controller", 5));
        assertEquals(3, events(current(restarted)).size());
    }

    @Test public void matchingCheckpointDiscardsOnlyTheTemporaryWindow() throws Exception {
        DriftJournal journal = new DriftJournal(temporary.newFolder());
        journal.matched(event("matched", 3));
        journal.append(event("controller", 4));
        String incident = journal.mismatch(event("dreo", 3));
        String archived = journal.readIncident(incident);
        journal.matched(event("matched", 4));
        List<JSONObject> lines = events(current(journal));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).getBoolean("baselineConfirmed"));
        assertEquals("matched", lines.get(1).getString("type"));
        assertEquals(4, lines.get(1).getInt("value"));
        assertEquals(archived, journal.readIncident(incident));
        assertEquals(1, journal.incidents().size());
    }

    @Test public void mismatchArchivesTheWholeWindowBeforeReturningAndSurvivesRestart() throws Exception {
        File directory = temporary.newFolder();
        DriftJournal journal = new DriftJournal(directory);
        journal.matched(event("matched", 3).put("sendCounter", 100));
        journal.append(event("controller", 4).put("sendCounter", 101).put("command", "Speed +"));
        JSONObject observation = event("dreo_poll", 3);
        String incident = journal.mismatch(observation);
        assertEquals("dreo_poll", observation.getString("type"));
        assertFalse(observation.has("incident"));
        String archived = journal.readIncident(incident);
        assertEquals(current(journal), archived);
        List<JSONObject> lines = events(archived);
        assertEquals(4, lines.size());
        assertEquals(100, lines.get(1).getInt("sendCounter"));
        assertEquals("Speed +", lines.get(2).getString("command"));
        assertEquals(101, lines.get(2).getInt("sendCounter"));
        assertEquals("mismatch", lines.get(3).getString("type"));
        assertEquals(incident, lines.get(3).getString("incident"));
        DriftJournal restarted = new DriftJournal(directory);
        assertEquals(archived, restarted.readIncident(incident));
        assertEquals(archived, current(restarted));
        restarted.matched(event("matched", 3));
        assertEquals(archived, restarted.readIncident(incident));
    }

    @Test public void completeWindowLargerThanSixteenKilobytesIsRetained() throws Exception {
        DriftJournal journal = new DriftJournal(temporary.newFolder());
        journal.matched(event("matched", 1));
        String detail = "x".repeat(1024);
        for (int i = 0; i < 40; i++) journal.append(event("controller", i).put("detail", detail));
        String archived = journal.readIncident(journal.mismatch(event("dreo", 2)));
        assertTrue(archived.length() > 16000);
        List<JSONObject> lines = events(archived);
        assertEquals(43, lines.size());
        for (int i = 0; i < 40; i++) {
            assertEquals(i, lines.get(i + 2).getInt("value"));
            assertEquals(detail, lines.get(i + 2).getString("detail"));
        }
    }

    @Test public void failedOrRejectedPollDoesNotDiscardEarlierEvents() throws Exception {
        DriftJournal journal = new DriftJournal(temporary.newFolder());
        journal.matched(event("matched", 3));
        journal.append(event("controller", 4));
        journal.append(new JSONObject().put("type", "dreo_poll").put("outcome", "network_failure"));
        journal.append(new JSONObject().put("type", "dreo_poll").put("outcome", "generation_changed"));
        List<JSONObject> lines = events(journal.readIncident(journal.mismatch(event("dreo", 3))));
        assertEquals(6, lines.size());
        assertEquals("matched", lines.get(1).getString("type"));
        assertEquals("controller", lines.get(2).getString("type"));
        assertEquals("network_failure", lines.get(3).getString("outcome"));
        assertEquals("generation_changed", lines.get(4).getString("outcome"));
    }

    @Test public void repeatedMismatchesHaveUniqueArchivesAndPreserveTheOriginalCheckpoint() throws Exception {
        File directory = temporary.newFolder();
        DriftJournal journal = new DriftJournal(directory);
        journal.matched(event("matched", 3));
        String first = journal.mismatch(event("dreo", 4));
        String firstContents = journal.readIncident(first);
        journal.append(event("controller", 5));
        String second = journal.mismatch(event("dreo", 4));
        assertNotEquals(first, second);
        assertEquals(firstContents, journal.readIncident(first));
        List<JSONObject> secondLines = events(journal.readIncident(second));
        assertEquals(5, secondLines.size());
        assertEquals("matched", secondLines.get(1).getString("type"));
        assertEquals(first, secondLines.get(2).getString("incident"));
        assertEquals(second, secondLines.get(4).getString("incident"));
        assertEquals(second, journal.incidents().get(0).getName());
        DriftJournal restarted = new DriftJournal(directory);
        String third = restarted.mismatch(event("dreo", 4));
        assertEquals(third, restarted.incidents().get(0).getName());
        assertEquals(second, restarted.incidents().get(1).getName());
        assertEquals(first, restarted.incidents().get(2).getName());
    }

    @Test public void incidentReadRejectsPathsAndTemporaryWindowNames() throws Exception {
        DriftJournal journal = new DriftJournal(temporary.newFolder());
        assertThrows(IOException.class, () -> journal.readIncident("../current.jsonl"));
        assertThrows(IOException.class, () -> journal.readIncident("current.jsonl"));
        assertThrows(IOException.class, () -> journal.readIncident(journal.currentFile().getAbsolutePath()));
        assertThrows(IOException.class, () -> journal.readIncident(null));
    }

    @Test public void failedArchiveIsRetainedAndRecoveredAfterRestartBeforeMatchingRollover() throws Exception {
        File directory = temporary.newFolder();
        DriftJournal journal = new DriftJournal(directory);
        journal.matched(event("matched", 3));
        journal.append(event("controller", 4));
        Path incidents = new File(directory, "incidents").toPath();
        Path savedDirectory = new File(directory, "saved-incidents").toPath();
        Files.move(incidents, savedDirectory);
        Files.write(incidents, new byte[]{1}); // Test-owned blocker makes archive creation fail on every OS.
        assertThrows(IOException.class, () -> journal.mismatch(event("dreo", 3)));
        String preserved = current(journal);
        List<JSONObject> failedLines = events(preserved);
        String name = failedLines.get(failedLines.size() - 1).getString("incident");
        assertThrows(IOException.class, () -> journal.matched(event("matched", 4)));
        assertEquals(preserved, current(journal));

        Files.delete(incidents); // Only the temporary blocker created by this test is removed.
        Files.move(savedDirectory, incidents);
        DriftJournal restarted = new DriftJournal(directory);
        assertEquals(preserved, current(restarted));
        restarted.append(event("controller", 5));
        restarted.matched(event("matched", 5));
        String recovered = restarted.readIncident(name);
        assertTrue(recovered.startsWith(preserved));
        List<JSONObject> recoveredLines = events(recovered);
        assertEquals("controller", recoveredLines.get(recoveredLines.size() - 2).getString("type"));
        assertEquals(5, recoveredLines.get(recoveredLines.size() - 2).getInt("value"));
        assertEquals("incident_archive_recovery", recoveredLines.get(recoveredLines.size() - 1).getString("type"));
        assertEquals(2, events(current(restarted)).size());
        assertEquals(5, events(current(restarted)).get(1).getInt("value"));
    }

    @Test public void crashPartialLineIsPreservedAndPreventsSilentRollover() throws Exception {
        File directory = temporary.newFolder();
        DriftJournal journal = new DriftJournal(directory);
        journal.matched(event("matched", 3));
        String partial = "{\"type\":\"controller\",\"value\":";
        Files.write(journal.currentFile().toPath(), partial.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        String preserved = current(journal);
        DriftJournal restarted = new DriftJournal(directory);
        assertEquals(preserved, current(restarted));
        restarted.append(event("controller", 4));
        String continued = current(restarted);
        assertTrue(continued.startsWith(preserved + "\n"));
        assertTrue(continued.endsWith(event("controller", 4).toString() + "\n"));
        assertThrows(IOException.class, () -> restarted.matched(event("matched", 4)));
        assertEquals(continued, current(restarted));
    }
}

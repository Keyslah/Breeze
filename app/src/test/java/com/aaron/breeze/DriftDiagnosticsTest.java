package com.aaron.breeze;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class DriftDiagnosticsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicLong wall = new AtomicLong(1791829831000L);
    private final AtomicLong elapsed = new AtomicLong(1000L);

    private DriftDiagnostics diagnostics() throws Exception {
        return new DriftDiagnostics(temporary.newFolder(), wall::get, elapsed::get);
    }

    private static byte[] fanPacket(int level, int counter, int lastSent) {
        ByteBuffer packet = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0, (byte) 5).put(1, (byte) (4 | 16)).put(4, (byte) level);
        packet.put(6, (byte) 255).put(14, (byte) 255).put(15, (byte) lastSent);
        packet.putShort(16, (short) counter).put(19, (byte) 7);
        return packet.array();
    }

    private Protocol.FanState fan(int level, int counter, int lastSent) {
        return Protocol.decodeFan(fanPacket(level, counter, lastSent), wall.get());
    }

    private static byte[] uptimePacket(long seconds) {
        ByteBuffer packet = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0, (byte) 1).putInt(12, (int) seconds);
        return packet.array();
    }

    private DreoReport report(int speed, boolean power) throws Exception {
        long stamp = wall.get() / 1000 - 10;
        JSONObject mixed = new JSONObject()
                .put("windlevel", field(speed, stamp)).put("poweron", field(power, stamp))
                .put("mode", field(1, stamp)).put("connected", field(true, stamp));
        return DreoReport.parse(new JSONObject().put("code", 0).put("data",
                new JSONObject().put("sn", "diagnostic-fixture").put("mixed", mixed)),
                "diagnostic-fixture", wall.get() - 100, wall.get());
    }

    private static JSONObject field(Object value, long stamp) throws Exception {
        return new JSONObject().put("state", value).put("timestamp", stamp);
    }

    private static List<JSONObject> entries(String contents) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (String line : contents.split("\n")) if (!line.isEmpty()) result.add(new JSONObject(line));
        return result;
    }

    private static String current(DriftDiagnostics diagnostics) throws Exception {
        return new String(Files.readAllBytes(diagnostics.journal.currentFile().toPath()), StandardCharsets.UTF_8);
    }

    private static List<JSONObject> ofType(String contents, String type) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject entry : entries(contents)) if (type.equals(entry.getString("type"))) result.add(entry);
        return result;
    }

    @Test public void observedNineToHIsArchivedBeforeAnyCorrectionCommand() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.packet(fanPacket(9, 40, 2));
        diagnostics.compare(report(9, true), fan(9, 40, 2), new JSONObject());
        wall.addAndGet(1000); elapsed.addAndGet(1000);
        diagnostics.packet(fanPacket(10, 41, 1));
        DreoReport reported = report(9, true);
        diagnostics.compare(reported, fan(10, 41, 1), new JSONObject().put("phase", "before-correction"));

        assertEquals(1, diagnostics.journal.incidents().size());
        String name = diagnostics.journal.incidents().get(0).getName();
        String archived = diagnostics.journal.readIncident(name);
        JSONObject step = ofType(archived, "controller_5").get(0);
        assertEquals(1, step.getJSONObject("data").getInt("observedSendDelta"));
        assertEquals(9, step.getJSONObject("data").getInt("previousEstimate"));
        assertEquals(10, step.getJSONObject("data").getJSONObject("fan").getInt("estimate"));
        assertTrue(step.getString("description").contains("Speed +"));
        assertTrue(step.getString("description").contains("9 → H"));
        assertEquals(wall.get(), step.getLong("at"));
        assertEquals(elapsed.get(), step.getLong("elapsedMs"));
        JSONObject mismatch = ofType(archived, "mismatch").get(0).getJSONObject("data");
        assertEquals(10, mismatch.getInt("estimate"));
        assertEquals(9, mismatch.getInt("reportedLevel"));
        assertEquals(41, mismatch.getInt("sendCounter"));
        assertEquals(reported.speedTimestamp, mismatch.getJSONObject("report").getLong("speedChangedAt"));
        assertEquals("before-correction", mismatch.getJSONObject("context").getString("phase"));
        assertTrue(ofType(archived, "command_submitted").isEmpty());

        diagnostics.command("submitted", Protocol.fan(Protocol.SYNC_SPEED, 9, 1, 42), "");
        assertEquals(archived, diagnostics.journal.readIncident(name));
        assertEquals(1, ofType(current(diagnostics), "command_submitted").size());
    }

    @Test public void matchingCheckStartsANewWindowButKeepsMismatchArchive() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.packet(fanPacket(4, 100, 1));
        diagnostics.compare(report(3, true), fan(4, 100, 1), new JSONObject());
        String name = diagnostics.journal.incidents().get(0).getName();
        String archived = diagnostics.journal.readIncident(name);
        diagnostics.event("after_correction", "temporary later observation", new JSONObject());
        diagnostics.compare(report(3, true), fan(3, 100, 1), new JSONObject());

        List<JSONObject> window = entries(current(diagnostics));
        assertEquals(2, window.size());
        assertEquals("window_open", window.get(0).getString("type"));
        assertTrue(window.get(0).getBoolean("baselineConfirmed"));
        assertEquals("matching_checkpoint", window.get(1).getString("type"));
        assertEquals(3, window.get(1).getJSONObject("data").getInt("estimate"));
        assertEquals(1, diagnostics.journal.incidents().size());
        assertEquals(archived, diagnostics.journal.readIncident(name));
    }

    @Test public void counterGapKeepsOnlyTheLastObservedCommandAndMarksUnknownSends() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.packet(fanPacket(3, 20, 1));
        diagnostics.packet(fanPacket(4, 23, 2));
        String contents = current(diagnostics);
        List<JSONObject> packets = ofType(contents, "controller_5");
        assertEquals(2, packets.size());
        JSONObject gap = packets.get(1).getJSONObject("data");
        assertEquals(3, gap.getInt("observedSendDelta"));
        assertEquals(2, gap.getInt("unidentifiedSends"));
        assertEquals("Speed -", gap.getJSONObject("fan").getString("lastSent"));
        assertTrue(packets.get(1).getString("description").contains("only last command known"));
        assertTrue(ofType(contents, "command_submitted").isEmpty());
    }

    @Test public void unsignedCounterWrapIsOneObservedSend() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.packet(fanPacket(8, 65535, 1));
        diagnostics.packet(fanPacket(9, 0, 1));
        JSONObject wrapped = ofType(current(diagnostics), "controller_5").get(1).getJSONObject("data");
        assertEquals(1, wrapped.getInt("observedSendDelta"));
        assertFalse(wrapped.has("counterContinuity"));
        assertFalse(wrapped.has("unidentifiedSends"));
    }

    @Test public void backwardCounterIsAmbiguousInsteadOfThousandsOfInventedCommands() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.packet(fanPacket(9, 200, 1));
        diagnostics.packet(fanPacket(3, 4, 2));
        JSONObject backwards = ofType(current(diagnostics), "controller_5").get(1).getJSONObject("data");
        assertEquals("reset_or_unknown", backwards.getString("counterContinuity"));
        assertFalse(backwards.has("observedSendDelta"));
        assertFalse(backwards.has("unidentifiedSends"));
    }

    @Test public void connectionBreakPreventsComparingCountersAcrossTheGap() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.packet(fanPacket(3, 20, 1));
        diagnostics.continuityBreak("Bluetooth disconnected");
        diagnostics.packet(fanPacket(7, 24, 1));
        String contents = current(diagnostics);
        assertEquals(1, ofType(contents, "continuity_break").size());
        JSONObject after = ofType(contents, "controller_5").get(1);
        assertFalse(after.getJSONObject("data").has("previousCounter"));
        assertFalse(after.getJSONObject("data").has("observedSendDelta"));
        assertTrue(after.getString("description").contains("prior commands unknown"));
    }

    @Test public void controllerUptimeResetBreaksCounterContinuity() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.packet(uptimePacket(800));
        diagnostics.packet(fanPacket(7, 50, 1));
        diagnostics.packet(uptimePacket(2));
        diagnostics.packet(fanPacket(7, 51, 1));
        String contents = current(diagnostics);
        List<JSONObject> breaks = ofType(contents, "continuity_break");
        assertEquals(1, breaks.size());
        assertTrue(breaks.get(0).getString("description").contains("uptime restarted"));
        JSONObject after = ofType(contents, "controller_5").get(1).getJSONObject("data");
        assertFalse(after.has("observedSendDelta"));
        assertEquals(2, ofType(contents, "controller_1").get(1).getJSONObject("data").getLong("uptimeSeconds"));
    }

    @Test public void logicPacketPreservesBoostWindowAndTheEncodedControllerState() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        ByteBuffer packet = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0, (byte) 9).put(1, (byte) (1 | 2 | 4 | 64)).put(3, (byte) 10);
        packet.putShort(4, (short) 9750).putShort(6, (short) 7300).putShort(8, (short) 73);
        packet.putShort(10, (short) 42).put(13, (byte) 7).putShort(16, (short) 850);
        packet.put(18, (byte) -2).put(19, (byte) 1);
        diagnostics.packet(packet.array());
        JSONObject event = ofType(current(diagnostics), "controller_9").get(0);
        JSONObject data = event.getJSONObject("data"), logic = data.getJSONObject("logic");
        assertTrue(logic.getBoolean("automatic"));
        assertTrue(logic.getBoolean("boosting"));
        assertEquals(73, logic.getInt("boostSeconds"));
        assertEquals(10, logic.getInt("target"));
        assertEquals(-2, logic.getInt("windowAdjustment"));
        assertEquals(42, logic.getInt("revision"));
        assertEquals(97.5, logic.getDouble("averageBodyF"), 0);
        assertEquals(73.0, logic.getDouble("roomF"), 0);
        assertEquals(8.5, logic.getDouble("score"), 0);
        String raw = data.getString("packet");
        assertEquals(40, raw.length());
        for (int i = 0; i < packet.array().length; i++)
            assertEquals(packet.array()[i] & 255, Integer.parseInt(raw.substring(i * 2, i * 2 + 2), 16));
    }

    @Test public void rejectedPollRetainsTheActiveWindowAndDoesNotMakeAnIncident() throws Exception {
        DriftDiagnostics diagnostics = diagnostics();
        diagnostics.compare(report(3, true), fan(3, 20, 1), new JSONObject());
        diagnostics.packet(fanPacket(4, 21, 1));
        String before = current(diagnostics);
        diagnostics.poll(report(3, true), false, "generation changed", new JSONObject());
        String after = current(diagnostics);
        assertTrue(after.startsWith(before));
        assertEquals(1, ofType(after, "matching_checkpoint").size());
        assertEquals(1, ofType(after, "controller_5").size());
        assertFalse(ofType(after, "dreo_check").get(0).getJSONObject("data").getBoolean("accepted"));
        assertTrue(diagnostics.journal.incidents().isEmpty());
    }

    @Test public void restartKeepsTheWindowAndSavedIncidentWithoutInventingCounterContinuity() throws Exception {
        File directory = temporary.newFolder();
        DriftDiagnostics original = new DriftDiagnostics(directory, wall::get, elapsed::get);
        original.compare(report(3, true), fan(3, 20, 1), new JSONObject());
        original.packet(fanPacket(4, 21, 1));
        original.compare(report(3, true), fan(4, 21, 1), new JSONObject());
        String name = original.journal.incidents().get(0).getName();
        String archived = original.journal.readIncident(name);
        String window = current(original);

        DriftDiagnostics restarted = new DriftDiagnostics(directory, wall::get, elapsed::get);
        assertTrue(current(restarted).startsWith(window));
        assertEquals(archived, restarted.journal.readIncident(name));
        restarted.packet(fanPacket(5, 22, 1));
        List<JSONObject> packets = ofType(current(restarted), "controller_5");
        assertEquals(2, packets.size());
        assertFalse(packets.get(1).getJSONObject("data").has("observedSendDelta"));
        assertTrue(restarted.status().contains("savedIncidents=1"));
    }
}

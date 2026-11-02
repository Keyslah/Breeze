package com.aaron.breeze;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class DreoReportTest {
    private static final long NOW = 1791694000000L;
    private static DreoReport report(String mixed) throws Exception {
        return DreoReport.parse(new JSONObject("{\"code\":0,\"data\":{\"sn\":\"fan-565s\",\"mixed\":" + mixed + "}}"), "fan-565s", NOW - 1000, NOW);
    }

    @Test public void exactModelSnapshotRetainsReportedSpeedWhenPowerIsOff() throws Exception {
        // Values and per-field timestamps from the upstream HAF008S owner-diagnostics fixture.
        DreoReport value = report("{\"poweron\":{\"state\":false,\"timestamp\":1779310641},"
                + "\"windlevel\":{\"state\":3,\"timestamp\":1779308556},"
                + "\"mode\":{\"state\":1,\"timestamp\":1779308554},"
                + "\"oscmode\":{\"state\":0,\"timestamp\":1779310898},"
                + "\"connected\":{\"state\":true,\"timestamp\":1779308283}}");
        assertEquals(Integer.valueOf(3), value.speed);
        assertEquals(Boolean.FALSE, value.power);
        assertEquals(Integer.valueOf(1), value.mode);
        assertEquals(Integer.valueOf(0), value.oscillation);
        assertEquals(Boolean.TRUE, value.connected);
        assertEquals(1779308556000L, value.speedTimestamp);
        assertEquals(1779310641000L, value.powerTimestamp);
        assertEquals(1779308554000L, value.modeTimestamp);
        assertEquals(1779310898000L, value.oscillationTimestamp);
        assertEquals(1779308283000L, value.connectedTimestamp);
        assertEquals(NOW - 1000, value.requestStartedAtMs);
        assertEquals(NOW, value.receivedAtMs);
    }

    @Test public void successfulEmptyResponseInventsNoState() throws Exception {
        DreoReport value = report("{}");
        assertNull(value.speed); assertNull(value.power); assertNull(value.mode);
        assertNull(value.oscillation); assertNull(value.connected);
        assertEquals(0, value.speedTimestamp);
    }

    @Test public void stringsFloatsAndNumericBooleansAreNotCoerced() throws Exception {
        DreoReport value = report("{\"windlevel\":{\"state\":\"5\"},\"poweron\":{\"state\":1},"
                + "\"mode\":{\"state\":1.5},\"oscmode\":{\"state\":true},\"connected\":{\"state\":\"true\"}}");
        assertNull(value.speed); assertNull(value.power); assertNull(value.mode);
        assertNull(value.oscillation); assertNull(value.connected);
    }

    @Test public void invalidSpeedAndOscillationDoNotOverwriteAnEstimate() throws Exception {
        for (int speed : new int[]{-1, 0, 10, 255}) assertNull(report("{\"windlevel\":{\"state\":" + speed + "}}").speed);
        for (int osc : new int[]{-1, 4, 255}) assertNull(report("{\"oscmode\":{\"state\":" + osc + "}}").oscillation);
        assertEquals(Integer.valueOf(1), report("{\"windlevel\":{\"state\":1}}").speed);
        assertEquals(Integer.valueOf(9), report("{\"windlevel\":{\"state\":9}}").speed);
        assertEquals(Integer.valueOf(3), report("{\"oscmode\":{\"state\":3}}").oscillation);
    }

    @Test public void unfamiliarModeIsRetainedAsANumberWithoutInventingItsMeaning() throws Exception {
        assertEquals(Integer.valueOf(6), report("{\"mode\":{\"state\":6}}").mode);
        assertNull(report("{\"mode\":{\"state\":-1}}").mode);
    }

    @Test public void missingNullMalformedAndFutureTimestampsAreUnknown() throws Exception {
        for (String stamp : new String[]{"null", "\"1779308556\"", "1.5", "0", "-1", "1791694000000", "9223372036854775807", "1791694301"}) {
            DreoReport value = report("{\"windlevel\":{\"state\":5,\"timestamp\":" + stamp + "}}");
            assertEquals(Integer.valueOf(5), value.speed);
            assertEquals("Reject stamp " + stamp, 0, value.speedTimestamp);
        }
        assertEquals(0, report("{\"windlevel\":{\"state\":5}}").speedTimestamp);
    }

    @Test public void fieldTimestampCannotTurnMalformedStateIntoConfirmation() throws Exception {
        DreoReport value = report("{\"windlevel\":{\"state\":null,\"timestamp\":1779308556}}");
        assertNull(value.speed);
        assertEquals(0, value.speedTimestamp);
    }

    @Test public void rejectOtherDeviceMissingIdentityAndApiErrors() throws Exception {
        for (String json : new String[]{
                "{\"code\":0,\"data\":{\"sn\":\"another-fan\",\"mixed\":{}}}",
                "{\"code\":0,\"data\":{\"mixed\":{}}}",
                "{\"code\":0,\"data\":{\"sn\":\"fan-565s\"}}",
                "{\"code\":500003,\"data\":{\"sn\":\"fan-565s\",\"mixed\":{}}}",
                "{\"code\":\"0\",\"data\":{\"sn\":\"fan-565s\",\"mixed\":{}}}"}) {
            try { DreoReport.parse(new JSONObject(json), "fan-565s", NOW - 1000, NOW); fail("Invalid envelope accepted"); }
            catch (IOException expected) { assertEquals("Dreo returned an invalid device state.", expected.getMessage()); }
        }
    }
}

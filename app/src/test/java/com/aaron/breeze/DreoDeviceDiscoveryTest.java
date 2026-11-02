package com.aaron.breeze;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public final class DreoDeviceDiscoveryTest {
    @Test public void exact565sModelIsDiscoveredWithoutGenericPrefixFallback() throws Exception {
        JSONObject response = new JSONObject("{\"code\":0,\"data\":{\"list\":["
                + "{\"sn\":\"actual\",\"deviceName\":\"Bedside\",\"model\":\"DR-HAF008S\",\"seriesName\":\"565S\"},"
                + "{\"sn\":\"alias\",\"model\":\"DR-HAF008\",\"seriesName\":\"565S\"},"
                + "{\"sn\":\"pedestal\",\"model\":\"DR-HPF008S\",\"seriesName\":\"508S\"},"
                + "{\"sn\":\"unverified\",\"model\":\"DR-HAF008\"},"
                + "{\"sn\":\"other\",\"model\":\"DR-HAF004S\"},"
                + "{\"model\":\"DR-HAF008S\",\"seriesName\":\"565S\"}]}}");
        List<DreoClient.Device> devices = DreoClient.parseDevices(response);
        assertEquals(2, devices.size());
        assertEquals("actual", devices.get(0).serial);
        assertEquals("Bedside", devices.get(0).name);
        assertEquals("DR-HAF008S", devices.get(0).model);
        assertEquals("565S", devices.get(0).seriesName);
        assertEquals("alias", devices.get(1).serial);
    }

    @Test public void numericSerialAndNonStringModelAreNotConverted() throws Exception {
        JSONObject response = new JSONObject("{\"code\":0,\"data\":{\"list\":["
                + "{\"sn\":12345,\"model\":\"DR-HAF008S\"},"
                + "{\"sn\":\"fan\",\"model\":808}]}}");
        assertTrue(DreoClient.parseDevices(response).isEmpty());
    }

    @Test public void failedOrMalformedDiscoveryIsNeverAnEmptySuccess() throws Exception {
        for (String json : new String[]{"{\"code\":401,\"data\":{\"list\":[]}}", "{\"code\":0}", "{\"code\":0,\"data\":{}}"}) {
            try { DreoClient.parseDevices(new JSONObject(json)); fail("Invalid discovery accepted"); }
            catch (IOException expected) { assertEquals("Dreo device discovery failed.", expected.getMessage()); }
        }
    }
}

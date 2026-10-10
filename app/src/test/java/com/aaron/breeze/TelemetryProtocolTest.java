package com.aaron.breeze;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelemetryProtocolTest {
    private static byte[] capability(int seconds){
        byte[] packet=new byte[20];packet[0]=4;packet[4]=7;packet[8]=64;packet[11]=(byte)seconds;return packet;
    }

    @Test public void cadenceRequiresItsCapabilityAndSensorProtocol(){
        byte[] packet=capability(10);
        assertEquals(10000,Protocol.telemetryIntervalMs(packet));
        packet[8]=(byte)(1|8|16|32|64);packet[10]=2;
        assertEquals(10000,Protocol.telemetryIntervalMs(packet));
        packet[8]&=~64;
        assertEquals(1000,Protocol.telemetryIntervalMs(packet));
        packet[8]=64;packet[4]=6;
        assertEquals(1000,Protocol.telemetryIntervalMs(packet));
        packet[4]=5;
        assertEquals(1000,Protocol.telemetryIntervalMs(packet));
        packet[4]=8;
        assertEquals(10000,Protocol.telemetryIntervalMs(packet));
        packet[0]=1;
        assertEquals(1000,Protocol.telemetryIntervalMs(packet));
    }

    @Test public void invalidAndAbsentCadenceFallBackToLegacy(){
        assertEquals(1000,Protocol.telemetryIntervalMs(null));
        assertEquals(1000,Protocol.telemetryIntervalMs(new byte[19]));
        assertEquals(1000,Protocol.telemetryIntervalMs(new byte[21]));
        assertEquals(1000,Protocol.telemetryIntervalMs(capability(0)));
        assertEquals(1000,Protocol.telemetryIntervalMs(capability(61)));
        assertEquals(1000,Protocol.telemetryIntervalMs(capability(255)));
        assertEquals(1000,Protocol.telemetryIntervalMs(capability(1)));
        assertEquals(60000,Protocol.telemetryIntervalMs(capability(60)));
    }

    @Test public void timingPreservesLegacyAndAllowsAMissedTenSecondUpdate(){
        long legacy=Protocol.telemetryIntervalMs(new byte[20]);
        assertEquals(6000,Protocol.telemetryFreshnessMs(legacy));
        assertEquals(15000,Protocol.telemetryLinkTimeoutMs(legacy));
        long tenSeconds=Protocol.telemetryIntervalMs(capability(10));
        assertEquals(25000,Protocol.telemetryFreshnessMs(tenSeconds));
        assertEquals(30000,Protocol.telemetryLinkTimeoutMs(tenSeconds));
        assertTrue(20000<Protocol.telemetryFreshnessMs(tenSeconds));
        assertEquals(125000,Protocol.telemetryFreshnessMs(Protocol.telemetryIntervalMs(capability(60))));
        assertEquals(180000,Protocol.telemetryLinkTimeoutMs(Protocol.telemetryIntervalMs(capability(60))));
    }
}

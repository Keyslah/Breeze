package com.aaron.breeze;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DreoSyncPolicyTest {
    private static final long STAMP=1791000000L, RECEIVED=(STAMP+1000)*1000;
    private static JSONObject field(Object value,long timestamp)throws Exception {return new JSONObject().put("state",value).put("timestamp",timestamp);}
    private static DreoReport report(int speed,boolean power,int mode,long stamp)throws Exception {
        JSONObject mixed=new JSONObject().put("windlevel",field(speed,stamp)).put("poweron",field(power,stamp)).put("mode",field(mode,stamp)).put("connected",field(true,stamp));
        return parse(mixed);
    }
    private static DreoReport parse(JSONObject mixed)throws Exception {return DreoReport.parse(new JSONObject().put("code",0).put("data",new JSONObject().put("sn","fixture-device").put("mixed",mixed)),"fixture-device",RECEIVED-100,RECEIVED);}
    private static void settle(DreoSyncPolicy policy,DreoReport report)throws Exception {
        assertFalse(policy.accept(policy.generation(),report,0));
        assertTrue(policy.accept(policy.generation(),report,180000));
    }

    @Test public void unchangedOldValueTimestampIsUsableAfterTwoScheduledReads()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport report=report(5,true,1,STAMP);
        settle(policy,report);assertTrue(policy.confirmed());
        assertTrue(policy.accept(policy.generation(),report,360000));
    }
    @Test public void responseStartedBeforeCommandCannotCorrect()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();long request=policy.generation();
        policy.changed(10,RECEIVED);assertFalse(policy.accept(request,report(5,true,1,STAMP),180000));assertFalse(policy.confirmed());
    }
    @Test public void unchangedPostCommandSnapshotNeedsTwoScheduledReads()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport report=report(5,true,1,STAMP);settle(policy,report);
        policy.changed(200000,RECEIVED);assertFalse(policy.accept(policy.generation(),report,380000));
        assertTrue(policy.accept(policy.generation(),report,560000));
    }
    @Test public void valueChangedAfterCommandCanCorrectAtNextScheduledRead()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();policy.changed(1000,(STAMP+1)*1000);
        assertTrue(policy.accept(policy.generation(),report(6,true,1,STAMP+2),181000));
    }
    @Test public void anotherCommandRestartsCandidateConfirmationWithoutLosingVersions()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport report=report(5,true,1,STAMP);
        policy.changed(0,RECEIVED);assertFalse(policy.accept(policy.generation(),report,180000));policy.changed(180001,RECEIVED);
        assertFalse(policy.accept(policy.generation(),report,360000));assertTrue(policy.accept(policy.generation(),report,540000));
    }
    @Test public void olderVersionDoesNotReplaceNewerConfirmedState()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport newer=report(6,true,1,STAMP+10);settle(policy,newer);
        assertFalse(policy.accept(policy.generation(),report(5,true,1,STAMP),360000));assertSame(newer,policy.observed());
        assertTrue(policy.reason().contains("Older"));
    }
    @Test public void equalVersionDifferentValueIsRejected()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport first=report(5,true,1,STAMP);settle(policy,first);
        assertFalse(policy.accept(policy.generation(),report(6,true,1,STAMP),360000));assertSame(first,policy.observed());
    }
    @Test public void versionsSurviveProcessRestartAndDoNotRestoreSettledConfidence()throws Exception {
        DreoSyncPolicy original=new DreoSyncPolicy();DreoReport newer=report(6,true,1,STAMP+10);settle(original,newer);
        String saved=original.saveVersions();assertFalse(original.versionsChanged());
        DreoSyncPolicy restored=new DreoSyncPolicy();restored.restoreVersions(saved);assertFalse(restored.confirmed());
        assertFalse(restored.accept(restored.generation(),report(5,true,1,STAMP),0));
        assertFalse(restored.accept(restored.generation(),newer,180000));assertTrue(restored.accept(restored.generation(),newer,360000));
        assertFalse(restored.versionsChanged());
    }
    @Test public void missingValuesNeverInventPowerSpeedOrNormalMode()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport missing=parse(new JSONObject().put("connected",field(true,STAMP)).put("windlevel",field(5,STAMP)));
        assertFalse(policy.accept(policy.generation(),missing,0));assertFalse(policy.accept(policy.generation(),missing,180000));
        assertTrue(policy.reason().contains("incomplete"));
    }
    @Test public void unknownOnlineTimestampDoesNotConfirm()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport report=parse(new JSONObject().put("windlevel",field(5,STAMP)).put("poweron",field(true,STAMP)).put("mode",field(1,STAMP)).put("connected",new JSONObject().put("state",true)));
        assertFalse(policy.accept(policy.generation(),report,0));assertFalse(policy.accept(policy.generation(),report,180000));
    }
    @Test public void nonNormalModeIsDisplayedButNeverSentAsNormalToController()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport natural=report(5,false,2,STAMP);
        assertFalse(policy.accept(policy.generation(),natural,0));assertSame(natural,policy.observed());assertTrue(policy.reason().contains("not Normal"));
    }
    @Test public void offlineReportCannotCorrect()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport offline=parse(new JSONObject().put("windlevel",field(5,STAMP)).put("poweron",field(true,STAMP)).put("mode",field(1,STAMP)).put("connected",field(false,STAMP)));
        assertFalse(policy.accept(policy.generation(),offline,0));assertTrue(policy.reason().contains("offline"));
    }
    @Test public void networkFailureReturnsToEstimateAndRequiresNewSettling()throws Exception {
        DreoSyncPolicy policy=new DreoSyncPolicy();DreoReport report=report(5,true,1,STAMP);settle(policy,report);
        policy.unavailable("Dreo unavailable");assertFalse(policy.confirmed());assertTrue(policy.reason().contains("estimate"));
        assertFalse(policy.accept(policy.generation(),report,360000));assertTrue(policy.accept(policy.generation(),report,540000));
    }
    @Test public void periodicChecksRemainThreeMinutes() {assertEquals(180000,DreoSynchronizer.POLL_MS);}
}

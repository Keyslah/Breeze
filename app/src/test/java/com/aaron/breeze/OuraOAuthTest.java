package com.aaron.breeze;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class OuraOAuthTest {
    private static final long WALL=1800000000000L,ELAPSED=1000000L;
    private static final String STATE="s".repeat(43);
    private JSONObject response()throws Exception{return new JSONObject().put("version",1).put("state",STATE).put("access_token","SYNTHETIC_TEST_TOKEN").put("token_type","bearer").put("scope","daily").put("expires_in",3600);}
    private OuraOAuth.Response validate(JSONObject data)throws Exception{return OuraOAuth.validate(data.toString(),STATE,WALL,ELAPSED,WALL+1000,ELAPSED+1000);}
    @Test public void publicConfigurationRequestsOnlyDailyWithoutSecret(){String url=OuraOAuth.authorizationUrl(STATE);assertTrue(url.contains("response_type=token"));assertTrue(url.contains("scope=daily"));assertFalse(url.contains("secret"));assertFalse(url.contains("heartrate"));assertTrue(url.contains("oura-callback.html"));}
    @Test public void stateIsUnpredictableAndUrlSafe(){String a=OuraOAuth.newState(),b=OuraOAuth.newState();assertTrue(a.matches("[A-Za-z0-9_-]{43}"));assertNotEquals(a,b);}
    @Test public void acceptsTokenWithExactExpiryUnits()throws Exception{OuraOAuth.Response value=validate(response());assertEquals(3600000L,value.lifetimeMs);assertEquals("SYNTHETIC_TEST_TOKEN",value.token);}
    @Test public void rejectsMissingOrWrongPendingState()throws Exception{JSONObject data=response();assertThrows(IOException.class,()->OuraOAuth.validate(data.toString(),"",WALL,ELAPSED,WALL,ELAPSED));data.put("state","t".repeat(43));assertThrows(IOException.class,()->validate(data));}
    @Test public void rejectsExpiredRebootedOrBackwardClockRequests()throws Exception{String data=response().toString();assertThrows(IOException.class,()->OuraOAuth.validate(data,STATE,WALL,ELAPSED,WALL+600000,ELAPSED+600000));assertThrows(IOException.class,()->OuraOAuth.validate(data,STATE,WALL,ELAPSED,WALL+1000,ELAPSED-1));assertThrows(IOException.class,()->OuraOAuth.validate(data,STATE,WALL,ELAPSED,WALL-1,ELAPSED+1000));}
    @Test public void requiresDailyScopeAndBearerToken()throws Exception{JSONObject data=response().put("scope","heartrate");assertThrows(IOException.class,()->validate(data));JSONObject type=response().put("token_type","basic");assertThrows(IOException.class,()->validate(type));}
    @Test public void expiryCannotBeCoercedOrOverflow()throws Exception{for(Object invalid:new Object[]{"3600",0,-1,1.5,Long.MAX_VALUE,Double.MAX_VALUE}){JSONObject data=response().put("expires_in",invalid);assertThrows(IOException.class,()->validate(data));}}
    @Test public void noCredentialOrIntentTextInValidationErrors()throws Exception{JSONObject data=response().put("access_token","private\nvalue");IOException error=assertThrows(IOException.class,()->validate(data));assertFalse(error.getMessage().contains("private"));assertNull(error.getCause());}
    @Test public void verifiedDenialContainsNoToken()throws Exception{JSONObject data=new JSONObject().put("version",1).put("state",STATE).put("error","access_denied");OuraOAuth.Response parsed=validate(data);assertEquals("access_denied",parsed.error);assertEquals("",parsed.token);data.put("access_token","SYNTHETIC");assertThrows(IOException.class,()->validate(data));}
}

package com.aaron.breeze;
import android.content.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
public final class Settings {
    private Settings(){}
    // Range profiles have their own keys. The earlier Warm/Cool threshold profiles stay untouched under
    // logicProfileV1 and logicSavedProfilesV1 so nothing the user saved is overwritten or lost.
    private static final String ACTIVE_LOGIC="logicProfileV2",DRAFT_LOGIC="logicDraftV2",SAVED_LOGIC="logicSavedProfilesV2",EARLIER_LOGIC="logicProfileV1";
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("breeze",Context.MODE_PRIVATE);}
    public static int doubleHitTimeout(Context context){return Math.max(0,Math.min(Protocol.MAX_DOUBLE_HIT_SECONDS,prefs(context).getInt("doubleHitTimeoutSeconds",0)));}
    public static void saveDoubleHitTimeout(Context context,int seconds){
        if(seconds<0||seconds>Protocol.MAX_DOUBLE_HIT_SECONDS)throw new IllegalArgumentException("Enter 0–3600 seconds.");
        if(!prefs(context).edit().putInt("doubleHitTimeoutSeconds",seconds).commit())throw new IllegalStateException("Could not save the double-hit timeout.");
    }
    /** The range the controller should be running: the last one applied from Setup. */
    public static synchronized LogicProfile logicProfile(Context context){
        SharedPreferences p=prefs(context);String saved=p.getString(ACTIVE_LOGIC,null);
        if(saved!=null)return LogicProfile.deserialize(saved);
        LogicProfile profile=LogicProfile.fromThresholdProfile(p.getString(EARLIER_LOGIC,null));
        // The controller still holds whatever it had before; send this range the next time Logic resumes.
        commitLogic(p.edit().putString(ACTIVE_LOGIC,profile.serialize()).putBoolean("homeLogicProfileDeferred",true));
        return profile;
    }
    /** Record a range as applied; any unsent Setup edits are replaced by it. */
    public static synchronized void saveLogicProfile(Context context,LogicProfile profile){
        commitLogic(prefs(context).edit().putString(ACTIVE_LOGIC,profile.serialize()).remove(DRAFT_LOGIC));
    }
    /** What Setup shows: unsent edits when there are any, otherwise the applied range. */
    public static synchronized LogicProfile logicDraft(Context context){
        LogicProfile applied=logicProfile(context);String draft=prefs(context).getString(DRAFT_LOGIC,null);
        if(draft==null)return applied;
        try{return LogicProfile.deserialize(draft);}catch(IllegalArgumentException unreadable){return applied;}
    }
    public static synchronized boolean logicDraftPending(Context context){return prefs(context).contains(DRAFT_LOGIC);}
    /** Keep Setup edits on the phone without sending them to the controller. */
    public static synchronized void saveLogicDraft(Context context,LogicProfile profile){
        LogicProfile applied=logicProfile(context);SharedPreferences.Editor edit=prefs(context).edit();
        // When only the name differs there is nothing to send: the controller never sees it.
        if(profile.sameSettings(applied))edit.putString(ACTIVE_LOGIC,profile.serialize()).remove(DRAFT_LOGIC);
        else edit.putString(DRAFT_LOGIC,profile.serialize());
        commitLogic(edit);
    }
    public static synchronized List<LogicProfile> savedLogicProfiles(Context context){
        JSONObject library=readLibrary(prefs(context));List<LogicProfile> profiles=new ArrayList<>();
        try{Iterator<String> names=library.keys();while(names.hasNext())profiles.add(LogicProfile.deserialize(library.getString(names.next())));}
        catch(JSONException failure){throw invalidLibrary(failure);}
        profiles.sort(Comparator.comparing(profile->profile.name));return profiles;
    }
    public static synchronized boolean hasSavedLogicProfile(Context context,String name){return readLibrary(prefs(context)).has(name==null?"":name.trim());}
    /** Keep a named copy. It changes neither the Setup form nor what the controller runs. */
    public static synchronized void saveNamedLogicProfile(Context context,LogicProfile profile){
        String saved=profile.serialize();SharedPreferences p=prefs(context);JSONObject library=readLibrary(p);
        try{library.put(profile.name.trim(),saved);}catch(JSONException failure){throw invalidLibrary(failure);}
        commitLogic(p.edit().putString(SAVED_LOGIC,library.toString()));
    }
    /** Read a named copy without applying it. */
    public static synchronized LogicProfile loadLogicProfile(Context context,String name){
        String requested=name==null?"":name.trim();JSONObject library=readLibrary(prefs(context));
        if(!library.has(requested))throw new IllegalArgumentException("Saved profile not found.");
        try{return LogicProfile.deserialize(library.getString(requested));}catch(JSONException failure){throw invalidLibrary(failure);}
    }
    private static JSONObject readLibrary(SharedPreferences prefs){
        if(!prefs.contains(SAVED_LOGIC))return new JSONObject();
        try{
            JSONTokener input=new JSONTokener(prefs.getString(SAVED_LOGIC,""));Object parsed=input.nextValue();
            if(!(parsed instanceof JSONObject)||input.nextClean()!=0)throw new IllegalArgumentException("Invalid saved profile collection.");
            JSONObject library=(JSONObject)parsed;Iterator<String> names=library.keys();
            while(names.hasNext()){
                String name=names.next();Object saved=library.get(name);
                if(!(saved instanceof String))throw new IllegalArgumentException("Invalid saved profile entry.");
                LogicProfile profile=LogicProfile.deserialize((String)saved);
                if(!name.equals(name.trim())||!name.equals(profile.name.trim()))throw new IllegalArgumentException("Saved profile name does not match its entry.");
            }
            return library;
        }catch(JSONException failure){throw invalidLibrary(failure);}
    }
    private static IllegalArgumentException invalidLibrary(Exception failure){return new IllegalArgumentException("Could not read saved logic profiles.",failure);}
    private static void commitLogic(SharedPreferences.Editor edit){
        if(!edit.commit())throw new IllegalStateException("Could not save the logic profile.");
    }
    static final int MIN_SMOOTH_MINUTES=1,MAX_SMOOTH_MINUTES=20,DEFAULT_SMOOTH_MINUTES=2;
    static boolean historySmoothHighestBody(SharedPreferences prefs){return prefs.getBoolean("historySmoothHighestBody",true);}
    static boolean historySmoothRoom(SharedPreferences prefs){return prefs.getBoolean("historySmoothRoom",true);}
    static int historySmoothingMinutes(SharedPreferences prefs,boolean room){return Math.max(MIN_SMOOTH_MINUTES,Math.min(MAX_SMOOTH_MINUTES,prefs.getInt(room?"historyRoomSmoothMinutes":"historyBodySmoothMinutes",DEFAULT_SMOOTH_MINUTES)));}
    static int historyVisibleSeries(SharedPreferences prefs){
        int visible=prefs.getInt("historyVisibleSeries",GraphView.DEFAULT_SERIES)&GraphView.ALL_SERIES;
        if(!prefs.getBoolean("historyHighestBodyDefaultApplied",false)){
            // Apply the new body trace default once, retaining weather/humidity choices.
            visible=(visible&~7)|(1<<GraphView.HIGHEST_BODY_SERIES);
            prefs.edit().putInt("historyVisibleSeries",visible).putBoolean("historyHighestBodyDefaultApplied",true).apply();
        }
        if(!prefs.getBoolean("historyFanSettingDefaultApplied",false)){
            visible|=1<<GraphView.FAN_SETTING_SERIES;
            prefs.edit().putInt("historyVisibleSeries",visible).putBoolean("historyFanSettingDefaultApplied",true).apply();
        }
        return visible;
    }
}

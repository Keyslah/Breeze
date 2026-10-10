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
    private static final String ACTIVE_LOGIC="logicProfileV1",SAVED_LOGIC="logicSavedProfilesV1";
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("breeze",Context.MODE_PRIVATE);}
    public static synchronized LogicProfile logicProfile(Context context){
        SharedPreferences p=prefs(context);String saved=p.getString(ACTIVE_LOGIC,null);
        LogicProfile profile=saved==null?new LogicProfile():LogicProfile.deserialize(saved);
        if(saved==null||!p.contains(SAVED_LOGIC)){
            SharedPreferences.Editor edit=p.edit();
            if(saved==null)edit.putString(ACTIVE_LOGIC,profile.serialize());
            if(!p.contains(SAVED_LOGIC))edit.putString(SAVED_LOGIC,seedLibrary(p,profile).toString());
            commitLogic(edit);
        }
        return profile;
    }
    public static synchronized void saveLogicProfile(Context context,LogicProfile profile){
        String saved=profile.serialize();SharedPreferences p=prefs(context);SharedPreferences.Editor edit=p.edit().putString(ACTIVE_LOGIC,saved);
        // Preserve the pre-upgrade active profile before the first autosave can rename it.
        if(!p.contains(SAVED_LOGIC))edit.putString(SAVED_LOGIC,seedLibrary(p,profile).toString());
        commitLogic(edit);
    }
    public static synchronized List<LogicProfile> savedLogicProfiles(Context context){
        JSONObject library=savedLibrary(context);List<LogicProfile> profiles=new ArrayList<>();
        try{Iterator<String> names=library.keys();while(names.hasNext())profiles.add(LogicProfile.deserialize(library.getString(names.next())));}
        catch(JSONException failure){throw invalidLibrary(failure);}
        profiles.sort(Comparator.comparing(profile->profile.name));return profiles;
    }
    public static synchronized void saveNamedLogicProfile(Context context,LogicProfile profile){
        String saved=profile.serialize();SharedPreferences p=prefs(context);
        JSONObject library=p.contains(SAVED_LOGIC)?readLibrary(p):seedLibrary(p,profile);
        try{library.put(profile.name.trim(),saved);}catch(JSONException failure){throw invalidLibrary(failure);}
        commitLogic(p.edit().putString(ACTIVE_LOGIC,saved).putString(SAVED_LOGIC,library.toString()));
    }
    public static synchronized LogicProfile loadLogicProfile(Context context,String name){
        String requested=name==null?"":name.trim();JSONObject library=savedLibrary(context);
        if(!library.has(requested))throw new IllegalArgumentException("Saved profile not found.");
        try{
            LogicProfile profile=LogicProfile.deserialize(library.getString(requested));
            commitLogic(prefs(context).edit().putString(ACTIVE_LOGIC,profile.serialize()));return profile;
        }catch(JSONException failure){throw invalidLibrary(failure);}
    }
    private static JSONObject savedLibrary(Context context){
        SharedPreferences p=prefs(context);if(!p.contains(SAVED_LOGIC))logicProfile(context);return readLibrary(p);
    }
    private static JSONObject seedLibrary(SharedPreferences prefs,LogicProfile fallback){
        String previous=prefs.getString(ACTIVE_LOGIC,null);LogicProfile seed=previous==null?fallback:LogicProfile.deserialize(previous);
        JSONObject library=new JSONObject();try{library.put(seed.name.trim(),seed.serialize());return library;}catch(JSONException failure){throw invalidLibrary(failure);}
    }
    private static JSONObject readLibrary(SharedPreferences prefs){
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

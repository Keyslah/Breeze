package com.aaron.breeze;
import android.content.*;
import org.json.*;
import java.util.*;
public final class Settings {
    private Settings(){}
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("breeze",Context.MODE_PRIVATE);}
    public static String json(Protocol.Config c){return "{\"low\":"+c.low+",\"medium\":"+c.medium+",\"high\":"+c.high+",\"hysteresis\":"+c.hysteresis+",\"offAngle\":"+c.offAngle+",\"lowAngle\":"+c.lowAngle+",\"mediumAngle\":"+c.mediumAngle+",\"highAngle\":"+c.highAngle+",\"dwell\":"+c.dwell+",\"ambientWeight\":"+c.ambientWeight+",\"humidityThreshold\":"+c.humidityThreshold+",\"humidityBoost\":"+c.humidityBoost+",\"calibrated\":"+c.calibrated+"}";}
    public static Protocol.Config load(Context context){Protocol.Config c=new Protocol.Config();try{JSONObject j=new JSONObject(prefs(context).getString("config","{}"));c.low=j.optDouble("low",29);c.medium=j.optDouble("medium",31);c.high=j.optDouble("high",33);c.hysteresis=j.optDouble("hysteresis",.5);c.offAngle=j.optInt("offAngle",60);c.highAngle=j.optInt("highAngle",75);c.mediumAngle=j.optInt("mediumAngle",90);c.lowAngle=j.optInt("lowAngle",105);c.dwell=j.optInt("dwell",30);c.ambientWeight=j.optInt("ambientWeight",0);c.humidityThreshold=j.optInt("humidityThreshold",65);c.humidityBoost=j.optInt("humidityBoost",0);c.calibrated=j.optBoolean("calibrated",false);}catch(JSONException ignored){}return c;}
    public static void save(Context context,Protocol.Config c){c.validate();prefs(context).edit().putString("config",json(c)).apply();}
    private static JSONObject profiles(Context context) throws JSONException {return new JSONObject(prefs(context).getString("ruleProfiles","{}"));}
    public static List<String> ruleNames(Context context) throws JSONException {
        List<String> names=new ArrayList<>();profiles(context).keys().forEachRemaining(names::add);names.sort(String.CASE_INSENSITIVE_ORDER);return names;
    }
    public static void saveRules(Context context,String name,Protocol.Config c) throws JSONException {
        name=name.trim();if(name.isEmpty())throw new IllegalArgumentException("Enter a name for these rules.");c.validate();
        JSONObject all=profiles(context),rules=new JSONObject();
        rules.put("low",c.low).put("medium",c.medium).put("high",c.high).put("hysteresis",c.hysteresis).put("dwell",c.dwell).put("ambientWeight",c.ambientWeight).put("humidityThreshold",c.humidityThreshold).put("humidityBoost",c.humidityBoost);
        all.put(name,rules);prefs(context).edit().putString("ruleProfiles",all.toString()).putString("ruleName",name).putString("config",json(c)).apply();
    }
    public static void loadRules(Context context,String name) throws JSONException {
        JSONObject rules=profiles(context).getJSONObject(name);Protocol.Config c=load(context);
        c.low=rules.getDouble("low");c.medium=rules.getDouble("medium");c.high=rules.getDouble("high");c.hysteresis=rules.getDouble("hysteresis");c.dwell=rules.getInt("dwell");c.ambientWeight=rules.getInt("ambientWeight");c.humidityThreshold=rules.getInt("humidityThreshold");c.humidityBoost=rules.getInt("humidityBoost");
        c.validate();prefs(context).edit().putString("config",json(c)).putString("ruleName",name).apply();
    }
}

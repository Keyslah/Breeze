package com.aaron.breeze;
import android.content.*;
import org.json.*;
public final class Settings {
    private Settings(){}
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("breeze",Context.MODE_PRIVATE);}
    public static String json(Protocol.Config c){return "{\"low\":"+c.low+",\"medium\":"+c.medium+",\"high\":"+c.high+",\"hysteresis\":"+c.hysteresis+",\"offAngle\":"+c.offAngle+",\"lowAngle\":"+c.lowAngle+",\"mediumAngle\":"+c.mediumAngle+",\"highAngle\":"+c.highAngle+",\"dwell\":"+c.dwell+",\"ambientWeight\":"+c.ambientWeight+",\"humidityThreshold\":"+c.humidityThreshold+",\"humidityBoost\":"+c.humidityBoost+",\"calibrated\":"+c.calibrated+"}";}
    public static Protocol.Config load(Context context){Protocol.Config c=new Protocol.Config();try{JSONObject j=new JSONObject(prefs(context).getString("config","{}"));c.low=j.optDouble("low",29);c.medium=j.optDouble("medium",31);c.high=j.optDouble("high",33);c.hysteresis=j.optDouble("hysteresis",.5);c.offAngle=j.optInt("offAngle",60);c.highAngle=j.optInt("highAngle",75);c.mediumAngle=j.optInt("mediumAngle",90);c.lowAngle=j.optInt("lowAngle",105);c.dwell=j.optInt("dwell",30);c.ambientWeight=j.optInt("ambientWeight",0);c.humidityThreshold=j.optInt("humidityThreshold",65);c.humidityBoost=j.optInt("humidityBoost",0);c.calibrated=j.optBoolean("calibrated",false);}catch(JSONException ignored){}return c;}
    public static void save(Context context,Protocol.Config c){c.validate();prefs(context).edit().putString("config",json(c)).apply();}
}

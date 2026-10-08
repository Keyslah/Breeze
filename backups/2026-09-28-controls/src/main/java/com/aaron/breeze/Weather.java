package com.aaron.breeze;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
public final class Weather {
    private Weather(){}
    public static class Place {public String label;public double lat,lon;public String toString(){return label;}}
    private static JSONObject get(String address)throws Exception {HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);try{int code=c.getResponseCode();if(code!=200)throw new IOException("Weather service returned "+code);try(InputStream in=c.getInputStream()){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}finally{c.disconnect();}}
    public static List<Place> search(String query)throws Exception{JSONArray a=get("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&count=8&language=en&format=json").optJSONArray("results");List<Place> result=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Place p=new Place();p.lat=o.getDouble("latitude");p.lon=o.getDouble("longitude");p.label=o.getString("name")+", "+o.optString("admin1")+", "+o.optString("country");result.add(p);}return result;}
    public static int fetch(Store store,Store.Session s,Place p)throws Exception {
        long end=s.end>0?s.end:System.currentTimeMillis();LocalDate from=Instant.ofEpochMilli(s.start).atZone(ZoneOffset.UTC).toLocalDate();LocalDate to=Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate();
        boolean archive=from.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(5));
        String host=archive?"https://archive-api.open-meteo.com/v1/archive":"https://api.open-meteo.com/v1/forecast";
        JSONObject j=get(host+"?latitude="+p.lat+"&longitude="+p.lon+"&start_date="+from+"&end_date="+to+"&hourly=temperature_2m,relative_humidity_2m&timeformat=unixtime&timezone=GMT");
        JSONObject h=j.getJSONObject("hourly");JSONArray times=h.getJSONArray("time"),temps=h.getJSONArray("temperature_2m"),rh=h.getJSONArray("relative_humidity_2m");int count=0;
        for(int i=0;i<times.length();i++){long t=times.getLong(i)*1000L;if(t>=s.start-3600000&&t<=end+3600000&&!temps.isNull(i)){store.weather(s.id,t,temps.getDouble(i),rh.isNull(i)?Double.NaN:rh.getDouble(i),p.label);count++;}}
        if(count==0)throw new IOException("No outdoor data available for this session yet.");return count;
    }
}

package com.aaron.breeze;
import android.content.*;
public final class Settings {
    private Settings(){}
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("breeze",Context.MODE_PRIVATE);}
}

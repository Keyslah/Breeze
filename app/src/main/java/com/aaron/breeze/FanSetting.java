package com.aaron.breeze;

/** Dreo settings: Off is zero; H is the level above nine. */
public final class FanSetting {
    public static final int MAX=10;
    private FanSetting(){}
    public static boolean valid(int setting){return setting>=0&&setting<=MAX;}
    public static boolean validOn(int setting){return setting>=1&&setting<=MAX;}
    public static String label(int setting){return setting==MAX?"H":valid(setting)?Integer.toString(setting):"—";}
    public static int parse(String text){
        if(text==null)throw new IllegalArgumentException("Choose 0 (Off), 1–9, or H.");
        String value=text.trim();if(value.equalsIgnoreCase("H"))return MAX;
        try{int setting=Integer.parseInt(value);if(valid(setting))return setting;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("Choose 0 (Off), 1–9, or H.");
    }
}

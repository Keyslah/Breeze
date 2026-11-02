package com.aaron.breeze;

/** Unit conversion for the supported DR-HAF008S / 565S cloud temperature. */
final class DreoTemperature {
    // Verified in installed Dreo 3.7.8: HAF008S uses AF004ControlActivity, whose
    // raw `temperature` is left unchanged for F and converted with (value-32)/1.8
    // for C. Do not infer a unit from the magnitude of a reading.
    static final String SOURCE_UNIT="F";
    static final long MAX_GAP_MS=240_000;
    private DreoTemperature(){}

    static double celsius(double value,String sourceUnit){
        if(!Double.isFinite(value))return Double.NaN;
        double celsius="F".equals(sourceUnit)?(value-32.0)/1.8:"C".equals(sourceUnit)?value:Double.NaN;
        return celsius>=-40&&celsius<=125?celsius:Double.NaN;
    }
}

#pragma once
#include <stdint.h>
#include <math.h>
inline float thermistor(float mv,float supply=3300,float series=10000,float r25=10000,float beta=3950) {
  if(mv<30||mv>3000)return NAN;
  float r=series*mv/(supply-mv);
  float c=1.0f/(1.0f/298.15f+logf(r/r25)/beta)-273.15f;
  return c>=-20&&c<=80?c:NAN;
}

// Electrical/conversion validity only. No inferred contact/drop/recovery rules.
inline bool validBody(float c){return isfinite(c)&&c>=-20&&c<=80;}
inline float warmestBody(float a,float b){if(!validBody(a))return validBody(b)?b:NAN;return validBody(b)&&b>a?b:a;}
inline uint8_t bodySource(float a,float b){return !validBody(a)?(validBody(b)?2:0):(validBody(b)&&b>a?2:1);}

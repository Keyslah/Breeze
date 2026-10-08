#pragma once
#include <stdint.h>
#include <math.h>
inline float thermistor(float mv,float supply=3300,float series=10000,float r25=10000,float beta=3950) {
  if(mv<30||mv>3000)return NAN;
  float r=series*mv/(supply-mv);
  float c=1.0f/(1.0f/298.15f+logf(r/r25)/beta)-273.15f;
  return c>=-20&&c<=80?c:NAN;
}

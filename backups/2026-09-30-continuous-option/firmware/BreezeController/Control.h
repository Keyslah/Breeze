#pragma once
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
struct Config {
  int16_t threshold[3] = {2900,3100,3300};
  uint8_t angle[4] = {60,105,90,75}; // logical Off, Low, Medium, High; physical Off, High, Medium, Low
  uint8_t hysteresis=5, dwell=30, ambientWeight=0, humidityThreshold=65, humidityBoost=0, calibrated=0;
};
inline bool validConfig(const Config& c) {
  if(c.threshold[0]<500||c.threshold[2]>5000||c.hysteresis<1||c.hysteresis>20||c.dwell<5||c.ambientWeight>50||c.humidityThreshold>100||c.humidityBoost>20||c.calibrated>1)return false;
  for(int i=0;i<2;i++)if(c.threshold[i+1]-c.threshold[i]<=c.hysteresis*10)return false;
  for(int i=0;i<4;i++)if(c.angle[i]>180)return false;
  bool inc=c.angle[0]<c.angle[3]&&c.angle[3]<c.angle[2]&&c.angle[2]<c.angle[1];
  bool dec=c.angle[0]>c.angle[3]&&c.angle[3]>c.angle[2]&&c.angle[2]>c.angle[1];
  return (inc||dec)&&abs(int(c.angle[0])-int(c.angle[1]))<=90;
}
inline float thermistor(float mv,float supply=3300,float series=10000,float r25=10000,float beta=3950) {
  if(mv<30||mv>3000)return NAN;
  float r=series*mv/(supply-mv);
  float c=1.0f/(1.0f/298.15f+logf(r/r25)/beta)-273.15f;
  return c>=-20&&c<=80?c:NAN;
}
inline float effectiveTemp(float probe,float room,float rh,const Config& c) {
  if(!isfinite(probe))return NAN;
  float t=probe;
  if(isfinite(room))t=probe+(room-probe)*c.ambientWeight/100.0f;
  if(isfinite(rh)&&rh>c.humidityThreshold)t+=(rh-c.humidityThreshold)*c.humidityBoost/100.0f;
  return t;
}
inline int chooseSpeed(float t,int current,const Config& c) {
  if(!isfinite(t))return current;
  int desired=0;for(int i=0;i<3;i++)if(t*100>=c.threshold[i])desired=i+1;
  if(desired<current&&t*100>=c.threshold[current-1]-c.hysteresis*10)return current;
  return desired;
}

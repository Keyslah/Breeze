#pragma once
#include <stdint.h>

// Three-wire continuous rotation: pulse selects speed/direction, never position.
constexpr bool CONTINUOUS_TEST_ONLY=true;
constexpr int CR_MIN_US=1000, CR_MAX_US=2000;
constexpr int CR_NEUTRAL_MIN_US=1400, CR_NEUTRAL_MAX_US=1600;
constexpr int CR_MIN_DURATION_MS=20, CR_MAX_DURATION_MS=2000;
inline bool validNeutral(int us){return us>=CR_NEUTRAL_MIN_US&&us<=CR_NEUTRAL_MAX_US;}
inline bool validJog(int direction,int percent,int duration,int neutral){
  return (direction==1||direction==-1)&&percent>=1&&percent<=100&&duration>=CR_MIN_DURATION_MS&&duration<=CR_MAX_DURATION_MS&&validNeutral(neutral);
}
inline int continuousPulse(int direction,int percent,int neutral){
  int endpoint=direction>0?CR_MAX_US:CR_MIN_US;
  return neutral+(endpoint-neutral)*percent/100;
}
inline bool legacyPositionCommand(int opcode,int action){
  return opcode==1||(opcode==2&&(action==0||action==1||action==2||action==3||action==5||action==7||(action>=9&&action<=13)));
}

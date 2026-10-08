#pragma once
#include <stdint.h>
// Pulse targets, not physical position feedback. Keep the existing servo range.
constexpr int SERVO_MIN_US=1000, SERVO_MAX_US=2000, SERVO_CENTER_US=1500;
constexpr int TEST_MIN_STEP_US=2, TEST_MAX_STEP_US=100;
inline int steppedPulse(int current,int direction,int step){
  if(current<SERVO_MIN_US||current>SERVO_MAX_US||step<TEST_MIN_STEP_US||step>TEST_MAX_STEP_US||(direction!=-1&&direction!=1))return -1;
  int next=current+direction*step;
  return next<SERVO_MIN_US||next>SERVO_MAX_US?-1:next;
}

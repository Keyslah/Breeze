#pragma once
#include <stdint.h>
// Preserve existing saved fan calibration; wider limits apply to manual testing.
constexpr int SERVO_MIN_US=1000, SERVO_MAX_US=2000, SERVO_CENTER_US=1500;
constexpr int TEST_MIN_US=500, TEST_MAX_US=2400;
constexpr int TEST_MIN_STEP_US=2, TEST_MAX_STEP_US=200;
inline int steppedPulse(int current,int direction,int step){
  if(current<TEST_MIN_US||current>TEST_MAX_US||step<TEST_MIN_STEP_US||step>TEST_MAX_STEP_US||(direction!=-1&&direction!=1))return -1;
  int next=current+direction*step;
  return next<TEST_MIN_US?TEST_MIN_US:next>TEST_MAX_US?TEST_MAX_US:next;
}

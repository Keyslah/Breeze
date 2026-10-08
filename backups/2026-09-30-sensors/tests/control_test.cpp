#include <cassert>
#include <cstdio>
#include "../firmware/BreezeController/Control.h"
int main(){
 Config c;assert(validConfig(c));assert(c.angle[0]<c.angle[3]&&c.angle[3]<c.angle[2]&&c.angle[2]<c.angle[1]);
 assert(fabs(thermistor(1650)-25)<.01);assert(isnan(thermistor(0)));assert(isnan(thermistor(3300)));
 assert(chooseSpeed(28,0,c)==0);assert(chooseSpeed(29,0,c)==1);assert(chooseSpeed(31,1,c)==2);assert(chooseSpeed(33,2,c)==3);
 assert(chooseSpeed(32.8,3,c)==3);assert(chooseSpeed(32.4,3,c)==2);assert(chooseSpeed(NAN,2,c)==2);
 assert(effectiveTemp(31,NAN,NAN,c)==31);c.ambientWeight=25;assert(effectiveTemp(32,24,NAN,c)==30);c.humidityBoost=10;assert(effectiveTemp(32,24,75,c)==31);
 c.angle[0]=120;c.angle[3]=105;c.angle[2]=90;c.angle[1]=75;assert(validConfig(c));c.angle[2]=120;assert(!validConfig(c));
 puts("PASS: NTC B3950 conversion, open/short faults, thresholds, hysteresis, missing sensors, humidity/room weighting, reversed mounting, physical dial order");
}

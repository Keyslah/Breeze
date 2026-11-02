#pragma once
#include <stdint.h>
#include <math.h>
#include <string.h>
#include <stddef.h>
#include "Sensors.h"

namespace BreezeLogic {
// Off is 0, speeds are 1-9 and H is 10. The range always spans every setting.
constexpr uint8_t MAX_LEVEL=10,ALL_PROBES=7;
// The phone compiles any number of user windows into at most one span per minute.
constexpr uint16_t MINUTES_PER_DAY=1440,MAX_TIME_WINDOW_SEGMENTS=MINUTES_PER_DAY;
static_assert(MAX_TIME_WINDOW_SEGMENTS*MAX_LEVEL<=32767,"Upload sums must fit int16_t");
// Scheduling stays in RAM. The phone owns saved windows and supplies local-clock offsets.
struct TimeWindow {
  uint8_t enabled=0;
  uint16_t startMinutes=270,endMinutes=480;
  int8_t adjustment=-1;
  bool valid()const{return enabled<=1&&startMinutes<1440&&endMinutes<1440&&adjustment>=-MAX_LEVEL&&adjustment<=MAX_LEVEL&&(!enabled||startMinutes!=endMinutes);}
  bool contains(uint16_t minute)const{return enabled&&(startMinutes<endMinutes?(minute>=startMinutes&&minute<endMinutes):(minute>=startMinutes||minute<endMinutes));}
};
struct TimeWindows {
  int8_t adjustments[MINUTES_PER_DAY]{};
  uint16_t count=0;
  bool anyEnabled=false;
  bool enabled()const{return anyEnabled;}
};
class WindowClock {
 public:
  bool set(uint32_t now,uint32_t epoch,int16_t offset,uint32_t transition,int16_t nextOffset){
    if(epoch<1700000000UL||offset<-840||offset>840||nextOffset<-840||nextOffset>840||(transition&&transition<epoch))return false;
    utcMillis=uint64_t(epoch)*1000;lastTick=now;offsetMinutes=offset;transitionEpoch=transition;nextOffsetMinutes=nextOffset;known=true;advance(now);return true;
  }
  void advance(uint32_t now){
    if(!known)return;
    utcMillis+=uint32_t(now-lastTick);lastTick=now;
    if(transitionEpoch&&utcMillis/1000>=transitionEpoch){offsetMinutes=nextOffsetMinutes;transitionEpoch=0;}
  }
  bool ready()const{return known;}
  int8_t adjustment(const TimeWindow& window)const{
    if(!known||!window.enabled)return 0;
    const int64_t localSeconds=int64_t(utcMillis/1000)+int64_t(offsetMinutes)*60;
    const uint16_t minute=uint16_t((localSeconds%86400+86400)%86400/60);
    return window.contains(minute)?window.adjustment:0;
  }
  int8_t adjustment(const TimeWindows& windows)const{
    if(!known)return 0;
    const int64_t localSeconds=int64_t(utcMillis/1000)+int64_t(offsetMinutes)*60;
    const uint16_t minute=uint16_t((localSeconds%86400+86400)%86400/60);
    return windows.adjustments[minute];
  }
 private:
  uint64_t utcMillis=0;
  uint32_t lastTick=0,transitionEpoch=0;
  int16_t offsetMinutes=0,nextOffsetMinutes=0;
  bool known=false;
};
inline uint8_t adjustedTarget(uint8_t target,int8_t adjustment){const int value=int(target)+adjustment;return value<0?0:value>MAX_LEVEL?MAX_LEVEL:uint8_t(value);}
struct Boost {
  uint8_t enabled=0,extra=2;
  uint16_t triggerCentiF=100,windowSeconds=30,riseSeconds=3,holdSeconds=120,returnSeconds=5;
  uint8_t curve=1,reserved=0;
};
// One continuous range: the score is 0 at the Off point and MAX_LEVEL at the Full point.
struct Profile {
  uint32_t magic=0x424C4F47;
  uint16_t schema=3,revision=1;
  int16_t offRoomCentiF=6800,offBodyCentiF=9800,fullRoomCentiF=7400,fullBodyCentiF=9700;
  uint16_t bodyWeightCenti=100,steadinessCenti=30;
  uint16_t averageSeconds=30,riseSeconds=10,fallSeconds=5;
  Boost boost{};
  uint32_t checksum=0;
};
inline uint32_t checksum(const Profile& p){
  const uint8_t* b=reinterpret_cast<const uint8_t*>(&p);uint32_t h=2166136261u;
  for(size_t i=0;i<offsetof(Profile,checksum);i++)h=(h^b[i])*16777619u;
  return h;
}
// Levels x 10000 that the room must contribute between the two points; the body supplies the rest.
inline int32_t roomSpanScaled(const Profile& p){return int32_t(MAX_LEVEL)*10000+int32_t(p.bodyWeightCenti)*(int32_t(p.offBodyCentiF)-p.fullBodyCentiF);}
inline bool valid(const Profile& p){
  const Boost& b=p.boost;
  return p.magic==0x424C4F47&&p.schema==3&&
    p.offRoomCentiF>=-5000&&p.fullRoomCentiF<=20000&&int32_t(p.fullRoomCentiF)-p.offRoomCentiF>=100&&
    p.offBodyCentiF>=-400&&p.offBodyCentiF<=17600&&p.fullBodyCentiF>=-400&&p.fullBodyCentiF<=17600&&
    p.bodyWeightCenti<=1000&&p.steadinessCenti<=100&&roomSpanScaled(p)>=1000&&
    p.averageSeconds>=1&&p.averageSeconds<=300&&p.riseSeconds>=1&&p.riseSeconds<=120&&p.fallSeconds>=1&&p.fallSeconds<=120&&
    b.enabled<=1&&b.extra>=1&&b.extra<=9&&b.triggerCentiF>=1&&b.triggerCentiF<=2000&&b.windowSeconds>=1&&b.windowSeconds<=300&&
    b.riseSeconds>=1&&b.riseSeconds<=120&&b.holdSeconds>=1&&b.holdSeconds<=3600&&b.returnSeconds>=1&&b.returnSeconds<=120&&b.curve<=1;
}
inline float fahrenheit(float c){return c*1.8f+32.0f;}
inline bool sameSettings(const Profile& a,const Profile& b){
  const Boost& u=a.boost;const Boost& v=b.boost;
  return a.offRoomCentiF==b.offRoomCentiF&&a.offBodyCentiF==b.offBodyCentiF&&a.fullRoomCentiF==b.fullRoomCentiF&&a.fullBodyCentiF==b.fullBodyCentiF&&
    a.bodyWeightCenti==b.bodyWeightCenti&&a.steadinessCenti==b.steadinessCenti&&a.averageSeconds==b.averageSeconds&&a.riseSeconds==b.riseSeconds&&a.fallSeconds==b.fallSeconds&&
    u.enabled==v.enabled&&u.extra==v.extra&&u.triggerCentiF==v.triggerCentiF&&u.windowSeconds==v.windowSeconds&&u.riseSeconds==v.riseSeconds&&u.holdSeconds==v.holdSeconds&&u.returnSeconds==v.returnSeconds&&u.curve==v.curve;
}
inline float interpolate(float t,uint8_t curve){if(t<=0)return 0;if(t>=1)return 1;return curve?t*t*(3-2*t):t;}
inline float bodyWeight(const Profile& p){return p.bodyWeightCenti/100.0f;}
inline float roomWeight(const Profile& p){return roomSpanScaled(p)/100.0f/(int32_t(p.fullRoomCentiF)-p.offRoomCentiF);}
inline float score(const Profile& p,float roomF,float bodyF){return roomWeight(p)*(roomF-p.offRoomCentiF/100.0f)+bodyWeight(p)*(bodyF-p.offBodyCentiF/100.0f);}
// Each setting owns one unit of score: Off at or below 0, then 1 for (0,1] up to H above 9.
inline uint8_t levelFor(float s){if(!(s>0.0001f))return 0;if(s>=MAX_LEVEL)return MAX_LEVEL;return uint8_t(ceilf(s-0.0001f));}
// Leave the held setting only once the score is the steadiness margin past its boundary.
inline uint8_t steadyLevel(const Profile& p,float s,uint8_t held){
  if(held>MAX_LEVEL)held=MAX_LEVEL;const uint8_t raw=levelFor(s);const float margin=p.steadinessCenti/100.0f;
  if(raw>held){const uint8_t up=levelFor(s-margin);return up>held?up:held;}
  if(raw<held){const uint8_t down=levelFor(s+margin);return down<held?down:held;}
  return held;
}
struct Decision {
  bool ready=false,boost=false;
  uint8_t target=0,baseline=0;
  uint16_t boostSeconds=0;
  float bodyF=NAN,roomF=NAN,score=NAN;
};
class Engine {
 public:
  Profile profile{};
  void apply(const Profile& p,bool keepSamples=false){profile=p;historyCount=historyPosition=0;if(!keepSamples){count=position=0;sampled=false;latestValid=false;serial=0;}lastEvaluation=serial;boost=BoostState{};}
  bool sample(uint32_t now,float c0,float c1,float c2=NAN){
    if(sampled&&uint32_t(now-lastSample)<1000)return false;
    sampled=true;lastSample=now;float values[3]={c0,c1,c2},warm=NAN;
    for(uint8_t i=0;i<3;i++)if(validBody(values[i])&&(!isfinite(warm)||values[i]>warm))warm=values[i];
    latestValid=isfinite(warm);samples[position]={now,latestValid?fahrenheit(warm):NAN};position=(position+1)%CAPACITY;if(count<CAPACITY)count++;serial++;
    float average=averageF(now);if(latestValid&&isfinite(average)){history[historyPosition]={now,average};historyPosition=(historyPosition+1)%CAPACITY;if(historyCount<CAPACITY)historyCount++;}
    return true;
  }
  float averageF(uint32_t now) const {
    float total=0;uint16_t usable=0;
    for(uint16_t i=0;i<count;i++)if(uint32_t(now-samples[i].at)<uint32_t(profile.averageSeconds)*1000&&isfinite(samples[i].value)){total+=samples[i].value;usable++;}
    return usable?total/usable:NAN;
  }
  bool bodyAvailable(uint32_t now)const{return sampled&&latestValid&&uint32_t(now-lastSample)<=2500&&isfinite(averageF(now));}
  // Off-to-H is one Power step plus nine speed steps; the configured time covers the nine.
  uint32_t stepIntervalMs(bool upward)const{const uint8_t steps=MAX_LEVEL-1;return (uint32_t(upward?profile.riseSeconds:profile.fallSeconds)*1000+steps-1)/steps;}
  Decision evaluate(uint32_t now,float roomC,uint8_t heldTarget=0,bool useSteadiness=true){
    Decision d;d.bodyF=averageF(now);d.roomF=isfinite(roomC)?fahrenheit(roomC):NAN;
    // Without both inputs there is no score: the caller holds the fan where it is.
    if(!isfinite(roomC)||roomC<-40||roomC>125){boost=BoostState{};return d;}
    if(!bodyAvailable(now)){d.bodyF=NAN;boost=BoostState{};return d;}
    d.ready=true;d.score=score(profile,d.roomF,d.bodyF);d.baseline=useSteadiness?steadyLevel(profile,d.score,heldTarget):levelFor(d.score);d.target=d.baseline;
    const bool newSample=serial!=lastEvaluation;lastEvaluation=serial;
    const Boost& settings=profile.boost;
    if(boost.active&&uint32_t(now-boost.at)>=duration(settings)){boost.active=false;boost.armed=false;}
    // Once the fan has gone Off, a rewarming probe must settle before it can count as a rise.
    if(!settings.enabled||d.baseline==0){boost=BoostState{};return d;}
    float prior=pastAverage(now,settings.windowSeconds);float rise=isfinite(prior)?d.bodyF-prior:NAN;
    if(newSample&&isfinite(rise)&&!boost.active){
      const float trigger=settings.triggerCentiF/100.0f;
      if(rise<=-trigger/2)boost.armed=false;
      else if(rise<trigger/2)boost.armed=true;
      else if(boost.armed&&rise+0.0001f>=trigger){boost.active=true;boost.at=now;boost.armed=false;}
    }
    if(!boost.active)return d;
    uint32_t elapsed=now-boost.at,riseMs=uint32_t(settings.riseSeconds)*1000,holdMs=uint32_t(settings.holdSeconds)*1000,returnMs=uint32_t(settings.returnSeconds)*1000;
    float fraction=elapsed<riseMs?interpolate(float(elapsed)/riseMs,settings.curve):elapsed<riseMs+holdMs?1:1-interpolate(float(elapsed-riseMs-holdMs)/returnMs,settings.curve);
    int target=lroundf(d.baseline+settings.extra*fraction);d.target=target>MAX_LEVEL?MAX_LEVEL:target;
    d.boost=true;d.boostSeconds=(duration(settings)-elapsed+999)/1000;return d;
  }
 private:
  static constexpr uint16_t CAPACITY=601;
  struct Point {uint32_t at=0;float value=NAN;};
  struct BoostState {bool active=false,armed=false;uint32_t at=0;};
  Point samples[CAPACITY]{},history[CAPACITY]{};
  BoostState boost{};
  uint16_t count=0,position=0,historyCount=0,historyPosition=0;
  uint32_t lastSample=0,serial=0,lastEvaluation=0;
  bool sampled=false,latestValid=false;
  static uint32_t duration(const Boost& b){return uint32_t(b.riseSeconds+b.holdSeconds+b.returnSeconds)*1000;}
  float pastAverage(uint32_t now,uint16_t seconds)const{
    uint32_t wanted=uint32_t(seconds)*1000,best=UINT32_MAX;float result=NAN;
    for(uint16_t i=0;i<historyCount;i++){uint32_t age=now-history[i].at;if(age>=wanted&&age<wanted+2500&&age<best){best=age;result=history[i].value;}}
    return result;
  }
};
}

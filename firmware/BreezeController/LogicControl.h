#pragma once
#include <stdint.h>
#include <math.h>
#include <string.h>
#include <stddef.h>
#include "Sensors.h"

namespace BreezeLogic {
struct Boost {
  uint8_t enabled=0,extra=2;
  uint16_t triggerCentiF=40,windowSeconds=30,riseSeconds=3,holdSeconds=120,returnSeconds=5;
  uint8_t curve=1,reserved=0;
};
struct Rule {
  int16_t bodyCentiF=9800;
  uint16_t stepCentiF=40;
  uint8_t minimum=3,offBelow=0;
  Boost boost{};
};
struct Profile {
  uint32_t magic=0x424C4F47;
  uint16_t schema=2,revision=1;
  uint8_t probeMask=7,maximum=9;
  int16_t roomCentiF=7100;
  int16_t coolRoomCentiF=7100;
  uint16_t averageSeconds=60,riseSeconds=10,fallSeconds=5;
  Rule rules[2]{};
  uint32_t checksum=0;
  Profile(){rules[1].minimum=1;rules[1].offBelow=1;}
};
inline uint32_t checksum(const Profile& p){
  const uint8_t* b=reinterpret_cast<const uint8_t*>(&p);uint32_t h=2166136261u;
  for(size_t i=0;i<offsetof(Profile,checksum);i++)h=(h^b[i])*16777619u;
  return h;
}
inline bool valid(const Profile& p){
  if(p.magic!=0x424C4F47||p.schema!=2||!(p.probeMask&7)||(p.probeMask&~7)||p.maximum<1||p.maximum>10||
     p.roomCentiF<-5000||p.roomCentiF>20000||p.coolRoomCentiF<-5000||p.coolRoomCentiF>p.roomCentiF||p.averageSeconds<1||p.averageSeconds>300||
     p.riseSeconds<1||p.riseSeconds>120||p.fallSeconds<1||p.fallSeconds>120)return false;
  for(const auto& r:p.rules){const auto& b=r.boost;
    if(r.bodyCentiF<-400||r.bodyCentiF>17600||r.stepCentiF<1||r.stepCentiF>2000||r.minimum<1||r.minimum>p.maximum||r.offBelow>1||
       b.enabled>1||b.extra<1||b.extra>9||b.triggerCentiF<1||b.triggerCentiF>2000||b.windowSeconds<1||b.windowSeconds>300||
       b.riseSeconds<1||b.riseSeconds>120||b.holdSeconds<1||b.holdSeconds>3600||b.returnSeconds<1||b.returnSeconds>120||b.curve>1)return false;
  }
  return true;
}
inline float fahrenheit(float c){return c*1.8f+32.0f;}
inline bool sameSettings(const Profile& a,const Profile& b){
  if(a.probeMask!=b.probeMask||a.maximum!=b.maximum||a.roomCentiF!=b.roomCentiF||a.coolRoomCentiF!=b.coolRoomCentiF||a.averageSeconds!=b.averageSeconds||a.riseSeconds!=b.riseSeconds||a.fallSeconds!=b.fallSeconds)return false;
  for(uint8_t i=0;i<2;i++){const Rule& x=a.rules[i];const Rule& y=b.rules[i];const Boost& u=x.boost;const Boost& v=y.boost;
    if(x.bodyCentiF!=y.bodyCentiF||x.stepCentiF!=y.stepCentiF||x.minimum!=y.minimum||x.offBelow!=y.offBelow||u.enabled!=v.enabled||u.extra!=v.extra||
       u.triggerCentiF!=v.triggerCentiF||u.windowSeconds!=v.windowSeconds||u.riseSeconds!=v.riseSeconds||u.holdSeconds!=v.holdSeconds||u.returnSeconds!=v.returnSeconds||u.curve!=v.curve)return false;
  }return true;
}
inline float interpolate(float t,uint8_t curve){if(t<=0)return 0;if(t>=1)return 1;return curve?t*t*(3-2*t):t;}
inline uint8_t normalTarget(const Profile& p,uint8_t rule,float bodyF,uint8_t heldTarget){
  const Rule& r=p.rules[rule];const float delta=bodyF-r.bodyCentiF/100.0f;
  if(r.offBelow&&delta<-0.00001f)return 0;
  if(r.offBelow&&fabsf(delta)<=0.00001f)return heldTarget>p.maximum?p.maximum:heldTarget;
  int steps=delta>0?floorf((delta+0.00001f)/(r.stepCentiF/100.0f)):0;
  int wanted=r.minimum+steps;return wanted>p.maximum?p.maximum:wanted;
}
struct Decision {
  bool ready=false,boost=false,hold=false;
  uint8_t rule=255,target=0,baseline=0;
  uint16_t boostSeconds=0;
  float bodyF=NAN,roomF=NAN;
};
class Engine {
 public:
  Profile profile{};
  void apply(const Profile& p,bool keepSamples=false){profile=p;historyCount=historyPosition=0;if(!keepSamples){count=position=0;sampled=false;latestValid=false;serial=0;}lastEvaluation=serial;for(auto& b:boosts)b=BoostState{};}
  bool sample(uint32_t now,float c0,float c1,float c2=NAN){
    if(sampled&&uint32_t(now-lastSample)<1000)return false;
    sampled=true;lastSample=now;float values[3]={c0,c1,c2},warm=NAN;
    for(uint8_t i=0;i<3;i++)if((profile.probeMask&(1u<<i))&&validBody(values[i])&&(!isfinite(warm)||values[i]>warm))warm=values[i];
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
  uint32_t stepIntervalMs(bool upward)const{const uint8_t steps=profile.maximum==10?9:8;return (uint32_t(upward?profile.riseSeconds:profile.fallSeconds)*1000+steps-1)/steps;}
  Decision evaluate(uint32_t now,float roomC,uint8_t heldTarget=0){
    Decision d;d.bodyF=averageF(now);d.roomF=isfinite(roomC)?fahrenheit(roomC):NAN;
    if(!isfinite(roomC)||roomC<-40||roomC>125)return d;
    if(d.roomF+0.00001f>=profile.roomCentiF/100.0f)d.rule=0;
    else if(d.roomF+0.00001f<profile.coolRoomCentiF/100.0f)d.rule=1;
    else{
      d.ready=true;d.hold=true;d.baseline=d.target=heldTarget;
      for(auto& state:boosts)if(state.active){state.active=false;state.armed=false;}
      return d;
    }
    // Leaving a rule cancels its boost rather than resuming a stale boost later.
    for(uint8_t i=0;i<2;i++)if(i!=d.rule&&boosts[i].active){boosts[i].active=false;boosts[i].armed=false;}
    if(!bodyAvailable(now)){d.bodyF=NAN;if(!profile.rules[d.rule].offBelow){d.ready=true;d.baseline=d.target=profile.rules[d.rule].minimum;}return d;}
    d.ready=true;d.baseline=normalTarget(profile,d.rule,d.bodyF,heldTarget);d.target=d.baseline;
    const bool newSample=serial!=lastEvaluation;lastEvaluation=serial;
    for(uint8_t i=0;i<2;i++)if(boosts[i].active&&uint32_t(now-boosts[i].at)>=duration(profile.rules[i].boost)){boosts[i].active=false;boosts[i].armed=false;}
    const Boost& settings=profile.rules[d.rule].boost;BoostState& state=boosts[d.rule];
    if(!settings.enabled||d.baseline==0){state.active=false;return d;}
    float prior=pastAverage(now,settings.windowSeconds);float rise=isfinite(prior)?d.bodyF-prior:NAN;
    if(newSample&&isfinite(rise)){
      if(!state.active&&rise<settings.triggerCentiF/200.0f)state.armed=true;
      if(!state.active&&state.armed&&rise+0.0001f>=settings.triggerCentiF/100.0f){state.active=true;state.at=now;state.armed=false;}
    }
    if(!state.active)return d;
    uint32_t elapsed=now-state.at,riseMs=uint32_t(settings.riseSeconds)*1000,holdMs=uint32_t(settings.holdSeconds)*1000,returnMs=uint32_t(settings.returnSeconds)*1000;
    float fraction=elapsed<riseMs?interpolate(float(elapsed)/riseMs,settings.curve):elapsed<riseMs+holdMs?1:1-interpolate(float(elapsed-riseMs-holdMs)/returnMs,settings.curve);
    int target=lroundf(d.baseline+settings.extra*fraction);d.target=target>profile.maximum?profile.maximum:target;
    d.boost=true;d.boostSeconds=(duration(settings)-elapsed+999)/1000;return d;
  }
 private:
  static constexpr uint16_t CAPACITY=601;
  struct Point {uint32_t at=0;float value=NAN;};
  struct BoostState {bool active=false,armed=true;uint32_t at=0;};
  Point samples[CAPACITY]{},history[CAPACITY]{};
  BoostState boosts[2]{};
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

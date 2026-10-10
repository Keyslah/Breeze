#pragma once
#include <stdint.h>
#include <string.h>

// BLE scheduling only: acquisition, recording, IR, and storage keep their own clocks.
class BreezeTelemetryCadence {
 public:
  static constexpr uint32_t FULL_PERIOD_MS=10000,DIAGNOSTIC_PERIOD_MS=1000,EVENT_POLL_MS=100;
  enum Changed:uint8_t {FAN=1,LOGIC=2,RECEIVER_TEST=4,RECEIVER_INPUT=8};

  bool fullDue(uint32_t now,uint32_t generation){
    connection(generation);
    return !cached||uint32_t(now-lastFull)>=FULL_PERIOD_MS;
  }

  void fullSent(uint32_t now,uint32_t generation,const uint8_t* fan,const uint8_t* logic,const uint8_t* test,const uint8_t* input){
    connection(generation);
    memcpy(fanSent,fan,20);memcpy(logicSent,logic,20);memcpy(testSent,test,20);memcpy(inputSent,input,20);
    lastFull=lastTest=lastInput=lastPoll=now;cached=true;
  }

  bool pollDue(uint32_t now){
    if(!cached||uint32_t(now-lastPoll)<EVENT_POLL_MS)return false;
    lastPoll=now;return true;
  }

  uint8_t changed(uint32_t now,const uint8_t* fan,const uint8_t* logic,const uint8_t* test,const uint8_t* input){
    if(!cached)return 0;
    uint8_t result=0,current[20],previous[20];
    fanState(current,fan);fanState(previous,fanSent);
    if(memcmp(current,previous,20)){result|=FAN;memcpy(fanSent,fan,20);}
    logicState(current,logic);logicState(previous,logicSent);
    if(memcmp(current,previous,20)){result|=LOGIC;memcpy(logicSent,logic,20);}
    // Countdown/frame updates share a one-second limit; a finished test is immediate.
    bool terminal=test[1]!=testSent[1]&&(test[1]==3||test[1]==4);
    if(memcmp(test,testSent,20)&&(terminal||uint32_t(now-lastTest)>=DIAGNOSTIC_PERIOD_MS)){
      result|=RECEIVER_TEST;memcpy(testSent,test,20);lastTest=now;
    }
    if(memcmp(input,inputSent,20)&&uint32_t(now-lastInput)>=DIAGNOSTIC_PERIOD_MS){
      result|=RECEIVER_INPUT;memcpy(inputSent,input,20);lastInput=now;
    }
    return result;
  }

 private:
  bool generationKnown=false,cached=false;
  uint32_t currentGeneration=0,lastFull=0,lastTest=0,lastInput=0,lastPoll=0;
  uint8_t fanSent[20]={},logicSent[20]={},testSent[20]={},inputSent[20]={};

  void connection(uint32_t generation){
    if(!generationKnown||generation!=currentGeneration){generationKnown=true;currentGeneration=generation;cached=false;}
  }
  static void fanState(uint8_t* out,const uint8_t* packet){
    memcpy(out,packet,20);out[1]&=uint8_t(~1u);
    out[2]=out[3]=out[11]=out[12]=out[13]=0; // Raw D1, chosen probe, and current body value.
  }
  static void logicState(uint8_t* out,const uint8_t* packet){
    memcpy(out,packet,20);out[1]&=uint8_t(~32u);
    memset(out+4,0,6);out[14]=out[15]=0; // Average/room/D2 temperatures and boost countdown.
  }
};

#pragma once
#include <stddef.h>
#include <Arduino.h>
#include <Preferences.h>
#include <IRrecv.h>
#include <IRsend.h>
#include "Pins.h"
#include "Sensors.h"
#include "LogicControl.h"

class FanRemote {
 public:
  static constexpr uint8_t SLOT_COUNT=12, NONE=255;
  enum Action:uint8_t {LEARN=1,CANCEL=2,CLEAR=3,SEND=4,TARGET=5,SYNC=6,AUTO=7,VERIFIED=8,FORGET_STATE=9,RECEIVER_TEST=12,LOGIC_GLOBAL=13,LOGIC_RULE=14,LOGIC_BOOST=15,LOGIC_COMMIT=16};
  enum Error:uint8_t {OK=0,NOT_LEARNED=20,NEED_SYNC=21,BUSY=22,TIMEOUT=23,STORAGE=24,BAD_CAPTURE=25,BAD_VALUE=26,NO_BODY=27,NEED_TEST=28,REMOTE_USED=29,RULES_PENDING=30,NO_ROOM=31};
  enum LearnState:uint8_t {IDLE,WAITING,SAVED,EXPIRED,CANCELED,FAILED};
  void begin();
  uint8_t command(const uint8_t* packet);
  void tick(float body0,float body1,float room,uint32_t now,float body2=NAN);
  void statusPacket(uint8_t* b,float rawBody1,uint8_t phase) const;
  void receiverTestPacket(uint8_t* b,uint32_t now) const;
  void receiverInputPacket(uint8_t* b) const;
  void logicStatusPacket(uint8_t* b,uint32_t now) const;
  uint8_t probeMask() const {return logic.profile.probeMask;}
  int8_t recordedSetting() const {return synchronized&&powerKnown&&estimated>=0&&estimated<=10?estimated:-1;}
  bool receiverTestActive() const {return testState==1||testState==2;}
  void disconnected();
  void cancelAutomatic(uint8_t reason);
 private:
  static constexpr uint16_t MAX_RAW=768;
  struct Learned {
    uint32_t magic=0x42524952;
    uint16_t version=1,count=0,carrier=38,bits=0;
    int16_t protocol=-1;
    uint8_t decoded=0,reserved=0;
    uint64_t value=0;
    uint32_t address=0,command=0;
    uint16_t raw[MAX_RAW]={};
    uint32_t checksum=0;
  };
  static_assert(sizeof(Learned)==1576&&offsetof(Learned,raw)==32&&offsetof(Learned,checksum)==1568,"Preserve the version 1 learned-code layout");
  IRrecv receiver{IR_RECEIVE_PIN,1024,15,true};
  IRsend sender{IR_TRANSMIT_PIN};
  Preferences nvs;
  bool storageReady=false,automatic=true,synchronized=false,verified=false,normalMode=false;
  bool powerKnown=false,powerOn=false;
  uint8_t retainedSpeed=0;
  bool profilePersisted=false;
  int estimated=0,target=0;
  uint16_t learnedMask=0,sendCounter=0;
  uint8_t learning=NONE,learnResultSlot=NONE,learnState=IDLE,error=OK,lastSent=NONE,source=0;
  uint16_t learnCarrier=38;
  uint32_t learningAt=0,lastSend=0,ignoreReceiveUntil=0;
  bool sentOnce=false,rcToggle=false;
  uint8_t repeatSlot=NONE;
  uint8_t testState=0,testAttempts=0,testMatched=0,testFrames=0,testOverflows=0,testIdle=1;
  uint16_t testId=0,testRawLen=0;
  uint32_t testNext=0,testDeadline=0;
  bool testWindowMatched=false,testWindowSeen=false;
  uint8_t inputFlags=0,inputSlot=NONE,inputBits=0;
  int16_t inputProtocol=-1;
  uint16_t inputSequence=0,inputRawLength=0;
  uint64_t inputValue=0;
  uint32_t monitorSendUntil=0;
  BreezeLogic::Engine logic;
  BreezeLogic::Profile stagedProfile;
  BreezeLogic::Decision logicDecision;
  uint8_t profileReceipt=0,logicBaseTarget=0,logicWanted=0;
  int8_t logicDirection=0;
  uint32_t logicNextStep=0;
  float thirdProbe=NAN;
  float controlBody=NAN,ambient=NAN;
  bool load(uint8_t slot,Learned& code);
  bool save(uint8_t slot,Learned& code);
  bool saveCompact(uint8_t slot,const Learned& code,bool migration);
  static bool validLearnedFields(const Learned& code);
  static bool sameLearned(const Learned& left,const Learned& right);
  void capture(uint32_t now);
  void receiverTestTick(uint32_t now);
  void recordReceiverInput(decode_results& result,bool duringSend,bool diagnostic=false);
  bool loadProfile(BreezeLogic::Profile& profile,bool* migrated=nullptr);
  bool saveProfile(BreezeLogic::Profile& profile);
  void saveEstimate();
  void loadEstimate();
  uint8_t profileCommand(const uint8_t* b);
  void automaticTick(uint32_t now);
  uint8_t transmit(uint8_t slot);
  uint8_t transmitOnce(uint8_t slot);
  void invalidate(uint8_t reason);
  static uint32_t checksum(const Learned& code);
  static const char* name(uint8_t slot);
};

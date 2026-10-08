#include "FanRemote.h"
#include <IRutils.h>
#include <stddef.h>
#include <string.h>

namespace {
uint16_t get16(const uint8_t* b){return b[0]|uint16_t(b[1])<<8;}
void set16(uint8_t* b,int v){b[0]=v;b[1]=v>>8;}
String key(uint8_t slot){return "ir"+String(slot);}
}
const char* FanRemote::name(uint8_t slot){
  static const char* names[SLOT_COUNT]={"Power","Speed +","Speed -","Mode","Horizontal","Vertical","Extra 1","Extra 2","Extra 3","Extra 4","Extra 5","Extra 6"};
  return slot<SLOT_COUNT?names[slot]:"Unknown";
}
uint32_t FanRemote::checksum(const Learned& code){
  const uint8_t* bytes=reinterpret_cast<const uint8_t*>(&code);uint32_t hash=2166136261u;
  for(size_t i=0;i<offsetof(Learned,checksum);i++)hash=(hash^bytes[i])*16777619u;
  return hash;
}
bool FanRemote::load(uint8_t slot,Learned& code){
  if(!storageReady||slot>=SLOT_COUNT||nvs.getBytesLength(key(slot).c_str())!=sizeof(code))return false;
  if(nvs.getBytes(key(slot).c_str(),&code,sizeof(code))!=sizeof(code))return false;
  return code.magic==0x42524952&&code.version==1&&code.count>=10&&code.count<=MAX_RAW&&
    code.carrier>=30&&code.carrier<=60&&code.decoded<=1&&code.bits<=64&&code.checksum==checksum(code);
}
bool FanRemote::save(uint8_t slot,Learned& code){
  if(!storageReady)return false;
  code.checksum=checksum(code);
  if(nvs.putBytes(key(slot).c_str(),&code,sizeof(code))!=sizeof(code))return false;
  Learned check;return load(slot,check)&&check.checksum==code.checksum;
}
void FanRemote::begin(){
  sender.begin(); // Output LOW until an explicit transmit; no learned defaults.
  storageReady=nvs.begin("breeze-ir",false);
  if(storageReady){
    verified=nvs.getBool("verified",false);
    for(uint8_t i=0;i<SLOT_COUNT;i++){Learned code;if(load(i,code))learnedMask|=1u<<i;}
  }else error=STORAGE;
  receiver.enableIRIn();receiver.setUnknownThreshold(10);
  Serial.printf("[IR] IRremoteESP8266 %s RX D6/GPIO%u TX D7/GPIO%u learned=0x%03x. Auto OFF; speed unknown.\n",_IRREMOTEESP8266_VERSION_STR,IR_RECEIVE_PIN,IR_TRANSMIT_PIN,learnedMask);
}
void FanRemote::invalidate(uint8_t reason){automatic=false;target=0;estimated=0;synchronized=false;normalMode=false;error=reason;}
void FanRemote::cancelAutomatic(uint8_t reason){automatic=false;target=0;error=reason;}
void FanRemote::disconnected(){
  // Cancel unfinished manual work when the phone disconnects.
  target=0;
  if(learning!=NONE){learning=NONE;learnState=CANCELED;}
}
uint8_t FanRemote::command(const uint8_t* b){
  const uint8_t action=b[4],value=b[5];uint32_t now=millis();
  switch(action){
    case LEARN:
      if(value>=SLOT_COUNT||get16(b+6)<30||get16(b+6)>60)return error=BAD_VALUE;
      if(!storageReady)return error=STORAGE;
      invalidate(OK);learning=value;learnResultSlot=value;learnCarrier=get16(b+6);learningAt=now;learnState=WAITING;receiver.resume();
      Serial.printf("[IR] Learn %s: point remote and press once (30s). Existing code retained until save succeeds. Raw carrier configured %ukHz, not measured.\n",name(value),learnCarrier);return OK;
    case CANCEL:learning=NONE;learnState=CANCELED;receiver.resume();return error=OK;
    case CLEAR:
      if(value>=SLOT_COUNT)return error=BAD_VALUE;
      if(!storageReady)return error=STORAGE;
      invalidate(OK);learning=NONE;learnState=IDLE;
      if((learnedMask&(1u<<value))&&!nvs.remove(key(value).c_str()))return error=STORAGE;
      learnedMask&=~(1u<<value);verified=false;nvs.putBool("verified",false);Serial.printf("[IR] Cleared %s\n",name(value));return OK;
    case SEND:
      if(value>=SLOT_COUNT)return error=BAD_VALUE;
      if(learning!=NONE)return error=BUSY;
      if(!(learnedMask&(1u<<value)))return error=NOT_LEARNED;
      if(sentOnce&&uint32_t(now-lastSend)<450)return error=BUSY;
      automatic=false;target=0;
      if(transmit(value)!=OK)return error;
      if(value==1||value==2){if(synchronized){
        // Endpoint behavior (wrap or clamp) is not known from an IR capture.
        if((value==1&&estimated==9)||(value==2&&estimated==1))invalidate(NEED_SYNC);
        else estimated+=(value==1?1:-1);
      }}
      else if(value!=4&&value!=5)invalidate(OK); // Power/mode/extra semantics are unknown.
      return error=OK;
    case TARGET:
      if(value<1||value>9)return error=BAD_VALUE;
      if(learning!=NONE)return error=BUSY;
      if(!synchronized||!normalMode)return error=NEED_SYNC;
      if(value!=estimated&&!(learnedMask&(1u<<(value>estimated?1:2))))return error=NOT_LEARNED;
      automatic=false;target=value;return error=OK;
    case SYNC:
      if(value<1||value>9||b[6]!=1)return error=BAD_VALUE;
      if(learning!=NONE||target)return error=BUSY;
      automatic=false;estimated=value;synchronized=true;normalMode=true;
      Serial.printf("[FAN] User reports fan ON, Normal mode, speed %u. Estimated only.\n",value);return error=OK;
    case AUTO:
      if(value>1)return error=BAD_VALUE;
      if(!value){cancelAutomatic(OK);return OK;}
      return error=RULES_PENDING; // Collect the probe contact data before defining any automatic rules.
    case VERIFIED:
      if(value>1)return error=BAD_VALUE;
      if(value&&((learnedMask&7)!=7||!synchronized))return error=NEED_SYNC;
      if(!storageReady||nvs.putBool("verified",value)!=1)return error=STORAGE;
      verified=value;if(!verified)cancelAutomatic(OK);return error=OK;
    case FORGET_STATE:invalidate(NEED_SYNC);return OK;
    default:return error=BAD_VALUE;
  }
}
void FanRemote::capture(uint32_t now){
  decode_results result;
  if(!receiver.decode(&result))return;
  if(int32_t(now-ignoreReceiveUntil)<0){receiver.resume();return;}
  if(result.overflow||result.rawlen<11||result.repeat){
    if(learning!=NONE)Serial.println("[IR] Ignored overflow, short noise or repeat-only frame; still waiting.");
    receiver.resume();return;
  }
  if(learning==NONE){
    if(synchronized||automatic){invalidate(REMOTE_USED);Serial.println("[IR] External remote observed; estimate invalidated, auto OFF. Sync current speed again.");}
    receiver.resume();return;
  }
  Serial.println(resultToHumanReadableBasic(&result));Serial.println(resultToSourceCode(&result));
  Learned code;code.count=getCorrectedRawLength(&result);code.carrier=learnCarrier;
  if(code.count<10||code.count>MAX_RAW){error=BAD_CAPTURE;learnState=FAILED;learning=NONE;receiver.resume();return;}
  uint16_t* raw=resultToRawArray(&result);if(!raw){error=STORAGE;learnState=FAILED;learning=NONE;receiver.resume();return;}
  uint32_t total=0;for(uint16_t i=0;i<code.count;i++){code.raw[i]=raw[i];total+=raw[i];}delete[] raw;
  if(total<5000||total>350000){error=BAD_CAPTURE;learnState=FAILED;learning=NONE;receiver.resume();return;}
  code.protocol=result.decode_type;
  if(result.decode_type!=UNKNOWN&&!hasACState(result.decode_type)&&result.bits>0&&result.bits<=64){
    code.decoded=1;code.bits=result.bits;code.value=result.value;code.address=result.address;code.command=result.command;
  }
  uint8_t slot=learning;learning=NONE;learnResultSlot=slot;verified=false;
  if(!storageReady||nvs.putBool("verified",false)!=1||!save(slot,code)){error=STORAGE;learnState=FAILED;Serial.println("[IR] SAVE FAILED; inspect NVS capacity.");}
  else{learnedMask|=1u<<slot;error=OK;learnState=SAVED;Serial.printf("[IR] Saved %s; protocol=%s address=0x%08lx command=0x%08lx bits=%u raw=%u carrier=%ukHz. Physical response not verified.\n",name(slot),typeToString(result.decode_type).c_str(),(unsigned long)code.address,(unsigned long)code.command,code.bits,code.count,code.carrier);}
  receiver.resume();
}
uint8_t FanRemote::transmit(uint8_t slot){
  Learned code;if(!load(slot,code))return error=NOT_LEARNED;
  receiver.disableIRIn();bool sent=false;
  if(code.decoded){
    uint64_t value=code.value;
    if(rcToggle&&code.protocol==RC5)value=sender.toggleRC5(value);
    if(rcToggle&&code.protocol==RC6)value=sender.toggleRC6(value,code.bits);
    sent=sender.send((decode_type_t)code.protocol,value,code.bits,0);
  }
  if(!sent)sender.sendRaw(code.raw,code.count,code.carrier);
  if(code.protocol==RC5||code.protocol==RC6)rcToggle=!rcToggle;
  lastSend=millis();sentOnce=true;ignoreReceiveUntil=lastSend+180;receiver.enableIRIn();
  lastSent=slot;sendCounter++;Serial.printf("[IR] TX %s via %s; physical response unconfirmed, estimated speed=%d\n",name(slot),sent?"decoded protocol":"raw timings",estimated);return error=OK;
}
void FanRemote::tick(float a,float b,float room,uint32_t now){
  controlBody=warmestBody(a,b);source=bodySource(a,b);ambient=room;
  capture(now);
  if(learning!=NONE&&uint32_t(now-learningAt)>=30000){learnResultSlot=learning;learning=NONE;learnState=EXPIRED;error=TIMEOUT;Serial.println("[IR] Learning timed out; prior command retained.");}
  if(learning!=NONE)return;
  if(target){
    if(!synchronized){target=0;error=NEED_SYNC;return;}
    if(target==estimated){target=0;return;}
    if(!sentOnce||uint32_t(now-lastSend)>=450){int direction=target>estimated?1:-1;if(transmit(direction>0?1:2)==OK){estimated+=direction;if(target==estimated)target=0;}else target=0;}
    return;
  }

}
void FanRemote::statusPacket(uint8_t* b,float rawBody1,uint8_t phase)const{
  memset(b,0,20);b[0]=5;b[1]=(validBody(rawBody1)?1:0)|(automatic?2:0)|(synchronized?4:0)|(verified?8:0)|(normalMode?16:0)|(target?32:0);
  set16(b+2,validBody(rawBody1)?lroundf(rawBody1*100):0);b[4]=estimated;b[5]=target;b[6]=learning;b[7]=learnState;set16(b+8,learnedMask);b[10]=error;b[11]=source;
  set16(b+12,validBody(controlBody)?lroundf(controlBody*100):0);b[14]=learnResultSlot;b[15]=lastSent;set16(b+16,sendCounter);b[18]=phase;b[19]=7;
}

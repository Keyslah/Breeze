#include "FanRemote.h"
#include <IRutils.h>
#include <nvs.h>
#include <memory>
#include <new>
#include <stddef.h>
#include <string.h>

namespace {
uint16_t get16(const uint8_t* b){return b[0]|uint16_t(b[1])<<8;}
void set16(uint8_t* b,int v){b[0]=v;b[1]=v>>8;}
uint32_t get32(const uint8_t* b){return uint32_t(get16(b))|uint32_t(get16(b+2))<<16;}
void set32(uint8_t* b,uint32_t value){set16(b,value);set16(b+2,value>>16);}
uint64_t get64(const uint8_t* b){return uint64_t(get32(b))|uint64_t(get32(b+4))<<32;}
void set64(uint8_t* b,uint64_t value){set32(b,uint32_t(value));set32(b+4,uint32_t(value>>32));}
uint32_t bytesChecksum(const uint8_t* bytes,size_t length){uint32_t hash=2166136261u;for(size_t i=0;i<length;i++)hash=(hash^bytes[i])*16777619u;return hash;}
constexpr size_t compactHeaderBytes=32,compactChecksumBytes=4,nvsEntriesPerPage=126,radioReserveEntries=252;
constexpr uint32_t automaticStepIntervalMs=1000;
size_t compactBytes(uint16_t count){return compactHeaderBytes+size_t(count)*2+compactChecksumBytes;}
String key(uint8_t slot){return "ir"+String(slot);}
struct FanEstimate {
  uint32_t magic=0x42464553;
  uint8_t known=0,on=0,speed=0,retained=0,normal=0,wanted=0;
  uint16_t reserved=0;
  uint32_t checksum=0;
};
uint32_t estimateChecksum(const FanEstimate& state){const uint8_t* b=reinterpret_cast<const uint8_t*>(&state);uint32_t h=2166136261u;for(size_t i=0;i<offsetof(FanEstimate,checksum);i++)h=(h^b[i])*16777619u;return h;}
// Range profiles use their own key; the earlier threshold profile under "logic-profile" is never rewritten.
constexpr char rangeProfileKey[]="logic-range";
constexpr char doubleHitKey[]="ir-double-time";
constexpr uint16_t maximumDoubleHitSeconds=3600;
// The fan times its display on its own clock. With the nominal 60 s, single presses were still
// accepted 60.1 s after the previous press and already swallowed at 58.5 s, so only idle times
// outside this band (milliseconds per configured second) say for certain what the display is doing.
constexpr uint32_t displayAwakeBeforeMs=875,displayAsleepFromMs=1100;
// Distinctive 38 kHz optical test pattern; this is not a learned fan command.
constexpr uint16_t testPattern=0xA5B,testTimingCount=27;
void testTimings(uint16_t* raw){
  raw[0]=1600;raw[1]=1600;
  for(uint8_t i=0;i<12;i++){raw[2+i*2]=600;raw[3+i*2]=(testPattern&(1u<<(11-i)))?1000:500;}
  raw[26]=600;
}
bool matchesTest(decode_results& result){
  if(result.overflow||result.repeat||getCorrectedRawLength(&result)!=testTimingCount)return false;
  uint16_t* measured=resultToRawArray(&result);if(!measured)return false;
  uint16_t expected[testTimingCount];testTimings(expected);bool matches=true;
  for(uint16_t i=0;i<testTimingCount;i++){
    int delta=int(measured[i])-int(expected[i]);if(delta<0)delta=-delta;
    if(delta>int(expected[i]/5+100)){matches=false;break;}
  }
  delete[] measured;return matches;
}
}
const char* FanRemote::name(uint8_t slot){
  static const char* names[SLOT_COUNT]={"Power","Speed +","Speed -","Mode","Horizontal","Vertical","Extra 1","Extra 2","Extra 3","Extra 4","Extra 5","Extra 6"};
  return slot<SLOT_COUNT?names[slot]:"Unknown";
}
uint32_t FanRemote::checksum(const Learned& code){
  return bytesChecksum(reinterpret_cast<const uint8_t*>(&code),offsetof(Learned,checksum));
}
bool FanRemote::validLearnedFields(const Learned& code){
  return code.magic==0x42524952&&code.version==1&&code.count>=10&&code.count<=MAX_RAW&&
    code.carrier>=30&&code.carrier<=60&&code.decoded<=1&&code.bits<=64;
}
bool FanRemote::sameLearned(const Learned& left,const Learned& right){
  return left.magic==right.magic&&left.version==right.version&&left.count==right.count&&left.carrier==right.carrier&&
    left.bits==right.bits&&left.protocol==right.protocol&&left.decoded==right.decoded&&left.reserved==right.reserved&&
    left.value==right.value&&left.address==right.address&&left.command==right.command&&
    memcmp(left.raw,right.raw,size_t(left.count)*sizeof(left.raw[0]))==0;
}
bool FanRemote::load(uint8_t slot,Learned& code){
  if(!storageReady||slot>=SLOT_COUNT)return false;
  const String name=key(slot);size_t length=nvs.getBytesLength(name.c_str());
  if(length==sizeof(code)){
    return nvs.getBytes(name.c_str(),&code,sizeof(code))==sizeof(code)&&validLearnedFields(code)&&code.checksum==checksum(code);
  }
  if(length<compactBytes(10)||length>compactBytes(MAX_RAW))return false;
  std::unique_ptr<uint8_t[]> bytes(new(std::nothrow) uint8_t[length]);if(!bytes)return false;
  if(nvs.getBytes(name.c_str(),bytes.get(),length)!=length||get32(bytes.get())!=0x42524952||get16(bytes.get()+4)!=2)return false;
  const uint16_t count=get16(bytes.get()+6);
  if(count<10||count>MAX_RAW||length!=compactBytes(count)||get32(bytes.get()+length-4)!=bytesChecksum(bytes.get(),length-4))return false;
  memset(&code,0,sizeof(code));code.magic=0x42524952;code.version=1;code.count=count;code.carrier=get16(bytes.get()+8);code.bits=get16(bytes.get()+10);
  code.protocol=int16_t(get16(bytes.get()+12));code.decoded=bytes[14];code.reserved=bytes[15];
  code.value=get64(bytes.get()+16);code.address=get32(bytes.get()+24);code.command=get32(bytes.get()+28);
  for(uint16_t i=0;i<count;i++)code.raw[i]=get16(bytes.get()+compactHeaderBytes+size_t(i)*2);
  code.checksum=checksum(code);return validLearnedFields(code);
}
bool FanRemote::save(uint8_t slot,Learned& code){
  if(!validLearnedFields(code))return false;
  code.checksum=checksum(code);
  return saveCompact(slot,code,false);
}
bool FanRemote::saveCompact(uint8_t slot,const Learned& code,bool migration){
  if(!storageReady||slot>=SLOT_COUNT||!validLearnedFields(code)||code.checksum!=checksum(code))return false;
  const String name=key(slot);const size_t length=compactBytes(code.count);
  nvs_stats_t stats={};if(nvs_get_stats(nullptr,&stats)!=ESP_OK||stats.total_entries<2*nvsEntriesPerPage)return false;
  size_t maxChunks=stats.total_entries/nvsEntriesPerPage-1;if(maxChunks>127)maxChunks=127;
  // Include data entries, a header for every possible chunk, and the final blob index.
  const size_t requiredEntries=(length+31)/32+maxChunks+1;
  // One heap buffer serves both legacy validation and replacement readback, keeping boot stack use small.
  std::unique_ptr<Learned> check(new(std::nothrow) Learned);if(!check)return false;
  if(migration){
    const size_t legacyMinimumEntries=(sizeof(Learned)+31)/32+2;
    if(length>=sizeof(Learned)||requiredEntries>=legacyMinimumEntries||nvs.getBytesLength(name.c_str())!=sizeof(Learned)||
      !load(slot,*check)||!sameLearned(*check,code)||stats.available_entries<requiredEntries)return false;
  }else if(stats.available_entries<requiredEntries+radioReserveEntries)return false;
  std::unique_ptr<uint8_t[]> bytes(new(std::nothrow) uint8_t[length]);if(!bytes)return false;
  set32(bytes.get(),code.magic);set16(bytes.get()+4,2);set16(bytes.get()+6,code.count);
  set16(bytes.get()+8,code.carrier);set16(bytes.get()+10,code.bits);set16(bytes.get()+12,uint16_t(code.protocol));
  bytes[14]=code.decoded;bytes[15]=code.reserved;set64(bytes.get()+16,code.value);set32(bytes.get()+24,code.address);set32(bytes.get()+28,code.command);
  for(uint16_t i=0;i<code.count;i++)set16(bytes.get()+compactHeaderBytes+size_t(i)*2,code.raw[i]);
  set32(bytes.get()+length-4,bytesChecksum(bytes.get(),length-4));
  // NVS replaces the same key; never remove the old code to make room for its replacement.
  if(nvs.putBytes(name.c_str(),bytes.get(),length)!=length)return false;
  return load(slot,*check)&&sameLearned(*check,code);
}
void FanRemote::begin(){
  sender.begin(); // Output LOW until an explicit transmit; no learned defaults.
  output.attach();
  storageReady=nvs.begin("breeze-ir",false);
  if(storageReady){
    verified=nvs.getBool("verified",false);
    const uint32_t seconds=nvs.getUInt(doubleHitKey,0);
    doubleHitSeconds=seconds<=maximumDoubleHitSeconds?uint16_t(seconds):0;
    // setup calls this before BLE initialization, leaving reclaimed space for PHY calibration.
    bool migrationFailed=false;uint8_t migratedCodes=0;
    for(uint8_t i=0;i<SLOT_COUNT;i++){
      Learned code;if(!load(i,code))continue;
      if(!migrationFailed&&nvs.getBytesLength(key(i).c_str())==sizeof(Learned)){
        if(saveCompact(i,code,true))migratedCodes++;
        else {migrationFailed=true;error=STORAGE;Serial.printf("[IR] Compact storage migration stopped at slot %u; no keys cleared.\n",i);}
      }
      if(load(i,code))learnedMask|=1u<<i;
    }
    nvs_stats_t stats={};
    if(nvs_get_stats(nullptr,&stats)==ESP_OK)Serial.printf("[IR] Compact storage: migrated=%u available NVS entries=%u reserve=%u.\n",migratedCodes,unsigned(stats.available_entries),unsigned(radioReserveEntries));
    else Serial.printf("[IR] Compact storage: migrated=%u; NVS statistics unavailable.\n",migratedCodes);
  }else error=STORAGE;
  BreezeLogic::Profile profile;if(storageReady&&loadProfile(profile)){logic.apply(profile);profilePersisted=true;}loadEstimate();
  receiver.enableIRIn(true);receiver.setUnknownThreshold(10);
  Serial.printf("[IR] IRremoteESP8266 %s RX D6/GPIO%u TX D7/GPIO%u learned=0x%03x. Auto enabled; awaiting confirmed fan state.\n",_IRREMOTEESP8266_VERSION_STR,IR_RECEIVE_PIN,IR_TRANSMIT_PIN,learnedMask);
}
void FanRemote::invalidate(uint8_t reason){bool wasKnown=powerKnown;automatic=false;target=0;estimated=0;synchronized=false;normalMode=false;powerKnown=false;powerOn=false;retainedSpeed=0;logicDirection=0;error=reason;if(wasKnown)saveEstimate();}
void FanRemote::cancelAutomatic(uint8_t reason){automatic=false;target=0;error=reason;}
void FanRemote::disconnected(){
  if(receiverTestActive()){testState=4;receiver.resume();Serial.println("[IR CHECK] Canceled: phone disconnected.");}
  // Cancel unfinished manual work when the phone disconnects.
  target=0;
  if(learning!=NONE){learning=NONE;learnState=CANCELED;}
}
uint8_t FanRemote::command(const uint8_t* b){
  const uint8_t action=b[4],value=b[5];uint32_t now=millis();
  if(action==LOGIC_CLOCK){
    if(value!=1||b[18]||b[19]||!windowClock.set(now,get32(b+6),int16_t(get16(b+10)),get32(b+12),int16_t(get16(b+16))))return error=BAD_VALUE;
    return error=OK; // A clock update never resumes automation or touches the archive clock.
  }
  if(action==DOUBLE_HIT){
    const uint16_t seconds=get16(b+6);
    if(value!=0||seconds>maximumDoubleHitSeconds)return error=BAD_VALUE;
    if(seconds!=doubleHitSeconds){
      // Apply only after the durable setting was accepted. A failed save leaves
      // both the active policy and any accepted command's pending burst intact.
      if(!storageReady||nvs.putUInt(doubleHitKey,seconds)!=sizeof(uint32_t))return error=STORAGE;
      doubleHitSeconds=seconds;repeatSlot=NONE;
    }
    if(!seconds)repeatSlot=NONE; // Zero always vetoes a queued second burst.
    return error=OK;
  }
  if(receiverTestActive()&&action!=CANCEL)return error=BUSY;
  if((repeatSlot!=NONE||heldSlot!=NONE)&&(action==SEND||action==LEARN||action==CLEAR||action==RECEIVER_TEST))return error=BUSY;
  if((action>=LOGIC_GLOBAL&&action<=LOGIC_COMMIT)||action==LOGIC_WINDOW)return profileCommand(b);
  switch(action){
    case RECEIVER_TEST:
      if(value!=0||get16(b+6)!=0)return error=BAD_VALUE;
      if(learning!=NONE)return error=BUSY;
      automatic=false;target=0;logicDirection=0;error=OK;testState=1;testAttempts=testMatched=testFrames=testOverflows=0;
      testRawLen=0;testId++;testNext=now+5000;ignoreReceiveUntil=0;receiver.resume();
      Serial.printf("[IR CHECK] Test %u starts in 5 seconds. Aim D7 LED at D6 receiver; three 38kHz test bursts.\n",testId);return OK;
    case LEARN:
      if(value>=SLOT_COUNT||get16(b+6)<30||get16(b+6)>60)return error=BAD_VALUE;
      if(!storageReady)return error=STORAGE;
      invalidate(OK);learning=value;learnResultSlot=value;learnCarrier=get16(b+6);learningAt=now;learnState=WAITING;receiver.resume();
      Serial.printf("[IR] Learn %s: point remote and press once (30s). Existing code retained until save succeeds. Raw carrier configured %ukHz, not measured.\n",name(value),learnCarrier);return OK;
    case CANCEL:if(receiverTestActive())testState=4;learning=NONE;learnState=CANCELED;receiver.resume();return error=OK;
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
      // Horizontal oscillation is independent of automatic speed control.
      if(value!=4){automatic=false;target=0;}
      {
        const uint8_t sent=transmit(value);
        // The press is accepted now and leaves from tick() once the display state is certain.
        if(sent==HELD)heldSlot=value;
        else if(sent!=OK)return error;
      }
      if(value==1||value==2){if(synchronized&&powerOn){
        // Dreo's running levels cycle through 1..9,H (H is 10 on the wire).
        estimated=value==1?(estimated==10?1:estimated+1):(estimated==1?10:estimated-1);
        retainedSpeed=estimated;if(!logicWindows.enabled())logicBaseTarget=estimated;saveEstimate();
      }else invalidate(NEED_SYNC);}
      else if(value==0){
        if(powerKnown&&((powerOn&&estimated>=1&&estimated<=10)||(!powerOn&&retainedSpeed>=1&&retainedSpeed<=10))){
          if(powerOn){retainedSpeed=estimated;powerOn=false;estimated=0;}else{powerOn=true;estimated=retainedSpeed;}
          synchronized=true;logicDirection=0;if(!logicWindows.enabled())logicBaseTarget=estimated;saveEstimate();
        }else invalidate(NEED_SYNC);
      }else if(value!=4&&value!=5)invalidate(OK); // Mode/extra semantics are unknown.
      return error=OK;
    case TARGET:
      if(value<1||value>10)return error=BAD_VALUE;
      if(learning!=NONE)return error=BUSY;
      if(!synchronized||!normalMode||!powerOn)return error=NEED_SYNC;
      if(value!=estimated&&!(learnedMask&(1u<<(value>estimated?1:2))))return error=NOT_LEARNED;
      automatic=false;target=value;return error=OK;
    case SYNC:
      if(value>10||b[6]!=1)return error=BAD_VALUE;
      if(learning!=NONE)return error=BUSY;
      target=0;estimated=value;synchronized=true;normalMode=true;powerKnown=true;powerOn=value>0;if(value)retainedSpeed=value;logicDirection=0;
      // Reported physical output includes the window adjustment; it is not the ordinary baseline.
      if(!logicWindows.enabled())logicBaseTarget=value;
      saveEstimate();
      Serial.printf("[FAN] User reports fan setting %u in Normal mode (0=Off, 10=H). Estimated only.\n",value);return error=OK;
    case AUTO:
      if(value>1)return error=BAD_VALUE;
      if(!value){cancelAutomatic(OK);return OK;}
      automatic=true;target=0;logicDirection=0;return error=OK;
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
  recordReceiverInput(result,int32_t(monitorSendUntil-now)>0);
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
FanRemote::Display FanRemote::displayState(uint32_t now)const{
  if(!doubleHitSeconds)return AWAKE; // The idle second press is opt-in.
  // No press since boot: the last one predates the restart, so the display has gone dark.
  if(!pressedOnce)return ASLEEP;
  const uint32_t idle=now-lastPress; // Unsigned arithmetic survives millis() rollover.
  if(idle<uint32_t(doubleHitSeconds)*displayAwakeBeforeMs)return AWAKE;
  return idle>=uint32_t(doubleHitSeconds)*displayAsleepFromMs?ASLEEP:UNSURE;
}
uint8_t FanRemote::transmit(uint8_t slot){
  if(repeatSlot!=NONE)return error=BUSY;
  const Display display=displayState(millis());
  // Sent now, this press could land as a step, as a mere wake-up, or with its repeat as two steps.
  if(display==UNSURE)return HELD;
  uint8_t result=transmitOnce(slot);
  // A dark display only lights up on the first press, so the same full burst follows once.
  if(result==OK&&display==ASLEEP)repeatSlot=slot;
  return result;
}
void FanRemote::sendTimings(uint16_t* raw,uint16_t count,uint16_t carrier){
  if(output.sendRaw(raw,count,carrier))return;
  // Without the hardware timer, software timing is the only way left to send this burst.
  if(softwareBursts<255)softwareBursts++;
  sender.begin();sender.sendRaw(raw,count,carrier);
}
uint8_t FanRemote::transmitOnce(uint8_t slot){
  Learned code;if(!load(slot,code))return error=NOT_LEARNED;
  receiver.resume();bool sent=false;
  // Preserve the full learned Dreo/Symphony burst; its generic sender replaces
  // the captured 8/9 frames with only four reconstructed frames.
  if(code.decoded&&code.protocol!=SYMPHONY){
    uint64_t value=code.value;
    if(rcToggle&&code.protocol==RC5)value=sender.toggleRC5(value);
    if(rcToggle&&code.protocol==RC6)value=sender.toggleRC6(value,code.bits);
    // The library times its own protocols in software and needs the pin to itself.
    output.release();sender.begin();
    sent=sender.send((decode_type_t)code.protocol,value,code.bits,0);
  }
  if(!sent)sendTimings(code.raw,code.count,code.carrier);
  if(code.protocol==RC5||code.protocol==RC6)rcToggle=!rcToggle;
  lastSend=millis();sentOnce=true;lastPress=lastSend;pressedOnce=true;ignoreReceiveUntil=lastSend+250;monitorSendUntil=lastSend+250;
  lastSent=slot;sendCounter++;Serial.printf("[IR] TX %s via %s; physical response unconfirmed, estimated speed=%d\n",name(slot),sent?"decoded protocol":"raw timings",estimated);return error=OK;
}
void FanRemote::tick(float a,float b,float room,uint32_t now,float body2){
  windowClock.advance(now);
  thirdProbe=body2;controlBody=NAN;source=0;ambient=room;
  float bodies[3]={a,b,body2};for(uint8_t i=0;i<3;i++)if(validBody(bodies[i])&&(!validBody(controlBody)||bodies[i]>controlBody)){controlBody=bodies[i];source=i+1;}
  logic.sample(now,a,b,body2);
  if(receiverTestActive()){receiverTestTick(now);return;}
  capture(now);
  if(repeatSlot!=NONE){
    // Repeat the full captured burst once, without changing the estimate twice.
    // Subsequent automatic steps measure their minimum gap from this burst end.
    if(!doubleHitSeconds)repeatSlot=NONE;
    else if(uint32_t(millis()-lastSend)>=300){uint8_t slot=repeatSlot;repeatSlot=NONE;transmitOnce(slot);}
    return;
  }
  if(heldSlot!=NONE){
    // An accepted manual press waits until the display state is certain; nothing else sends first.
    if(transmit(heldSlot)!=HELD)heldSlot=NONE;
    return;
  }
  if(learning!=NONE&&uint32_t(now-learningAt)>=30000){learnResultSlot=learning;learning=NONE;learnState=EXPIRED;error=TIMEOUT;Serial.println("[IR] Learning timed out; prior command retained.");}
  if(learning!=NONE)return;
  if(target){
    if(!synchronized){target=0;error=NEED_SYNC;return;}
    if(target==estimated){target=0;return;}
    if(!sentOnce||uint32_t(millis()-lastSend)>=automaticStepIntervalMs){int direction=target>estimated?1:-1;const uint8_t sent=transmit(direction>0?1:2);if(sent==OK){estimated+=direction;retainedSpeed=estimated;saveEstimate();if(target==estimated)target=0;}else if(sent!=HELD)target=0;}
    return;
  }
  if(automatic)automaticTick(now);
}
void FanRemote::receiverTestTick(uint32_t now){
  decode_results result;
  if(receiver.decode(&result)){
    if(testState==2&&int32_t(now-testDeadline)<0){
      recordReceiverInput(result,true,true);
      testRawLen=result.rawlen;
      if(result.overflow){if(testOverflows<255)testOverflows++;}
      else if(!result.repeat&&result.rawlen>=11){
        if(!testWindowSeen){testFrames++;testWindowSeen=true;}
        if(!testWindowMatched&&matchesTest(result)){testMatched++;testWindowMatched=true;}
      }
    }
    receiver.resume();
  }
  if(testState==2&&int32_t(now-testDeadline)>=0){
    Serial.printf("[IR CHECK] Burst %u: matched=%u frames=%u rawlen=%u idle=%u\n",testAttempts,testWindowMatched,testWindowSeen,testRawLen,testIdle);
    if(testAttempts==3){
      testState=3;receiver.resume();ignoreReceiveUntil=now+180;error=OK;
      Serial.printf("[IR CHECK] RESULT: %s; matched=%u/3 frames=%u/3 overflow=%u\n",testMatched==3?"PASS":testMatched==0?"NO MATCHING IR DETECTED":"PARTIAL",testMatched,testFrames,testOverflows);
      return;
    }
    testState=1;
  }
  if(testState==1&&int32_t(now-testNext)>=0){
    receiver.resume();testIdle=digitalRead(IR_RECEIVE_PIN);testWindowSeen=testWindowMatched=false;
    uint16_t timings[testTimingCount];testTimings(timings);
    uint32_t sentAt=millis();testAttempts++;testState=2;testDeadline=sentAt+250;testNext=sentAt+1000;
    // Keep the receiver and its interrupt enabled: this test needs real optical feedback.
    sendTimings(timings,testTimingCount,38);lastSend=millis();sentOnce=true;
  }
}
void FanRemote::recordReceiverInput(decode_results& result,bool duringSend,bool diagnostic){
  if(!result.overflow&&!result.repeat&&result.rawlen<11)return;
  inputFlags=1|(duringSend?2:0)|(result.repeat?4:0)|(result.overflow?8:0)|(diagnostic?16:0);
  inputSequence++;inputRawLength=result.rawlen;
  if(result.repeat)return;
  inputSlot=NONE;inputProtocol=result.decode_type;inputBits=result.bits>255?255:result.bits;inputValue=result.value;
  if(result.overflow||diagnostic)return;
  for(uint8_t slot=0;slot<SLOT_COUNT;slot++){
    if(!(learnedMask&(1u<<slot)))continue;
    Learned code;if(!load(slot,code)||!code.decoded||code.protocol!=result.decode_type||code.bits!=result.bits)continue;
    if(code.value==result.value||(code.protocol==RC5&&sender.toggleRC5(code.value)==result.value)||(code.protocol==RC6&&sender.toggleRC6(code.value,code.bits)==result.value)){inputSlot=slot;return;}
  }
  uint16_t count=getCorrectedRawLength(&result);if(count<10||count>MAX_RAW)return;
  uint16_t* measured=resultToRawArray(&result);if(!measured)return;
  for(uint8_t slot=0;slot<SLOT_COUNT;slot++){
    if(!(learnedMask&(1u<<slot)))continue;
    Learned code;if(!load(slot,code)||code.count!=count)continue;
    bool matches=true;for(uint16_t i=0;i<count;i++){int delta=int(measured[i])-int(code.raw[i]);if(delta<0)delta=-delta;if(delta>int(code.raw[i]/5+100)){matches=false;break;}}
    if(matches){inputSlot=slot;break;}
  }
  delete[] measured;
}
void FanRemote::receiverInputPacket(uint8_t* b)const{
  memset(b,0,20);b[0]=8;b[1]=inputFlags;b[2]=inputSlot;b[3]=inputBits;
  set16(b+4,inputProtocol);set16(b+6,inputSequence);
  for(uint8_t i=0;i<8;i++)b[8+i]=uint8_t(inputValue>>(i*8));
  set16(b+16,inputRawLength);b[19]=1;
}
bool FanRemote::loadProfile(BreezeLogic::Profile& profile){
  return storageReady&&nvs.getBytesLength(rangeProfileKey)==sizeof(profile)&&nvs.getBytes(rangeProfileKey,&profile,sizeof(profile))==sizeof(profile)&&
    BreezeLogic::valid(profile)&&profile.checksum==BreezeLogic::checksum(profile);
}
void FanRemote::saveEstimate(){
  if(!storageReady)return;FanEstimate state;state.known=powerKnown;state.on=powerOn;state.speed=estimated;state.retained=retainedSpeed;state.normal=normalMode;
  state.wanted=logicBaseTarget;state.checksum=estimateChecksum(state);nvs.putBytes("fan-estimate",&state,sizeof(state));
}
void FanRemote::loadEstimate(){
  FanEstimate state;if(!storageReady||nvs.getBytesLength("fan-estimate")!=sizeof(state)||nvs.getBytes("fan-estimate",&state,sizeof(state))!=sizeof(state)||
    state.magic!=0x42464553||state.checksum!=estimateChecksum(state)||state.known!=1||state.on>1||state.normal!=1||state.speed>10||state.retained>10||state.wanted>10||
    (state.on&&state.speed<1)||(!state.on&&state.speed!=0))return;
  powerKnown=synchronized=normalMode=true;powerOn=state.on;estimated=state.speed;retainedSpeed=state.retained;logicBaseTarget=state.wanted;
}
bool FanRemote::saveProfile(BreezeLogic::Profile& profile){
  if(!storageReady)return false;profile.checksum=BreezeLogic::checksum(profile);
  if(nvs.putBytes(rangeProfileKey,&profile,sizeof(profile))!=sizeof(profile))return false;
  BreezeLogic::Profile check;return loadProfile(check)&&check.checksum==profile.checksum;
}
uint8_t FanRemote::profileCommand(const uint8_t* b){
  if(learning!=NONE)return error=BUSY;
  switch(b[4]){
    case LOGIC_GLOBAL:
      // Byte 15 = 3 marks the range layout; earlier threshold uploads are refused here.
      if(b[15]!=3||b[5]!=0)return error=BAD_VALUE;
      stagedProfile=logic.profile;profileReceipt=1;
      memset(stagedWindowAdjustments,0,sizeof(stagedWindowAdjustments));memset(windowReceipt,0,sizeof(windowReceipt));
      stagedWindowFormat=0;stagedWindowCount=stagedWindowReceived=0;windowStageInvalid=stagedWindowsEnabled=false;
      stagedProfile.averageSeconds=get16(b+6);stagedProfile.riseSeconds=get16(b+8);stagedProfile.fallSeconds=get16(b+10);stagedProfile.steadinessCenti=get16(b+12);return error=OK;
    case LOGIC_RULE:
      if(!(profileReceipt&1)||b[5]!=0||b[16]!=3)return error=BAD_VALUE;
      stagedProfile.offRoomCentiF=int16_t(get16(b+6));stagedProfile.offBodyCentiF=int16_t(get16(b+8));
      stagedProfile.fullRoomCentiF=int16_t(get16(b+10));stagedProfile.fullBodyCentiF=int16_t(get16(b+12));stagedProfile.bodyWeightCenti=get16(b+14);profileReceipt|=2;return error=OK;
    case LOGIC_BOOST:{
      if(!(profileReceipt&1)||b[5]!=0||b[19]!=3)return error=BAD_VALUE;
      auto& boost=stagedProfile.boost;boost.enabled=b[6];boost.extra=b[7];boost.triggerCentiF=get16(b+8);
      boost.windowSeconds=get16(b+10);boost.riseSeconds=get16(b+12);boost.holdSeconds=get16(b+14);boost.returnSeconds=get16(b+16);boost.curve=b[18];
      profileReceipt|=4;return error=OK;
    }
    case LOGIC_WINDOW:{
      if(!(profileReceipt&1)||windowStageInvalid)return error=BAD_VALUE;
      // A malformed/mixed upload cannot accidentally commit a partial replacement.
      auto invalid=[this](){windowStageInvalid=true;return error=BAD_VALUE;};
      const uint8_t format=b[11];
      if(format!=1&&format!=2)return invalid();
      for(uint8_t i=format==1?12:16;i<20;i++)if(b[i])return invalid();
      const uint16_t index=format==1?0:get16(b+12),count=format==1?1:get16(b+14);
      if(count<1||count>BreezeLogic::MAX_TIME_WINDOW_SEGMENTS||index>=count)return invalid();
      if(stagedWindowFormat&&(stagedWindowFormat!=format||stagedWindowCount!=count))return invalid();
      if(windowReceipt[index/8]&(1u<<(index%8)))return invalid();
      BreezeLogic::TimeWindow candidate;candidate.enabled=b[5];candidate.startMinutes=get16(b+6);candidate.endMinutes=get16(b+8);candidate.adjustment=int8_t(b[10]);
      if(!candidate.valid())return invalid();
      // Accumulate in 16 bits without clamping so mixed signs remain order-independent.
      for(uint16_t minute=0;minute<BreezeLogic::MINUTES_PER_DAY;minute++)if(candidate.contains(minute))stagedWindowAdjustments[minute]+=candidate.adjustment;
      stagedWindowCount=count;stagedWindowFormat=format;stagedWindowsEnabled|=candidate.enabled!=0;
      windowReceipt[index/8]|=uint8_t(1u<<(index%8));stagedWindowReceived++;profileReceipt|=8;return error=OK;
    }
    case LOGIC_COMMIT:{
      if((profileReceipt!=7&&profileReceipt!=15)||windowStageInvalid||!BreezeLogic::valid(stagedProfile)||
        stagedWindowReceived!=stagedWindowCount)return error=BAD_VALUE;
      bool changed=!BreezeLogic::sameSettings(stagedProfile,logic.profile);
      if(changed||!profilePersisted){
        stagedProfile.revision=logic.profile.revision+(changed?1:0);if(changed&&!stagedProfile.revision)stagedProfile.revision=1;
        if(!saveProfile(stagedProfile))return error=STORAGE;
        if(changed)logic.apply(stagedProfile,true);else logic.profile=stagedProfile;
      }
      // Off/H bounds make offsets beyond +/-10 equivalent; clamp only the completed sum.
      for(uint16_t minute=0;minute<BreezeLogic::MINUTES_PER_DAY;minute++){
        const int value=stagedWindowAdjustments[minute];
        logicWindows.adjustments[minute]=value<-BreezeLogic::MAX_LEVEL?-BreezeLogic::MAX_LEVEL:value>BreezeLogic::MAX_LEVEL?BreezeLogic::MAX_LEVEL:value;
      }
      logicWindows.count=stagedWindowCount;logicWindows.anyEnabled=stagedWindowsEnabled;
      profilePersisted=true;profileReceipt=0;automatic=true;target=0;if(changed){logicDirection=0;logicDecision={};}
      Serial.printf("[LOGIC] Applied range revision %u; automation enabled.\n",logic.profile.revision);return error=OK;
    }
  }
  return error=BAD_VALUE;
}
void FanRemote::automaticTick(uint32_t now){
  logicDecision=logic.evaluate(now,ambient,logicBaseTarget,!logicWindows.enabled()||logicBaselineReady);
  // Without a room or body reading the fan stays where it is.
  if(!logicDecision.ready){logicWanted=estimated;logicDirection=0;error=logic.bodyAvailable(now)?NO_ROOM:NO_BODY;return;}
  logicBaseTarget=logicDecision.baseline;logicBaselineReady=true;
  // Add every active window to the ordinary Logic/boost target, then clamp once.
  logicWanted=BreezeLogic::adjustedTarget(logicDecision.target,windowClock.adjustment(logicWindows));
  if((learnedMask&7)!=7){error=NOT_LEARNED;return;}
  if(!synchronized||!normalMode||!powerKnown){error=NEED_SYNC;return;}
  error=OK;
  if(!powerOn){
    if(!logicWanted){logicDirection=0;error=OK;return;}
    if(retainedSpeed<1||retainedSpeed>10){error=NEED_SYNC;return;}
    if(sentOnce&&uint32_t(millis()-lastSend)<automaticStepIntervalMs)return;
    if(transmit(0)==OK){powerOn=true;estimated=retainedSpeed;logicDirection=0;saveEstimate();}return;
  }
  if(!logicWanted&&estimated==1){
    if(estimated<1||estimated>10){error=NEED_SYNC;return;}
    if(sentOnce&&uint32_t(millis()-lastSend)<automaticStepIntervalMs)return;
    retainedSpeed=1;if(transmit(0)==OK){powerOn=false;estimated=0;logicDirection=0;saveEstimate();}return;
  }
  if(estimated<1||estimated>10){error=NEED_SYNC;return;}
  int desired=logicWanted?logicWanted:1;
  if(desired==estimated){logicDirection=0;error=OK;return;}
  int direction=desired>estimated?1:-1;uint32_t interval=logic.stepIntervalMs(direction>0);if(interval<automaticStepIntervalMs)interval=automaticStepIntervalMs;
  // The boost curve supplies its own rise/return schedule; follow each discrete level.
  if(!logicDecision.boost){
    if(direction!=logicDirection){logicDirection=direction;logicNextStep=now+interval;return;}
    if(int32_t(now-logicNextStep)<0)return;
  }
  if(sentOnce&&uint32_t(millis()-lastSend)<automaticStepIntervalMs)return;
  if(transmit(direction>0?1:2)==OK){estimated+=direction;retainedSpeed=estimated;logicDirection=direction;logicNextStep=millis()+interval;saveEstimate();}
}
void FanRemote::logicStatusPacket(uint8_t* b,uint32_t now)const{
  const bool scored=automatic&&logicDecision.ready&&isfinite(logicDecision.score);
  memset(b,0,20);b[0]=9;b[1]=(automatic?1:0)|(automatic&&logicDecision.boost?2:0)|4|
    ((!synchronized||!powerKnown||(!powerOn&&retainedSpeed==0&&logicWanted>0))?8:0)|(!logic.bodyAvailable(now)?16:0)|(validBody(thirdProbe)?32:0)|(scored?64:0);
  b[2]=logicDecision.ready?0:255;b[3]=automatic?logicWanted:target;
  float average=logic.averageF(now);set16(b+4,isfinite(average)?lroundf(average*100):0);set16(b+6,isfinite(ambient)?lroundf(BreezeLogic::fahrenheit(ambient)*100):0);
  set16(b+8,automatic?logicDecision.boostSeconds:0);set16(b+10,logic.profile.revision);b[12]=error;b[13]=BreezeLogic::ALL_PROBES;
  set16(b+14,validBody(thirdProbe)?lroundf(thirdProbe*100):0);
  // Signed centi-levels: 0 is the Off point and 1000 the Full point.
  if(scored){float value=logicDecision.score*100;set16(b+16,int16_t(value>30000?30000:value<-30000?-30000:lroundf(value)));}
  b[18]=automatic?uint8_t(windowClock.adjustment(logicWindows)):0;
  b[19]=1;
}
void FanRemote::receiverTestPacket(uint8_t* b,uint32_t now)const{
  memset(b,0,20);b[0]=7;b[1]=testState;b[2]=testAttempts;b[3]=testMatched;b[4]=testFrames;
  if(testState==1&&int32_t(testNext-now)>0)b[5]=uint8_t((testNext-now+999)/1000);
  b[6]=testOverflows;set16(b+8,testId);b[10]=error;set16(b+11,testRawLen);
  b[13]=testIdle;b[14]=38;b[15]=IR_TRANSMIT_PIN;b[16]=IR_RECEIVE_PIN;b[19]=1;
}
void FanRemote::statusPacket(uint8_t* b,float rawBody1,uint8_t phase)const{
  memset(b,0,20);b[0]=5;b[1]=(validBody(rawBody1)?1:0)|(automatic?2:0)|(synchronized?4:0)|(verified?8:0)|(normalMode?16:0)|(target?32:0)|(heldSlot!=NONE?64:0);
  set16(b+2,validBody(rawBody1)?lroundf(rawBody1*100):0);b[4]=estimated;b[5]=target;b[6]=learning;b[7]=learnState;set16(b+8,learnedMask);b[10]=error;b[11]=source;
  set16(b+12,validBody(controlBody)?lroundf(controlBody*100):0);b[14]=learnResultSlot;b[15]=lastSent;set16(b+16,sendCounter);b[18]=phase;b[19]=7;
}

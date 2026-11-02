"""Exercise display-aware IR double hits in actual firmware with local hardware spies."""
import ast
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
tree = ast.parse((ROOT / 'tests/ir_test.py').read_text(encoding='utf-8'))
assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == 'stub' for target in node.targets))
stub = ast.literal_eval(assignment.value)

# Keep this test's uint32 persistence and failure model local to its fixture.
stub = stub.replace('class Preferences{public:', '''
inline std::map<std::string,uint32_t> savedUInts;
inline int uintWrites=0;
class Preferences{public:''')
stub, count = re.subn(r' size_t putUInt\([^\n]+',
                     ' size_t putUInt(const char* key,uint32_t value){nvsWrites++;uintWrites++;'
                     'if(failStorage)return 0;savedUInts[key]=value;return sizeof(uint32_t);}', stub)
assert count == 1
stub, count = re.subn(r' uint32_t getUInt\([^\n]+\n', '', stub)
assert count <= 1
stub = stub.replace('class Preferences{public:', '''class Preferences{public:
 uint32_t getUInt(const char* key,uint32_t fallback=0){auto found=savedUInts.find(key);return found==savedUInts.end()?fallback:found->second;}''')
stub = stub.replace('inline int transmissions=0,rawTransmissions=0;', '''
inline int transmissions=0,rawTransmissions=0;
inline std::vector<uint32_t> txStarts,txEnds;
inline std::vector<uint64_t> txValues;
inline std::vector<std::vector<uint16_t>> txRawFrames;''')
stub = stub.replace('bool send(decode_type_t,uint64_t,uint16_t,int){if(sendSupported)transmissions++;return sendSupported;}',
                    'bool send(decode_type_t,uint64_t value,uint16_t,int){if(sendSupported){'
                    'txStarts.push_back(clockMs);txValues.push_back(value);transmissions++;'
                    'clockMs+=37;txEnds.push_back(clockMs);}return sendSupported;}')
stub = stub.replace('transmissions++;rawTransmissions++;lastRaw.assign(raw,raw+count);',
                    'txStarts.push_back(clockMs);transmissions++;rawTransmissions++;lastRaw.assign(raw,raw+count);'
                    'txRawFrames.push_back(lastRaw);clockMs+=113;txEnds.push_back(clockMs);')
assert 'clockMs+=37;' in stub and 'clockMs+=113;' in stub

HARNESS = r'''
#include "fake.h"
#define private public
#include "FanRemote.h"
#undef private
#include "FanRemote.cpp"

constexpr const char* configKey="ir-double-time";
int action(FanRemote& f,int kind,int value=0,int argument=0){
 uint8_t b[20]={1,6};b[4]=kind;b[5]=value;b[6]=argument;b[7]=argument>>8;return f.command(b);
}
int configure(FanRemote& f,int seconds,int reserved=0){return action(f,FanRemote::DOUBLE_HIT,reserved,seconds);}
float celsius(float f){return (f-32)/1.8f;}
void at(FanRemote& f,uint32_t time,float body=98,float room=74){clockMs=time;f.tick(celsius(body),NAN,celsius(room),clockMs);}
void prepare(FanRemote& f,bool raw=false,int setting=1){
 saved.clear();savedUInts.clear();clockMs=0;pending=scheduledCapture=false;loopbackMode=0;sendSupported=true;failStorage=false;
 transmissions=rawTransmissions=nvsWrites=nvsRemoves=uintWrites=0;
 txStarts.clear();txEnds.clear();txValues.clear();txRawFrames.clear();rawCarriers.clear();rawSenderPins.clear();
 f=FanRemote();f.begin();assert(transmissions==0&&uintWrites==0);
 // Raw replay uses two 12-bit frame trains separated by a longer pause, ending on a mark like a real capture.
 FanRemote::Learned code;code.count=raw?47:12;code.bits=12;code.protocol=raw?SYMPHONY:NEC;code.decoded=1;code.value=123;
 for(int i=0;i<code.count;i++)code.raw[i]=i%2?(i%24==23?8000:1200):600;
 for(int slot=0;slot<6;slot++){code.value=123+slot;assert(f.save(slot,code));f.learnedMask|=1u<<slot;}
 BreezeLogic::Profile profile;profile.averageSeconds=1;profile.riseSeconds=profile.fallSeconds=1;profile.steadinessCenti=0;
 f.logic.apply(profile);assert(action(f,FanRemote::SYNC,setting,1)==FanRemote::OK);
}
// Idle milliseconds, counted from the end of the last press, at which the display stops being
// certainly lit and starts being certainly dark.
uint32_t unsureFrom(int seconds){return uint32_t(seconds)*displayAwakeBeforeMs;}
uint32_t darkFrom(int seconds){return uint32_t(seconds)*displayAsleepFromMs;}
bool held(FanRemote& f){uint8_t b[20];f.statusPacket(b,NAN,0);return (b[1]&64)!=0;}
void assertNoRepeat(FanRemote& f,uint32_t end,int count,int setting){
 for(uint32_t elapsed:{299u,300u,1000u}){
  at(f,end+elapsed);assert(transmissions==count&&f.estimated==setting&&f.lastSend==end&&!held(f));
 }
}
void assertPair(FanRemote& f,uint32_t end,int count,int setting,bool raw){
 assert(end==clockMs&&end==txEnds.back());
 at(f,end+299);assert(transmissions==count&&f.estimated==setting&&f.lastSend==end);
 at(f,end+300);assert(transmissions==count+1&&f.estimated==setting&&f.recordedSetting()==setting);
 assert(txStarts.back()==end+300&&f.lastSend==txEnds.back());
 assert(f.lastSend==end+300+(raw?113u:37u));
 if(raw){assert(txRawFrames.size()>=2&&txRawFrames.back().size()==47&&txRawFrames[txRawFrames.size()-2]==txRawFrames.back());assert(rawCarriers.back()==38);}
 else assert(txValues.size()>=2&&txValues[txValues.size()-2]==txValues.back());
 at(f,f.lastSend+300);assert(transmissions==count+1&&f.estimated==setting);
}
// One accepted Speed + as the first press since boot: a wake burst and its repeat. Returns the
// end of the repeat, where the fan's display timer starts.
uint32_t light(FanRemote& f,bool raw){
 const int count=transmissions,setting=f.estimated;
 assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==count+1&&f.estimated==setting+1);
 assertPair(f,f.lastSend,count+1,setting+1,raw);clockMs=f.lastSend;return f.lastSend;
}
void off_is_always_single(){
 for(bool raw:{false,true})for(uint32_t idle:{0u,52500u,60000u,66000u,3600000u,UINT32_MAX-500u}){
  FanRemote f;prepare(f,raw);clockMs=idle;
  assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==1&&f.estimated==2);
  assertNoRepeat(f,f.lastSend,1,2);
  clockMs=f.lastSend+idle;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==2&&f.estimated==3);
  assertNoRepeat(f,f.lastSend,2,3);
 }
}
void first_press_since_boot_wakes(){
 // Whatever the uptime, the last press predates the restart, so the display is treated as dark.
 for(bool raw:{false,true})for(int seconds:{2,60,3600})for(uint32_t uptime:{0u,1u,unsureFrom(seconds)-1,unsureFrom(seconds),darkFrom(seconds),darkFrom(seconds)+5000}){
  FanRemote f;prepare(f,raw);assert(configure(f,seconds)==FanRemote::OK);clockMs=uptime;
  assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==1&&f.sendCounter==1&&f.estimated==2&&!held(f));
  assertPair(f,f.lastSend,1,2,raw);assert(f.sendCounter==2);
 }
}
void lit_unsure_and_dark_bands(){
 for(bool raw:{false,true})for(int seconds:{2,60,3600}){
  const uint32_t unsure=unsureFrom(seconds),dark=darkFrom(seconds);assert(unsure<dark);
  // Certainly lit: one burst, estimate advanced once, nothing held.
  for(uint32_t idle:{1000u,unsure-1}){
   FanRemote f;prepare(f,raw);assert(configure(f,seconds)==FanRemote::OK);uint32_t lit=light(f,raw);
   clockMs=lit+idle;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==3&&f.estimated==3&&!held(f));
   assertNoRepeat(f,f.lastSend,3,3);
  }
  // Certainly dark: the wake burst and its repeat, counted from the second burst of the last pair.
  for(uint32_t idle:{dark,dark+1,dark+500000}){
   FanRemote f;prepare(f,raw);assert(configure(f,seconds)==FanRemote::OK);uint32_t lit=light(f,raw);
   clockMs=lit+idle;assert(action(f,FanRemote::SEND,2)==FanRemote::OK&&transmissions==3&&f.estimated==1&&!held(f));
   assertPair(f,f.lastSend,3,1,raw);
  }
  // In between, the press is accepted at once but leaves only when the display is certainly dark.
  for(uint32_t idle:{unsure,unsure+1,(unsure+dark)/2,dark-1}){
   FanRemote f;prepare(f,raw);assert(configure(f,seconds)==FanRemote::OK);uint32_t lit=light(f,raw);
   clockMs=lit+idle;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==2&&f.estimated==3&&f.recordedSetting()==3&&held(f)&&f.sendCounter==2);
   auto stored=saved;int removes=nvsRemoves;
   // Until it has left, nothing else may reach the LED or the learned codes.
   assert(action(f,FanRemote::SEND,2)==FanRemote::BUSY&&action(f,FanRemote::LEARN,1,38)==FanRemote::BUSY);
   assert(action(f,FanRemote::CLEAR,1)==FanRemote::BUSY&&action(f,FanRemote::RECEIVER_TEST)==FanRemote::BUSY);
   assert(transmissions==2&&f.estimated==3&&held(f)&&saved==stored&&nvsRemoves==removes);
   at(f,lit+idle);assert(transmissions==2&&held(f));
   at(f,lit+dark-1);assert(transmissions==2&&held(f)&&f.estimated==3);
   at(f,lit+dark);assert(transmissions==3&&txStarts.back()==lit+dark&&!held(f)&&f.estimated==3&&f.lastSent==1);
   assertPair(f,f.lastSend,3,3,raw);assert(f.sendCounter==4);
   // The display is lit again: the next press inside the lit band is a single burst.
   clockMs=f.lastSend+unsure-1;assert(action(f,FanRemote::SEND,2)==FanRemote::OK&&transmissions==5&&f.estimated==2);
   assertNoRepeat(f,f.lastSend,5,2);
  }
 }
}
void bands_survive_clock_rollover(){
 for(bool raw:{false,true}){
  const uint32_t unsure=unsureFrom(60),dark=darkFrom(60);
  FanRemote f;prepare(f,raw);assert(configure(f,60)==FanRemote::OK);clockMs=UINT32_MAX-2000;uint32_t lit=light(f,raw);
  clockMs=lit+unsure-1;assert(clockMs<lit&&action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==3);assertNoRepeat(f,f.lastSend,3,3);
  prepare(f,raw);assert(configure(f,60)==FanRemote::OK);clockMs=UINT32_MAX-2000;lit=light(f,raw);
  clockMs=lit+unsure;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==2&&held(f));
  at(f,lit+dark-1);assert(transmissions==2&&held(f));
  at(f,lit+dark);assert(transmissions==3&&!held(f));assertPair(f,f.lastSend,3,3,raw);
  prepare(f,raw);assert(configure(f,1)==FanRemote::OK);clockMs=UINT32_MAX-200;
  assert(action(f,FanRemote::SEND,1)==FanRemote::OK);assertPair(f,f.lastSend,1,2,raw);
 }
}
void configuration_and_persistence(){
 FanRemote f;prepare(f);auto learned=saved;int writes=nvsWrites;
 assert(configure(f,0)==FanRemote::OK&&nvsWrites==writes&&uintWrites==0&&transmissions==0);
 for(int invalid:{3601,65535})assert(configure(f,invalid)==FanRemote::BAD_VALUE);
 assert(configure(f,60,1)==FanRemote::BAD_VALUE&&nvsWrites==writes&&uintWrites==0&&saved==learned&&transmissions==0);
 assert(configure(f,60)==FanRemote::OK&&uintWrites==1&&savedUInts[configKey]==60&&saved==learned&&transmissions==0);
 writes=nvsWrites;assert(configure(f,60)==FanRemote::OK&&nvsWrites==writes&&uintWrites==1);
 failStorage=true;
 assert(configure(f,120)==FanRemote::STORAGE&&savedUInts[configKey]==60&&uintWrites==2&&saved==learned&&transmissions==0);
 failStorage=false;uint32_t lit=light(f,false);
 // The failed update did not change the live 60 s setting: with 120 s the display would still be lit here.
 clockMs=lit+darkFrom(60);assert(darkFrom(60)<unsureFrom(120)&&action(f,FanRemote::SEND,1)==FanRemote::OK);assertPair(f,f.lastSend,3,3,false);
 writes=nvsWrites;clockMs=0;FanRemote reboot;reboot.begin();
 assert(nvsWrites==writes&&uintWrites==2&&transmissions==4); // Boot loads without writing or emitting IR.
 // The stored 60 s is live after the restart.
 lit=light(reboot,false);
 clockMs=lit+unsureFrom(60)-1;assert(action(reboot,FanRemote::SEND,1)==FanRemote::OK);assertNoRepeat(reboot,reboot.lastSend,7,5);
 clockMs=reboot.lastSend+darkFrom(60);assert(action(reboot,FanRemote::SEND,1)==FanRemote::OK);assertPair(reboot,reboot.lastSend,8,6,false);
 assert(configure(reboot,3600)==FanRemote::OK&&savedUInts[configKey]==3600);
 assert(configure(reboot,0)==FanRemote::OK&&savedUInts[configKey]==0);
 writes=nvsWrites;assert(configure(reboot,0)==FanRemote::OK&&nvsWrites==writes);
 clockMs=0;FanRemote disabled;disabled.begin();clockMs=3600000;
 assert(action(disabled,FanRemote::SEND,1)==FanRemote::OK);assertNoRepeat(disabled,disabled.lastSend,10,7);
 // Missing NVS cannot be treated as a successful runtime-only update.
 prepare(f);f.storageReady=false;writes=nvsWrites;
 assert(configure(f,60)==FanRemote::STORAGE&&nvsWrites==writes&&transmissions==0);
 clockMs=60000;f.storageReady=true;assert(action(f,FanRemote::SEND,1)==FanRemote::OK);assertNoRepeat(f,f.lastSend,1,2);
 // Out-of-range stored uint32 values must not truncate into an enabled timeout.
 for(uint32_t invalid:{3601u,65536u,UINT32_MAX}){
  prepare(f);savedUInts[configKey]=invalid;clockMs=0;writes=nvsWrites;FanRemote corrupted;corrupted.begin();
  assert(nvsWrites==writes&&transmissions==0);clockMs=3600000;
  assert(action(corrupted,FanRemote::SEND,1)==FanRemote::OK);assertNoRepeat(corrupted,corrupted.lastSend,1,2);
 }
 // Configuration remains available during the independent receiver diagnostic.
 prepare(f);assert(action(f,FanRemote::RECEIVER_TEST)==FanRemote::OK&&f.receiverTestActive());
 assert(configure(f,60)==FanRemote::OK&&f.receiverTestActive()&&transmissions==0&&savedUInts[configKey]==60);
 assert(configure(f,0)==FanRemote::OK&&f.receiverTestActive()&&transmissions==0&&savedUInts[configKey]==0);
}
void pending_guards_and_cancellation(){
 for(bool raw:{false,true}){
  FanRemote f;prepare(f,raw);assert(configure(f,60)==FanRemote::OK);clockMs=60000;
  assert(action(f,FanRemote::SEND,1)==FanRemote::OK);uint32_t end=f.lastSend;auto stored=saved;int writes=nvsWrites,removes=nvsRemoves;
  assert(action(f,FanRemote::SEND,2)==FanRemote::BUSY);
  assert(action(f,FanRemote::LEARN,1,38)==FanRemote::BUSY);
  assert(action(f,FanRemote::CLEAR,1)==FanRemote::BUSY);
  assert(action(f,FanRemote::RECEIVER_TEST)==FanRemote::BUSY);
  assert(transmissions==1&&f.estimated==2&&saved==stored&&nvsWrites==writes&&nvsRemoves==removes);
  assert(configure(f,60)==FanRemote::OK&&nvsWrites==writes); // Idempotent nonzero config preserves the pair.
  assertPair(f,end,1,2,raw);
  clockMs=f.lastSend+darkFrom(60);assert(action(f,FanRemote::SEND,1)==FanRemote::OK);end=f.lastSend;
  stored=saved;writes=nvsWrites;failStorage=true;
  assert(configure(f,120)==FanRemote::STORAGE&&savedUInts[configKey]==60&&saved==stored&&nvsWrites==writes+1);
  assert(configure(f,0)==FanRemote::STORAGE&&savedUInts[configKey]==60); // Failed persistence leaves pending/runtime unchanged.
  failStorage=false;assertPair(f,end,3,3,raw);
  clockMs=f.lastSend+darkFrom(60);assert(action(f,FanRemote::SEND,1)==FanRemote::OK);end=f.lastSend;
  assert(configure(f,120)==FanRemote::OK&&savedUInts[configKey]==120);assertNoRepeat(f,end,5,4);
  clockMs=f.lastSend+darkFrom(120);assert(action(f,FanRemote::SEND,1)==FanRemote::OK);end=f.lastSend;
  assert(configure(f,0)==FanRemote::OK&&savedUInts[configKey]==0);assertNoRepeat(f,end,6,5);
  clockMs=f.lastSend+3600000;assert(action(f,FanRemote::SEND,1)==FanRemote::OK);assertNoRepeat(f,f.lastSend,7,6);
  // Zero is a hard veto even if stale pending state is already present at zero.
  f.repeatSlot=1;writes=nvsWrites;assert(configure(f,0)==FanRemote::OK&&nvsWrites==writes&&f.repeatSlot==FanRemote::NONE);
  at(f,f.lastSend+300);assert(transmissions==7&&f.estimated==6);
 }
}
void held_press_follows_setting_changes(){
 for(bool raw:{false,true}){
  const uint32_t unsure=unsureFrom(60),dark=darkFrom(60);
  // Turning the feature off releases the held press as one burst.
  FanRemote f;prepare(f,raw);assert(configure(f,60)==FanRemote::OK);uint32_t lit=light(f,raw);
  clockMs=lit+unsure;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&held(f)&&transmissions==2);
  assert(configure(f,0)==FanRemote::OK&&held(f));
  at(f,lit+unsure+10);assert(transmissions==3&&!held(f)&&f.estimated==3);assertNoRepeat(f,f.lastSend,3,3);
  // A longer display time makes the display certainly lit: one burst.
  prepare(f,raw);assert(configure(f,60)==FanRemote::OK);lit=light(f,raw);
  clockMs=lit+unsure;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&held(f));
  assert(configure(f,120)==FanRemote::OK);at(f,lit+unsure+10);assert(transmissions==3&&!held(f));assertNoRepeat(f,f.lastSend,3,3);
  // A shorter one makes it certainly dark: the wake pair leaves at once.
  prepare(f,raw);assert(configure(f,60)==FanRemote::OK);lit=light(f,raw);
  clockMs=lit+unsure;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&held(f));
  assert(configure(f,10)==FanRemote::OK);at(f,lit+unsure+10);assert(transmissions==3&&!held(f));assertPair(f,f.lastSend,3,3,raw);
  // A failed save changes nothing, and losing the phone does not drop the accepted press.
  prepare(f,raw);assert(configure(f,60)==FanRemote::OK);lit=light(f,raw);
  clockMs=lit+unsure;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&held(f));
  failStorage=true;assert(configure(f,0)==FanRemote::STORAGE);failStorage=false;f.disconnected();
  at(f,lit+dark-1);assert(transmissions==2&&held(f));
  at(f,lit+dark);assert(transmissions==3&&!held(f)&&f.estimated==3);assertPair(f,f.lastSend,3,3,raw);
 }
}
void automatic_and_target_pacing(){
 for(bool raw:{false,true})for(bool useTarget:{false,true})for(bool down:{false,true}){
  FanRemote f;int initial=down?3:1;prepare(f,raw,initial);assert(configure(f,60)==FanRemote::OK);
  float body=down?97:98,room=down?66:74;
  if(useTarget){assert(action(f,FanRemote::TARGET,down?1:4)==FanRemote::OK);at(f,60000,body,room);}
  else{at(f,60000,body,room);if(!transmissions)at(f,61000,body,room);}
  int expected=initial+(down?-1:1);
  assert(transmissions==1&&f.estimated==expected&&f.lastSent==(down?2:1));uint32_t end=f.lastSend;
  at(f,end+299,body,room);assert(transmissions==1&&f.estimated==expected);
  at(f,end+300,body,room);assert(transmissions==2&&f.estimated==expected&&f.sendCounter==2);
  assert(txStarts.back()==end+300&&f.lastSend==txEnds.back());end=f.lastSend;
  at(f,end+999,body,room);assert(transmissions==2&&f.estimated==expected);
  at(f,end+1000,body,room);assert(transmissions==3&&f.estimated==expected+(down?-1:1)&&txStarts.back()==end+1000);
  end=f.lastSend;at(f,end+300,body,room);assert(transmissions==3); // This ordinary step is inside the lit band.
 }
}
void automatic_and_target_wait_out_the_unsure_band(){
 const uint32_t unsure=unsureFrom(60),dark=darkFrom(60);
 for(bool raw:{false,true}){
  // A target reached for inside the band keeps waiting; it is neither sent early nor cancelled.
  FanRemote f;prepare(f,raw);assert(configure(f,60)==FanRemote::OK);uint32_t lit=light(f,raw);
  clockMs=lit+unsure;assert(action(f,FanRemote::TARGET,4)==FanRemote::OK);
  for(uint32_t t=lit+unsure;t<lit+dark;t+=250){at(f,t);assert(transmissions==2&&f.estimated==2&&f.target==4&&!held(f)&&f.error==FanRemote::OK);}
  at(f,lit+dark);assert(transmissions==3&&f.estimated==3&&f.target==4&&txStarts.back()==lit+dark);
  uint32_t end=f.lastSend;at(f,end+300);assert(transmissions==4&&f.estimated==3);
  end=f.lastSend;at(f,end+1000);assert(transmissions==5&&f.estimated==4&&f.target==0);at(f,f.lastSend+300);assert(transmissions==5);
  // Automatic control does the same: no burst inside the band, then one wake pair for one step.
  prepare(f,raw);assert(configure(f,60)==FanRemote::OK);lit=light(f,raw);
  assert(action(f,FanRemote::AUTO,1)==FanRemote::OK);
  for(uint32_t t=lit+unsure;t<lit+dark;t+=250){at(f,t,99,76);assert(transmissions==2&&f.estimated==2);}
  uint32_t t=lit+dark;while(transmissions==2&&t<lit+dark+5000){at(f,t,99,76);t+=250;}
  assert(transmissions==3&&f.estimated==3&&txStarts.back()>=lit+dark&&f.lastSent==1);
  end=f.lastSend;at(f,end+299,99,76);assert(transmissions==3);at(f,end+300,99,76);assert(transmissions==4&&f.estimated==3);
 }
}
void power_follows_the_same_rule(){
 const uint32_t unsure=unsureFrom(60),dark=darkFrom(60);
 for(bool raw:{false,true}){
  // On and dark: a wake burst, then the press that counts. The estimate changes once.
  FanRemote f;prepare(f,raw,3);assert(configure(f,60)==FanRemote::OK);clockMs=1000;
  assert(action(f,FanRemote::SEND,0)==FanRemote::OK&&transmissions==1&&f.estimated==0&&!f.powerOn);
  assertPair(f,f.lastSend,1,0,raw);const uint32_t off=f.lastSend;
  // Off, soon after: one burst. Off for longer than the display time: the pair again.
  clockMs=off+unsure-1;assert(action(f,FanRemote::SEND,0)==FanRemote::OK&&transmissions==3&&f.estimated==3&&f.powerOn&&!held(f));
  assertNoRepeat(f,f.lastSend,3,3);
  prepare(f,raw,3);assert(configure(f,60)==FanRemote::OK);clockMs=1000;
  assert(action(f,FanRemote::SEND,0)==FanRemote::OK);assertPair(f,f.lastSend,1,0,raw);
  clockMs=f.lastSend+dark;assert(action(f,FanRemote::SEND,0)==FanRemote::OK&&transmissions==3&&f.estimated==3&&f.powerOn);
  assertPair(f,f.lastSend,3,3,raw);
  // In the unsure band Power is held like any other press, then leaves as the pair.
  prepare(f,raw,3);assert(configure(f,60)==FanRemote::OK);uint32_t lit=light(f,raw);
  clockMs=lit+unsure;assert(action(f,FanRemote::SEND,0)==FanRemote::OK&&transmissions==2&&held(f)&&f.estimated==0&&!f.powerOn);
  at(f,lit+dark-1);assert(transmissions==2&&held(f));
  at(f,lit+dark);assert(transmissions==3&&!held(f)&&f.lastSent==0);assertPair(f,f.lastSend,3,0,raw);
  // Automatic control turning an Off fan on as the first press since boot: the pair, one estimate change.
  prepare(f,raw,3);assert(action(f,FanRemote::SYNC,0,1)==FanRemote::OK&&configure(f,60)==FanRemote::OK&&action(f,FanRemote::AUTO,1)==FanRemote::OK);
  uint32_t t=5000;while(!transmissions&&t<20000){at(f,t,99,76);t+=250;}
  assert(transmissions==1&&f.lastSent==0&&f.powerOn&&f.estimated==3);
  uint32_t end=f.lastSend;at(f,end+299,99,76);assert(transmissions==1);at(f,end+300,99,76);assert(transmissions==2&&f.lastSent==0&&f.estimated==3&&f.powerOn);
 }
}
void receiver_check_is_not_a_press(){
 // Its test pattern is not a fan command, so the display timer still counts from the last press.
 const uint32_t dark=darkFrom(60);
 FanRemote f;prepare(f);assert(configure(f,60)==FanRemote::OK);uint32_t lit=light(f,false);
 clockMs=lit+10000;assert(action(f,FanRemote::RECEIVER_TEST)==FanRemote::OK);
 for(uint32_t t=lit+10000;t<lit+20000;t+=50){clockMs=t;f.tick(NAN,NAN,NAN,clockMs);}
 assert(!f.receiverTestActive()&&transmissions==5&&f.lastSend>lit&&f.lastPress==lit);
 clockMs=lit+dark;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==6);assertPair(f,f.lastSend,6,3,false);
}
int main(){
 off_is_always_single();first_press_since_boot_wakes();lit_unsure_and_dark_bands();bands_survive_clock_rollover();
 configuration_and_persistence();pending_guards_and_cancellation();held_press_follows_setting_changes();
 automatic_and_target_pacing();automatic_and_target_wait_out_the_unsure_band();power_follows_the_same_rule();receiver_check_is_not_a_press();
 puts("PASS: actual firmware display-aware double hits default off; first press since boot wakes; lit/unsure/dark bands from the last press with single, held-then-pair and pair sends; 299/300ms decoded/raw repeat timing and one estimate update; rollover; atomic persistence and no-op writes; cancellation/zero veto/busy guards; held presses follow setting changes and survive disconnect; automatic/TARGET wait out the unsure band; Power follows the same rule; the receiver check is not a press. Hardware reception is not modeled.");
}
'''

with tempfile.TemporaryDirectory(prefix='breeze-double-hit-test-') as directory:
    path = Path(directory)
    (path / 'fake.h').write_text(stub)
    for header in ('Arduino.h', 'Preferences.h', 'IRrecv.h', 'IRsend.h', 'IRutils.h', 'nvs.h'):
        (path / header).write_text('#include "fake.h"\n')
    (path / 'test.cpp').write_text(HARNESS)
    (path / 'run.cmd').write_text(
        '@echo off\n'
        'call "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\n'
        'cl /nologo /EHsc /std:c++17 /I . /I "' + str(ROOT / 'firmware/BreezeController') + '" test.cpp /Fe:test.exe\n'
        'if errorlevel 1 exit /b 1\n'
        'test.exe\n')
    subprocess.run([str(path / 'run.cmd')], cwd=path, check=True)

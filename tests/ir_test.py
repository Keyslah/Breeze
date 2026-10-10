"""Run the actual FanRemote implementation against deterministic IR/NVS spies."""
from pathlib import Path
import subprocess
import tempfile
import re

root = Path(__file__).resolve().parents[1]
firmware_source = (root/'firmware/BreezeController/BreezeController.ino').read_text(encoding='utf-8')
legacy_fixture = (root/'firmware/out-logic/sketch/LogicControl.h').read_text(encoding='utf-8').replace('namespace BreezeLogic {','namespace BreezeLogicV1 {')
def firmware_function(name):
    start = re.search(r'void ' + name + r'\([^;{}]*\)\s*\{', firmware_source).start()
    opening = firmware_source.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (firmware_source[end] == '{') - (firmware_source[end] == '}')
        end += 1
    return firmware_source[start:end]
stub = r'''
#pragma once
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <string>
#include <map>
#include <vector>
#include <cstring>
#include <cassert>
using String=std::string;
#define String(x) std::to_string(x)
constexpr int D0=0,D1=1,D2=2,D4=22,D5=23,D6=16,D7=17;
constexpr int HIGH=1,LOW=0,INPUT=0,kRawTick=2;
inline void pinMode(int,int){}
inline int digitalRead(int){return HIGH;}
inline uint32_t clockMs=100;
inline uint32_t millis(){return clockMs;}
struct SerialFake{template<class...T>void printf(const char*,T...){} template<class T>void println(T){}};
inline SerialFake Serial;
enum decode_type_t{UNKNOWN=-1,NEC=1,RC5=2,RC6=3,SYMPHONY=76};
#define _IRREMOTEESP8266_VERSION_STR "test-spy"
struct decode_results{bool overflow=false,repeat=false;uint16_t rawlen=13,bits=32;decode_type_t decode_type=NEC;uint64_t value=123;uint32_t address=3,command=4;uint16_t*rawbuf=nullptr;};
inline bool pending=false,sendSupported=true,failStorage=false;
inline decode_results nextCapture;
inline uint16_t pulse=600;
inline int transmissions=0,rawTransmissions=0;
inline int nvsWrites=0,nvsRemoves=0,receiverEnables=0,receiverDisables=0,loopbackMode=0;
inline bool receiverEnabled=false,scheduledCapture=false;
inline uint32_t captureAt=0,captureDelay=20;
inline decode_results loopbackCapture;
inline std::vector<uint16_t> loopbackRaw,lastRaw;
inline std::vector<int> rawCarriers,rawSenderPins;
inline std::map<std::string,std::vector<uint8_t>> saved;
class Preferences{public:
 bool begin(const char*,bool){return true;}
 bool getBool(const char*,bool fallback){return fallback;}
 size_t putBool(const char*,bool){nvsWrites++;return failStorage?0:1;}
 size_t putUInt(const char*,uint32_t){nvsWrites++;return 4;}
 size_t getBytesLength(const char* k){return saved[k].size();}
 size_t getBytes(const char* k,void* dest,size_t n){if(saved[k].size()!=n)return 0;memcpy(dest,saved[k].data(),n);return n;}
 size_t putBytes(const char*k,const void*p,size_t n){nvsWrites++;if(failStorage)return 0;const auto*b=(const uint8_t*)p;saved[k]=std::vector<uint8_t>(b,b+n);return n;}
 bool remove(const char*k){nvsRemoves++;return saved.erase(k)>0;}
};
constexpr int ESP_OK=0;
struct nvs_stats_t {size_t used_entries=0,free_entries=500,available_entries=500,total_entries=630,namespace_count=1;};
inline int nvs_get_stats(const char*,nvs_stats_t* result){*result=nvs_stats_t{};return ESP_OK;}
class IRrecv{public:IRrecv(int,int,int,bool){}void enableIRIn(bool=false){receiverEnabled=true;receiverEnables++;}void disableIRIn(){receiverEnabled=false;receiverDisables++;}void setUnknownThreshold(int){}void resume(){pending=false;}
 bool decode(decode_results* r){if(receiverEnabled&&scheduledCapture&&int32_t(clockMs-captureAt)>=0){nextCapture=loopbackCapture;pending=true;scheduledCapture=false;}if(!receiverEnabled||!pending)return false;*r=nextCapture;return true;}
};
class IRsend{int pin;public:IRsend(int p):pin(p){}void begin(){}bool send(decode_type_t,uint64_t,uint16_t,int){if(sendSupported)transmissions++;return sendSupported;}void sendRaw(uint16_t*raw,uint16_t count,uint16_t carrier){
 transmissions++;rawTransmissions++;lastRaw.assign(raw,raw+count);rawCarriers.push_back(carrier);rawSenderPins.push_back(pin);
 if(receiverEnabled&&loopbackMode){loopbackRaw.resize(count+1);loopbackRaw[0]=10000;for(int i=0;i<count;i++)loopbackRaw[i+1]=raw[i]/kRawTick;
  if(loopbackMode==2)for(int i=1;i<=count;i++)loopbackRaw[i]=600/kRawTick;
  if(loopbackMode==3&&count>3)loopbackRaw[4]=(raw[3]==500?1000:500)/kRawTick;
  if(loopbackMode==5)for(int i=1;i<=count;i++)loopbackRaw[i]+=50/kRawTick;
  loopbackCapture={};loopbackCapture.decode_type=UNKNOWN;loopbackCapture.bits=0;loopbackCapture.rawlen=count+1;loopbackCapture.rawbuf=loopbackRaw.data();loopbackCapture.overflow=loopbackMode==4;
  scheduledCapture=true;captureAt=clockMs+captureDelay;
 }
}
 uint64_t toggleRC5(uint64_t n){return n^2048;}uint64_t toggleRC6(uint64_t n,uint16_t){return n^65536;}
};
inline bool hasACState(decode_type_t){return false;}
inline std::string resultToHumanReadableBasic(decode_results*){return "capture";}
inline std::string resultToSourceCode(decode_results*){return "raw";}
inline std::string typeToString(decode_type_t){return "NEC";}
inline uint16_t getCorrectedRawLength(decode_results* r){return r->rawlen-1;}
inline uint16_t* resultToRawArray(decode_results* r){auto*p=new uint16_t[r->rawlen-1];for(int i=0;i<r->rawlen-1;i++)p[i]=r->rawbuf?r->rawbuf[i+1]*kRawTick:pulse;return p;}
'''
harness = r'''
#include "FanRemote.cpp"
#include "legacy_logic_fixture.h"
// Hardware/session boundaries around the actual firmware process() function.
FanRemote fan;Preferences prefs;
bool active=false,mounted=false,storageOK=true,streaming=false,synced=false;
uint8_t commandError=0,testStage=0;uint16_t ack=0;
uint32_t streamRecord=0,totalRecord=0,baseRecord=0,archiveId=0,lastSample=0;
uint64_t epochBase=0,clockAt=0;float secondProbe=29;
uint16_t u16(const uint8_t*b){return b[0]|uint16_t(b[1])<<8;}
uint32_t u32(const uint8_t*b){return u16(b)|uint32_t(u16(b+2))<<16;}
uint32_t esp_random(){return 1;}uint64_t esp_timer_get_time(){return uint64_t(clockMs)*1000;}
uint32_t nowEpoch(){return 1800000000;}
void telemetry(){}void metadata(){}void notifyBytes(uint8_t*){}void retire(uint32_t){}void openStorage(){}
void stopSession(){active=false;}void startSession(){active=true;}void readBodySensor(){}void appendRecord(uint8_t=0,bool=true){}
struct FilesystemFake{bool format(){return true;}}LittleFS;
struct OtaFake{bool active(){return false;}uint8_t enter(void(*)(uint8_t*)){return 0;}uint8_t cancel(){return 0;}uint8_t confirmRunning(){return 0;}}ota;
FIRMWARE_PROCESS
int action(FanRemote& f,int a,int v=0,int arg=0){uint8_t b[20]={1,6};b[4]=a;b[5]=v;b[6]=arg;b[7]=arg>>8;return f.command(b);}
std::vector<uint8_t> status(FanRemote& f){std::vector<uint8_t>b(20);f.statusPacket(b.data(),29,3);return b;}
void tick(FanRemote& f,int advance=500){clockMs+=advance;f.tick(35,29,23,clockMs);}
void learn(FanRemote& f,int slot){assert(action(f,1,slot,38)==0);pending=true;nextCapture={};tick(f);assert(status(f)[7]==2);}
std::vector<uint8_t> optical(FanRemote& f){std::vector<uint8_t>b(20);f.receiverTestPacket(b.data(),clockMs);return b;}
std::vector<uint8_t> input(FanRemote& f){std::vector<uint8_t>b(20);f.receiverInputPacket(b.data());return b;}
uint16_t read16(const std::vector<uint8_t>&b,int offset){return b[offset]|uint16_t(b[offset+1])<<8;}
uint64_t read64(const std::vector<uint8_t>&b,int offset){uint64_t n=0;for(int i=0;i<8;i++)n|=uint64_t(b[offset+i])<<(i*8);return n;}
std::map<std::string,std::vector<uint8_t>> learnedRecords(){std::map<std::string,std::vector<uint8_t>> out;for(const auto& item:saved)if(item.first.rfind("ir",0)==0)out.insert(item);return out;}
std::vector<uint8_t> logicState(FanRemote& f){std::vector<uint8_t>b(20);f.logicStatusPacket(b.data(),clockMs);return b;}
void field16(uint8_t* b,int offset,int value){b[offset]=value;b[offset+1]=value>>8;}
float celsius(float f){return (f-32)/1.8f;}
void runtimeTick(FanRemote& f,int advance,float bodyF,float roomF,float d2F=NAN){clockMs+=advance;f.tick(celsius(bodyF),celsius(bodyF-1),celsius(roomF),clockMs,celsius(d2F));}
int submitProfile(FanRemote& f,const BreezeLogic::Profile& p,bool commit=true){
 uint8_t b[20]={1,6};b[4]=13;b[5]=p.probeMask;field16(b,6,p.roomCentiF);field16(b,8,p.averageSeconds);field16(b,10,p.riseSeconds);field16(b,12,p.fallSeconds);b[14]=p.maximum;b[15]=2;field16(b,16,p.coolRoomCentiF);assert(f.command(b)==0);
 for(int i=0;i<2;i++){memset(b+4,0,16);const auto&r=p.rules[i];b[4]=14;b[5]=i;field16(b,6,r.bodyCentiF);field16(b,8,r.stepCentiF);b[10]=r.minimum;b[11]=r.offBelow;b[12]=1;assert(f.command(b)==0);
  memset(b+4,0,16);const auto&t=r.boost;b[4]=15;b[5]=i;b[6]=t.enabled;b[7]=t.extra;field16(b,8,t.triggerCentiF);field16(b,10,t.windowSeconds);field16(b,12,t.riseSeconds);field16(b,14,t.holdSeconds);field16(b,16,t.returnSeconds);b[18]=t.curve;b[19]=1;assert(f.command(b)==0);
 }
 return commit?action(f,16):0;
}
void runOptical(FanRemote& f,int mode,int matches,int frames,int overflows=0){
 loopbackMode=mode;pending=scheduledCapture=false;int count=rawTransmissions,writes=nvsWrites,removes=nvsRemoves,disables=receiverDisables;
 auto stored=saved;auto before=status(f);assert(action(f,12)==0);auto b=optical(f);
 assert(b[0]==7&&b[19]==1&&b[1]==1&&b[2]==0&&b[3]==0&&b[4]==0&&b[5]==5);
 assert(b[14]==38&&b[15]==17&&b[16]==16);
 assert(action(f,1,0,38)==22&&action(f,4,0)==22); // Busy guards protect the optical test.
 tick(f,4999);assert(rawTransmissions==count&&optical(f)[2]==0);
 tick(f,1);
 for(int attempt=1;attempt<=3;attempt++){
  b=optical(f);assert(b[1]==2&&b[2]==attempt&&b[13]==HIGH);
  assert(receiverEnabled&&receiverDisables==disables&&rawTransmissions==count+attempt);
  assert(lastRaw.size()==27&&lastRaw[0]==1600&&lastRaw[1]==1600&&lastRaw[26]==600);
  for(int bit=0;bit<12;bit++){assert(lastRaw[2+bit*2]==600);assert(lastRaw[3+bit*2]==((0xA5B&(1<<(11-bit)))?1000:500));}
  assert(rawCarriers.back()==38&&rawSenderPins.back()==17);
  tick(f,20);b=optical(f);
  // A second meaningful frame in one receive window must not inflate the counts.
  if(mode==1||mode==5){pending=true;nextCapture=loopbackCapture;tick(f,10);assert(optical(f)[3]==attempt&&optical(f)[4]==attempt);tick(f,220);}
  else tick(f,230);
  if(attempt<3){assert(optical(f)[1]==1);tick(f,750);}
 }
 b=optical(f);assert(b[1]==3&&b[2]==3&&b[3]==matches&&b[4]==frames&&b[6]==overflows&&b[10]==0);
 if(matches){auto seen=input(f);assert(seen[0]==8&&seen[19]==1&&seen[1]==19&&seen[2]==255&&read16(seen,4)==65535&&read16(seen,16)==28);}
 assert(nvsWrites==writes&&nvsRemoves==removes&&saved==stored); // Never overwrite or clear learned buttons.
 auto after=status(f);assert(after[8]==before[8]&&after[9]==before[9]&&after[16]==before[16]&&after[17]==before[17]);
 int finishedCount=transmissions;tick(f,5000);assert(transmissions==finishedCount);
 loopbackMode=0;
}
int main(){
 FanRemote f;f.begin();assert(transmissions==0&&status(f)[4]==0);
 assert(action(f,4,0)==20&&action(f,5,4)==21&&action(f,7,1)==0);assert(transmissions==0);
 learn(f,0);learn(f,1);learn(f,2);assert((status(f)[8]&7)==7);
 FanRemote reboot;reboot.begin();assert((status(reboot)[8]&7)==7&&status(reboot)[4]==0);assert(action(reboot,4,0)==0&&transmissions==1);
 assert(action(reboot,4,0)==0&&transmissions==2); // A second explicit manual tap has no artificial 450ms delay.
 tick(reboot);sendSupported=false;assert(action(reboot,4,0)==0&&rawTransmissions==1);sendSupported=true;
 tick(reboot);assert(action(reboot,6,4,1)==0);assert(action(reboot,5,6)==0);tick(reboot);assert(status(reboot)[4]==5);tick(reboot,1000);assert(status(reboot)[4]==6&&status(reboot)[5]==0);
 int count=transmissions;assert(action(reboot,7,0)==0);for(int i=0;i<200;i++)tick(reboot);assert(transmissions==count); // Paused automation never drives IR.
 assert(action(reboot,6,9,1)==0);assert(action(reboot,4,1)==0);assert(status(reboot)[4]==10&&reboot.recordedSetting()==10); // 9 advances to H; its duplicate does not advance twice.
 count=transmissions;tick(reboot,299);assert(transmissions==count&&status(reboot)[4]==10);tick(reboot,1);assert(transmissions==count+1&&status(reboot)[4]==10);
 tick(reboot);assert(action(reboot,6,3,1)==0);pending=true;nextCapture={};tick(reboot);assert(status(reboot)[4]==0&&status(reboot)[10]==29);
 assert(action(reboot,1,0,38)==0);nextCapture.repeat=true;pending=true;tick(reboot);assert(status(reboot)[7]==1);
 nextCapture.repeat=false;nextCapture.overflow=true;pending=true;tick(reboot);assert(status(reboot)[7]==1);
 tick(reboot,30000);assert(status(reboot)[7]==3&&(status(reboot)[8]&1));
 assert(action(reboot,1,0,38)==0);failStorage=true;nextCapture={};pending=true;tick(reboot);assert(status(reboot)[7]==5&&(status(reboot)[8]&1));failStorage=false;
 assert(action(reboot,1,0,38)==0);assert(action(reboot,2)==0&&status(reboot)[6]==255);
 assert(action(reboot,6,4,1)==0);assert(action(reboot,5,7)==0);reboot.disconnected();count=transmissions;tick(reboot);assert(transmissions==count&&status(reboot)[5]==0);
 assert(action(reboot,1,3,38)==0);nextCapture={};nextCapture.decode_type=UNKNOWN;pending=true;tick(reboot);tick(reboot);assert(action(reboot,4,3)==0&&rawTransmissions==2);
 tick(reboot);assert(action(reboot,3,0)==0);assert(action(reboot,4,0)==20);
 runOptical(reboot,1,3,3); // Existing learned codes are preserved.
 saved.clear();FanRemote diagnostic;diagnostic.begin();assert(status(diagnostic)[8]==0&&status(diagnostic)[9]==0);
 runOptical(diagnostic,1,3,3); // Optical output test needs no learned button.
 runOptical(diagnostic,5,3,3); // Receiver timing distortion within tolerance still matches.
 runOptical(diagnostic,0,0,0); // No optical response never becomes a software-only pass.
 runOptical(diagnostic,2,0,3); // Unrelated meaningful remote frames are not test matches.
 runOptical(diagnostic,3,0,3); // Correct header but different data is not a test match.
 runOptical(diagnostic,4,0,0,3); // Overflow cannot prove reception.
 // A matching waveform decoded after the receive deadline must not pass,
 // even when a delayed main-loop tick has not yet closed the old window.
 loopbackMode=1;captureDelay=300;pending=scheduledCapture=false;
 count=transmissions;int lateWrites=nvsWrites,lateRemoves=nvsRemoves,lateDisables=receiverDisables;
 assert(action(diagnostic,12)==0);tick(diagnostic,5000);
 for(int attempt=1;attempt<=3;attempt++){
  assert(optical(diagnostic)[2]==attempt);tick(diagnostic,300);
  auto late=optical(diagnostic);assert(late[3]==0&&late[4]==0&&late[6]==0);
  if(attempt<3){assert(late[1]==1);tick(diagnostic,700);}else assert(late[1]==3);
 }
 assert(transmissions==count+3&&receiverDisables==lateDisables&&nvsWrites==lateWrites&&nvsRemoves==lateRemoves);
 loopbackMode=0;captureDelay=20;
 // A stale caller timestamp must not shorten the receive window at actual send.
 loopbackMode=1;pending=scheduledCapture=false;assert(action(diagnostic,12)==0);
 clockMs+=5400;diagnostic.tick(35,29,23,clockMs-400);assert(optical(diagnostic)[2]==1);
 tick(diagnostic,20);assert(optical(diagnostic)[3]==1);assert(action(diagnostic,2)==0);loopbackMode=0;
 int writes=nvsWrites,removes=nvsRemoves;count=transmissions;
 assert(action(diagnostic,12,1)==26&&action(diagnostic,12,0,38)==26);
 assert(action(diagnostic,12)==0);assert(action(diagnostic,2)==0&&optical(diagnostic)[1]==4);tick(diagnostic,6000);assert(transmissions==count);
 assert(action(diagnostic,12)==0);diagnostic.disconnected();assert(optical(diagnostic)[1]==4);tick(diagnostic,6000);assert(transmissions==count);
 assert(action(diagnostic,12)==0);tick(diagnostic,5000);assert(optical(diagnostic)[2]==1);diagnostic.disconnected();tick(diagnostic,5000);assert(transmissions==count+1&&optical(diagnostic)[1]==4);
 assert(nvsWrites==writes&&nvsRemoves==removes);
 saved.clear();clockMs=100;pending=scheduledCapture=false;FanRemote monitor;monitor.begin();auto empty=input(monitor);
 assert(empty[0]==8&&empty[19]==1&&empty[1]==0&&empty[2]==255&&read16(empty,4)==65535&&read16(empty,6)==0);
 assert(action(monitor,1,0,38)==0);nextCapture={};nextCapture.value=0x20DFA35C;pending=true;tick(monitor);assert(status(monitor)[7]==2);
 assert(action(monitor,1,1,38)==0);nextCapture={};nextCapture.value=0x20DF03FC;pending=true;tick(monitor);assert(status(monitor)[7]==2);
 writes=nvsWrites;removes=nvsRemoves;auto monitorStored=saved;int monitorDisables=receiverDisables;
 assert(action(monitor,4,0)==0);nextCapture={};nextCapture.value=0x20DFA35C;pending=true;tick(monitor,20);auto own=input(monitor);
 assert(own[1]==3&&own[2]==0&&own[3]==32&&read16(own,4)==NEC&&read64(own,8)==0x20DFA35C&&read16(own,16)==13);
 assert(receiverEnabled&&receiverDisables==monitorDisables&&nvsWrites==writes&&nvsRemoves==removes&&saved==monitorStored);
 tick(monitor);assert(action(monitor,6,4,1)==0);assert(action(monitor,4,1)==0);
 writes=nvsWrites;monitorStored=saved;auto learnedBeforeFeedback=learnedRecords();
 nextCapture={};nextCapture.value=0x20DF03FC;pending=true;tick(monitor,20);own=input(monitor);
 assert(own[1]==3&&own[2]==1&&status(monitor)[4]==5&&(status(monitor)[1]&4)); // Own feedback must not invalidate its estimate.
 int ownSequence=read16(own,6);tick(monitor);nextCapture={};nextCapture.value=0x20DF03FC;pending=true;tick(monitor,10);auto external=input(monitor);
 assert(external[1]==1&&external[2]==1&&read16(external,6)==ownSequence+1&&status(monitor)[4]==0&&status(monitor)[10]==29);
 assert(nvsWrites==writes+1&&nvsRemoves==removes&&learnedRecords()==learnedBeforeFeedback); // External use persists uncertain estimate without modifying learned buttons.
 assert(action(monitor,1,3,38)==0);pulse=900;nextCapture={};nextCapture.decode_type=UNKNOWN;nextCapture.bits=0;pending=true;tick(monitor);assert(status(monitor)[7]==2);pulse=600;
 writes=nvsWrites;removes=nvsRemoves;monitorStored=saved;tick(monitor);loopbackMode=1;
 assert(action(monitor,4,3)==0);tick(monitor,20);auto rawOwn=input(monitor);
 assert(rawOwn[1]==3&&rawOwn[2]==3&&read16(rawOwn,4)==65535&&read16(rawOwn,16)==13);loopbackMode=0;
 tick(monitor);pulse=900;nextCapture={};nextCapture.decode_type=UNKNOWN;nextCapture.bits=0;nextCapture.value=0xFEDCBA9876543210ULL;pending=true;tick(monitor,10);auto rawExternal=input(monitor);pulse=600;
 assert(rawExternal[1]==1&&rawExternal[2]==3&&read16(rawExternal,4)==65535&&read64(rawExternal,8)==0xFEDCBA9876543210ULL);
 assert(nvsWrites==writes&&nvsRemoves==removes&&saved==monitorStored);
 tick(monitor);assert(action(monitor,6,3,1)==0);assert(action(monitor,5,7)==0);tick(monitor,1);
 assert(status(monitor)[4]==4&&status(monitor)[5]==7);int beforeImmediate=transmissions;
 tick(monitor,449);assert(status(monitor)[4]==4&&transmissions==beforeImmediate); // Multi-step target pacing stays intact.
 assert(action(monitor,4,1)==0&&transmissions==beforeImmediate+1&&status(monitor)[4]==5&&status(monitor)[5]==0);
 assert(action(monitor,4,2)==20); // Missing command still fails without fabricating a send.
 assert(action(monitor,4,1)==0&&transmissions==beforeImmediate+2&&status(monitor)[4]==6);
 assert(action(monitor,4,1)==0&&transmissions==beforeImmediate+3&&status(monitor)[4]==7);
 tick(monitor,1000);assert(transmissions==beforeImmediate+3&&status(monitor)[5]==0); // Canceled target cannot execute delayed steps.
 runOptical(monitor,1,3,3); // Diagnostics are explicitly flagged and never shown as a learned button.
 clockMs=100;fan.begin();count=transmissions;writes=nvsWrites;removes=nvsRemoves;
 uint8_t powerPacket[20]={1,6};powerPacket[2]=200;powerPacket[4]=4;
 process(powerPacket,7);assert(transmissions==count+1&&ack==200&&commandError==0&&status(fan)[4]==0);
 process(powerPacket,7);assert(transmissions==count+1&&ack==200&&commandError==0&&status(fan)[4]==0); // ACK retry never replays a Power toggle.
 powerPacket[5]=1;process(powerPacket,7);assert(transmissions==count+1&&commandError==1); // Same sequence with changed payload also never sends.
 powerPacket[5]=0;powerPacket[2]=201;process(powerPacket,7);assert(transmissions==count+2&&commandError==0&&status(fan)[4]==7); // A new tap sends immediately and restores the retained speed.
 process(powerPacket,7);assert(transmissions==count+2&&commandError==0);
 process(powerPacket,8);assert(transmissions==count+3&&commandError==0); // A new BLE connection owns a new sequence namespace.
 assert(nvsWrites==writes+3&&nvsRemoves==removes);
 // Exercise real controller automation, atomic profile upload and restart semantics.
 saved.clear();clockMs=100;pending=scheduledCapture=false;loopbackMode=0;FanRemote controlled;controlled.begin();count=transmissions;
 assert((logicState(controlled)[1]&1)&&logicState(controlled)[13]==7);
 runtimeTick(controlled,1000,98,72);assert(transmissions==count&&logicState(controlled)[12]==20); // Enabled with missing codes remains quiet.
 learn(controlled,0);learn(controlled,1);learn(controlled,2);auto immutableCodes=learnedRecords();
 BreezeLogic::Profile p;p.averageSeconds=1;uint8_t globalOnly[20]={1,6};globalOnly[4]=13;globalOnly[5]=7;field16(globalOnly,6,7100);field16(globalOnly,8,1);field16(globalOnly,10,10);field16(globalOnly,12,5);globalOnly[14]=9;globalOnly[15]=1;
 assert(controlled.command(globalOnly)==0&&action(controlled,16)==26);runtimeTick(controlled,1000,98,72);assert(transmissions==count&&!(logicState(controlled)[1]&1));
 assert(submitProfile(controlled,p,false)==0);failStorage=true;assert(action(controlled,16)==24);failStorage=false;
 assert(read16(logicState(controlled),10)==1&&!(logicState(controlled)[1]&1)&&learnedRecords()==immutableCodes);
 assert(action(controlled,16)==0&&read16(logicState(controlled),10)==2&&(logicState(controlled)[1]&1));
 runtimeTick(controlled,1,98,72);assert(transmissions==count&&logicState(controlled)[12]==21); // No startup Power toggle while state is unknown.
 assert(action(controlled,6,1,1)==0);runtimeTick(controlled,1,98,72);runtimeTick(controlled,1249,98,72);assert(transmissions==count);
 runtimeTick(controlled,1,98,72);assert(status(controlled)[4]==2&&transmissions==count+1);runtimeTick(controlled,1250,98,72);assert(status(controlled)[4]==3&&transmissions==count+2);
 assert(action(controlled,6,1,1)==0);runtimeTick(controlled,1000,102,72);count=transmissions;
 for(int i=1;i<=8;i++){runtimeTick(controlled,1250,102,72);assert(status(controlled)[4]==1+i&&transmissions==count+i);} // Eight steps span ten seconds.
 controlled.disconnected();assert(logicState(controlled)[1]&1);runtimeTick(controlled,1000,97,70);count=transmissions;
 for(int i=1;i<=8;i++){runtimeTick(controlled,1000,97,70);assert(status(controlled)[4]==9-i&&transmissions==count+i);} // Automatic levels respect the one-second minimum before OFF.
 runtimeTick(controlled,999,97,70);assert(status(controlled)[4]==1);runtimeTick(controlled,1,97,70);assert(status(controlled)[4]==0&&transmissions==count+9&&(status(controlled)[1]&4));
 FanRemote offReboot;offReboot.begin();assert(status(offReboot)[4]==0&&(status(offReboot)[1]&4)&&(logicState(offReboot)[1]&1));
 count=transmissions;runtimeTick(offReboot,1000,98,72);assert(status(offReboot)[4]==1&&transmissions==count+1);runtimeTick(offReboot,1,98,72);
 runtimeTick(offReboot,1250,98,72);assert(status(offReboot)[4]==2);runtimeTick(offReboot,1250,98,72);assert(status(offReboot)[4]==3); // Restart at retained1, then ramp.
 assert(action(offReboot,6,1,1)==0);runtimeTick(offReboot,1000,NAN,72);assert(logicState(offReboot)[3]==3&&(logicState(offReboot)[1]&16));
 runtimeTick(offReboot,1250,NAN,72);runtimeTick(offReboot,1250,NAN,72);assert(status(offReboot)[4]==3);count=transmissions;
 runtimeTick(offReboot,1000,NAN,70);runtimeTick(offReboot,2000,NAN,70);assert(status(offReboot)[4]==3&&transmissions==count); // Missing body in cold room holds physical state.
 assert(action(offReboot,4,0)==0&&status(offReboot)[4]==0&&!(logicState(offReboot)[1]&1));count=transmissions;runtimeTick(offReboot,10000,102,72);assert(transmissions==count);
 assert(action(offReboot,4,0)==0&&status(offReboot)[4]==3);assert(action(offReboot,7,1)==0);runtimeTick(offReboot,1000,98,72,100);
 assert(status(offReboot)[11]==3&&logicState(offReboot)[3]==8&&(logicState(offReboot)[1]&32)); // D2 participates in warmest sampling.
 auto beforeSame=logicState(offReboot);writes=nvsWrites;assert(submitProfile(offReboot,p)==0);auto afterSame=logicState(offReboot);
 assert(nvsWrites==writes&&read16(afterSame,10)==read16(beforeSame,10)&&read16(afterSame,4)==read16(beforeSame,4)&&!(afterSame[1]&16));
 p.rules[0].minimum=4;assert(submitProfile(offReboot,p)==0&&read16(logicState(offReboot),4)==read16(beforeSame,4)); // Editing rules preserves current minute samples.
 assert(action(offReboot,5,7)==0);assert(action(offReboot,6,2,1)==0&&status(offReboot)[4]==2&&status(offReboot)[5]==0); // Correction replaces a manual target immediately.
 BreezeLogic::Profile boosted=p;boosted.rules[0].minimum=3;boosted.rules[0].bodyCentiF=10500;boosted.rules[0].boost.enabled=1;boosted.rules[0].boost.windowSeconds=1;boosted.rules[0].boost.holdSeconds=5;
 assert(submitProfile(offReboot,boosted)==0);assert(action(offReboot,6,3,1)==0);runtimeTick(offReboot,1000,98,72);runtimeTick(offReboot,1000,99,72);runtimeTick(offReboot,2000,99,72);
 auto boosting=logicState(offReboot);assert(boosting[1]&2);writes=nvsWrites;assert(submitProfile(offReboot,boosted)==0);auto sameBoost=logicState(offReboot);
 assert((sameBoost[1]&2)&&read16(sameBoost,8)==read16(boosting,8)&&read16(sameBoost,10)==read16(boosting,10)&&nvsWrites==writes);
 FanRemote knownReboot;knownReboot.begin();assert(status(knownReboot)[4]==status(offReboot)[4]&&(status(knownReboot)[1]&4)&&(logicState(knownReboot)[1]&1));
 assert(read16(logicState(knownReboot),10)==read16(sameBoost,10)&&learnedRecords()==immutableCodes);
 // Independent warm/cold room thresholds hold the actual current level in their gap.
 BreezeLogic::Profile independent=p;independent.roomCentiF=7200;independent.coolRoomCentiF=7000;
 assert(submitProfile(knownReboot,independent)==0);assert(action(knownReboot,6,1,1)==0);
 runtimeTick(knownReboot,1000,102,72);count=transmissions;runtimeTick(knownReboot,1250,102,72);assert(status(knownReboot)[4]==2&&transmissions==count+1);
 runtimeTick(knownReboot,1000,102,71.9);auto held=logicState(knownReboot);count=transmissions;
 assert(held[2]==255&&held[3]==2);runtimeTick(knownReboot,10000,102,71);assert(status(knownReboot)[4]==2&&transmissions==count); // Old rise cannot finish while holding.
 runtimeTick(knownReboot,1000,97,70);assert(logicState(knownReboot)[2]==255&&logicState(knownReboot)[3]==2&&transmissions==count);
 runtimeTick(knownReboot,1000,97,69.9);assert(logicState(knownReboot)[2]==1&&logicState(knownReboot)[3]==0);runtimeTick(knownReboot,1000,97,69.9);assert(status(knownReboot)[4]==1);
 runtimeTick(knownReboot,100,97,70);count=transmissions;runtimeTick(knownReboot,1000,97,70);assert(status(knownReboot)[4]==1&&transmissions==count); // Gap also cancels pending OFF.
 BreezeLogic::Profile overlapping=independent;overlapping.coolRoomCentiF=7300;assert(submitProfile(knownReboot,overlapping)==26);
 FanRemote independentReboot;independentReboot.begin();assert(read16(logicState(independentReboot),10)==read16(logicState(knownReboot),10));count=transmissions;runtimeTick(independentReboot,1000,102,71);
 assert(logicState(independentReboot)[2]==255&&logicState(independentReboot)[3]==1&&transmissions==count); // Independent thresholds survive NVS reload.
 // Construct a real schema 1 blob using its actual 1.2.6 compiled header snapshot.
 static_assert(sizeof(BreezeLogicV1::Profile)==64&&offsetof(BreezeLogicV1::Profile,checksum)==60,"Fixture must retain the actual old layout");
 BreezeLogicV1::Profile legacy;legacy.revision=27;legacy.probeMask=5;legacy.maximum=8;legacy.roomCentiF=7325;legacy.averageSeconds=45;legacy.riseSeconds=8;legacy.fallSeconds=4;
 legacy.rules[0].bodyCentiF=9760;legacy.rules[0].stepCentiF=35;legacy.rules[0].minimum=4;legacy.rules[0].boost.enabled=1;legacy.rules[0].boost.extra=3;legacy.rules[0].boost.triggerCentiF=75;legacy.rules[0].boost.windowSeconds=20;legacy.rules[0].boost.riseSeconds=4;legacy.rules[0].boost.holdSeconds=90;legacy.rules[0].boost.returnSeconds=8;legacy.rules[0].boost.curve=0;
 legacy.rules[1].bodyCentiF=9680;legacy.rules[1].stepCentiF=65;legacy.rules[1].minimum=2;legacy.rules[1].boost.enabled=1;legacy.rules[1].boost.triggerCentiF=50;legacy.rules[1].boost.windowSeconds=15;legacy.rules[1].boost.riseSeconds=2;legacy.rules[1].boost.holdSeconds=60;legacy.rules[1].boost.returnSeconds=7;
 legacy.checksum=BreezeLogicV1::checksum(legacy);const uint8_t* oldBytes=reinterpret_cast<const uint8_t*>(&legacy);std::vector<uint8_t> legacyBlob(oldBytes,oldBytes+sizeof(legacy));saved["logic-profile"]=legacyBlob;
 writes=nvsWrites;FanRemote legacyController;legacyController.begin();assert(nvsWrites==writes&&saved["logic-profile"]==legacyBlob&&read16(logicState(legacyController),10)==27&&logicState(legacyController)[13]==5);
 runtimeTick(legacyController,1000,97.6f,74);assert(logicState(legacyController)[3]==4);auto beforeMigration=logicState(legacyController);auto learnedBeforeMigration=learnedRecords();
 BreezeLogic::Profile migrated;migrated.revision=27;migrated.probeMask=legacy.probeMask;migrated.maximum=legacy.maximum;migrated.roomCentiF=migrated.coolRoomCentiF=legacy.roomCentiF;migrated.averageSeconds=legacy.averageSeconds;migrated.riseSeconds=legacy.riseSeconds;migrated.fallSeconds=legacy.fallSeconds;
 for(int i=0;i<2;i++){const auto&from=legacy.rules[i];auto&to=migrated.rules[i];to.bodyCentiF=from.bodyCentiF;to.stepCentiF=from.stepCentiF;to.minimum=from.minimum;to.offBelow=from.offBelow;to.boost.enabled=from.boost.enabled;to.boost.extra=from.boost.extra;to.boost.triggerCentiF=from.boost.triggerCentiF;to.boost.windowSeconds=from.boost.windowSeconds;to.boost.riseSeconds=from.boost.riseSeconds;to.boost.holdSeconds=from.boost.holdSeconds;to.boost.returnSeconds=from.boost.returnSeconds;to.boost.curve=from.boost.curve;to.boost.reserved=from.boost.reserved;}
 writes=nvsWrites;assert(submitProfile(legacyController,migrated)==0&&nvsWrites==writes+1&&read16(logicState(legacyController),10)==27&&read16(logicState(legacyController),4)==read16(beforeMigration,4));
 BreezeLogic::Profile persistedMigration;assert(saved["logic-profile"].size()==sizeof(persistedMigration));memcpy(&persistedMigration,saved["logic-profile"].data(),sizeof(persistedMigration));
 assert(persistedMigration.schema==2&&persistedMigration.revision==27&&BreezeLogic::sameSettings(persistedMigration,migrated)&&persistedMigration.checksum==BreezeLogic::checksum(persistedMigration)&&learnedRecords()==learnedBeforeMigration);
 writes=nvsWrites;assert(submitProfile(legacyController,migrated)==0&&nvsWrites==writes); // Reopening after migration is idempotent.
 saved["logic-profile"]=legacyBlob;saved["logic-profile"][8]^=1;FanRemote damagedLegacy;damagedLegacy.begin();assert(read16(logicState(damagedLegacy),10)==1&&logicState(damagedLegacy)[13]==7); // Reject a corrupt schema 1 checksum.
 saved["logic-profile"].back()^=1;saved["fan-estimate"].back()^=1;FanRemote invalidReboot;invalidReboot.begin();count=transmissions;runtimeTick(invalidReboot,1000,102,72);
 assert(status(invalidReboot)[4]==0&&logicState(invalidReboot)[12]==21&&transmissions==count&&read16(logicState(invalidReboot),10)==1);
 puts("PASS: controller-owned automation, independent room thresholds/deadband and persisted profiles; ramp/OFF-restart/disconnect/current-state tracking; atomic/identical uploads, D2, missing-body minimum, manual pause/correction, boosts and command deduplication");
}
'''.replace('FIRMWARE_PROCESS', firmware_function('process'))
with tempfile.TemporaryDirectory(prefix='breeze-ir-test-') as directory:
    path = Path(directory)
    (path/'fake.h').write_text(stub)
    (path/'legacy_logic_fixture.h').write_text(legacy_fixture)
    for header in ('Arduino.h','Preferences.h','IRrecv.h','IRsend.h','IRutils.h','nvs.h'):
        (path/header).write_text('#include "fake.h"\n')
    (path/'test.cpp').write_text(harness)
    (path/'run.cmd').write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 /I . /I "'+str(root/'firmware/BreezeController')+'" test.cpp /Fe:test.exe\nif errorlevel 1 exit /b 1\ntest.exe\n')
    subprocess.run([str(path/'run.cmd')],cwd=path,check=True)

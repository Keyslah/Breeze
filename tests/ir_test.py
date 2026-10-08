"""Run the actual FanRemote implementation against deterministic IR/NVS spies."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
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
constexpr int D0=0,D1=1,D4=22,D5=23,D6=16,D7=17;
inline uint32_t clockMs=100;
inline uint32_t millis(){return clockMs;}
struct SerialFake{template<class...T>void printf(const char*,T...){} template<class T>void println(T){}};
inline SerialFake Serial;
enum decode_type_t{UNKNOWN=-1,NEC=1,RC5=2,RC6=3};
#define _IRREMOTEESP8266_VERSION_STR "test-spy"
struct decode_results{bool overflow=false,repeat=false;uint16_t rawlen=13,bits=32;decode_type_t decode_type=NEC;uint64_t value=123;uint32_t address=3,command=4;};
inline bool pending=false,sendSupported=true,failStorage=false;
inline decode_results nextCapture;
inline uint16_t pulse=600;
inline int transmissions=0,rawTransmissions=0;
inline std::map<std::string,std::vector<uint8_t>> saved;
class Preferences{public:
 bool begin(const char*,bool){return true;}
 bool getBool(const char*,bool fallback){return fallback;}
 size_t putBool(const char*,bool){return failStorage?0:1;}
 size_t getBytesLength(const char* k){return saved[k].size();}
 size_t getBytes(const char* k,void* dest,size_t n){if(saved[k].size()!=n)return 0;memcpy(dest,saved[k].data(),n);return n;}
 size_t putBytes(const char*k,const void*p,size_t n){if(failStorage)return 0;const auto*b=(const uint8_t*)p;saved[k]=std::vector<uint8_t>(b,b+n);return n;}
 bool remove(const char*k){return saved.erase(k)>0;}
};
class IRrecv{public:IRrecv(int,int,int,bool){}void enableIRIn(){}void disableIRIn(){}void setUnknownThreshold(int){}void resume(){pending=false;}
 bool decode(decode_results* r){if(!pending)return false;*r=nextCapture;return true;}
};
class IRsend{public:IRsend(int){}void begin(){}bool send(decode_type_t,uint64_t,uint16_t,int){if(sendSupported)transmissions++;return sendSupported;}void sendRaw(uint16_t*,uint16_t,uint16_t){transmissions++;rawTransmissions++;}
 uint64_t toggleRC5(uint64_t n){return n^2048;}uint64_t toggleRC6(uint64_t n,uint16_t){return n^65536;}
};
inline bool hasACState(decode_type_t){return false;}
inline std::string resultToHumanReadableBasic(decode_results*){return "capture";}
inline std::string resultToSourceCode(decode_results*){return "raw";}
inline std::string typeToString(decode_type_t){return "NEC";}
inline uint16_t getCorrectedRawLength(decode_results* r){return r->rawlen-1;}
inline uint16_t* resultToRawArray(decode_results* r){auto*p=new uint16_t[r->rawlen-1];for(int i=0;i<r->rawlen-1;i++)p[i]=pulse;return p;}
'''
harness = r'''
#include "FanRemote.cpp"
int action(FanRemote& f,int a,int v=0,int arg=0){uint8_t b[20]={1,6};b[4]=a;b[5]=v;b[6]=arg;b[7]=arg>>8;return f.command(b);}
std::vector<uint8_t> status(FanRemote& f){std::vector<uint8_t>b(20);f.statusPacket(b.data(),29,3);return b;}
void tick(FanRemote& f,int advance=500){clockMs+=advance;f.tick(35,29,23,clockMs);}
void learn(FanRemote& f,int slot){assert(action(f,1,slot,38)==0);pending=true;nextCapture={};tick(f);assert(status(f)[7]==2);}
int main(){
 FanRemote f;f.begin();assert(transmissions==0&&status(f)[4]==0);
 assert(action(f,4,0)==20&&action(f,5,4)==21&&action(f,7,1)==30);assert(transmissions==0);
 learn(f,0);learn(f,1);learn(f,2);assert((status(f)[8]&7)==7);
 FanRemote reboot;reboot.begin();assert((status(reboot)[8]&7)==7&&status(reboot)[4]==0);assert(action(reboot,4,0)==0&&transmissions==1);
 assert(action(reboot,4,0)==22);tick(reboot);sendSupported=false;assert(action(reboot,4,0)==0&&rawTransmissions==1);sendSupported=true;
 tick(reboot);assert(action(reboot,6,4,1)==0);assert(action(reboot,5,6)==0);tick(reboot);assert(status(reboot)[4]==5);tick(reboot);assert(status(reboot)[4]==6&&status(reboot)[5]==0);
 int count=transmissions;for(int i=0;i<200;i++){tick(reboot);assert(action(reboot,7,1)==30);}assert(transmissions==count); // Temperature never drives IR.
 assert(action(reboot,6,9,1)==0);assert(action(reboot,4,1)==0);assert(status(reboot)[4]==0); // Endpoint may wrap; never pretend clamping.
 tick(reboot);assert(action(reboot,6,3,1)==0);pending=true;nextCapture={};tick(reboot);assert(status(reboot)[4]==0&&status(reboot)[10]==29);
 assert(action(reboot,1,0,38)==0);nextCapture.repeat=true;pending=true;tick(reboot);assert(status(reboot)[7]==1);
 nextCapture.repeat=false;nextCapture.overflow=true;pending=true;tick(reboot);assert(status(reboot)[7]==1);
 tick(reboot,30000);assert(status(reboot)[7]==3&&(status(reboot)[8]&1));
 assert(action(reboot,1,0,38)==0);failStorage=true;nextCapture={};pending=true;tick(reboot);assert(status(reboot)[7]==5&&(status(reboot)[8]&1));failStorage=false;
 assert(action(reboot,1,0,38)==0);assert(action(reboot,2)==0&&status(reboot)[6]==255);
 assert(action(reboot,6,4,1)==0);assert(action(reboot,5,7)==0);reboot.disconnected();count=transmissions;tick(reboot);assert(transmissions==count&&status(reboot)[5]==0);
 assert(action(reboot,1,3,38)==0);nextCapture={};nextCapture.decode_type=UNKNOWN;pending=true;tick(reboot);tick(reboot);assert(action(reboot,4,3)==0&&rawTransmissions==2);
 tick(reboot);assert(action(reboot,3,0)==0);assert(action(reboot,4,0)==20);
 puts("PASS: actual IR learning, NVS reload, decoded/raw sends, rate limiting, targets, endpoint uncertainty, remote invalidation, timeout/cancel, failed save, disconnect, clear, no temperature-driven transmission");
}
'''
with tempfile.TemporaryDirectory(prefix='breeze-ir-test-') as directory:
    path = Path(directory)
    (path/'fake.h').write_text(stub)
    for header in ('Arduino.h','Preferences.h','IRrecv.h','IRsend.h','IRutils.h'):
        (path/header).write_text('#include "fake.h"\n')
    (path/'test.cpp').write_text(harness)
    (path/'run.cmd').write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 /I . /I "'+str(root/'firmware/BreezeController')+'" test.cpp /Fe:test.exe\nif errorlevel 1 exit /b 1\ntest.exe\n')
    subprocess.run([str(path/'run.cmd')],cwd=path,check=True)

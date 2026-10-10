"""Check actual FanRemote pacing against completed transmissions, without hardware."""
import ast
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
tree = ast.parse((ROOT / 'tests/ir_test.py').read_text(encoding='utf-8'))
assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == 'stub' for target in node.targets))
stub = ast.literal_eval(assignment.value)
stub = stub.replace('inline int transmissions=0,rawTransmissions=0;',
                    'inline int transmissions=0,rawTransmissions=0;inline std::vector<uint32_t> txStarts;')
stub = stub.replace('if(sendSupported)transmissions++;return sendSupported;',
                    'if(sendSupported){txStarts.push_back(clockMs);transmissions++;clockMs+=37;}return sendSupported;')
assert 'clockMs+=37;' in stub

HARNESS = r'''
#include "fake.h"
#define private public
#include "FanRemote.h"
#undef private
#include "FanRemote.cpp"

int action(FanRemote& f,int a,int v=0,int arg=0){uint8_t b[20]={1,6};b[4]=a;b[5]=v;b[6]=arg;return f.command(b);}
float celsius(float f){return (f-32)/1.8f;}
void at(FanRemote& f,uint32_t time,float body=98,float room=72){clockMs=time;f.tick(celsius(body),NAN,celsius(room),clockMs);}
void prepare(FanRemote& f,int setting=1,int rise=1,int fall=1){
 saved.clear();clockMs=100;pending=scheduledCapture=false;loopbackMode=0;sendSupported=true;failStorage=false;
 transmissions=rawTransmissions=0;txStarts.clear();f=FanRemote();f.begin();
 FanRemote::Learned code;code.count=12;code.bits=32;code.protocol=NEC;code.decoded=1;code.value=123;
 for(int i=0;i<12;i++)code.raw[i]=600;
 for(int slot=0;slot<3;slot++){assert(f.save(slot,code));f.learnedMask|=1u<<slot;}
 BreezeLogic::Profile p;p.averageSeconds=1;p.riseSeconds=rise;p.fallSeconds=fall;
 p.roomCentiF=7200;p.coolRoomCentiF=7000;p.rules[0].minimum=9;f.logic.apply(p);
 assert(action(f,FanRemote::SYNC,setting,1)==0);
}
void prime(FanRemote& f,int setting){
 assert(action(f,FanRemote::SEND,setting==9?2:1)==0);
 assert(action(f,FanRemote::SYNC,setting,1)==0&&action(f,FanRemote::AUTO,1)==0);
 assert(f.lastSend==clockMs&&clockMs==txStarts.back()+37);
}
void normal_and_longer_profiles(){
 for(int seconds:{1,20}){
  FanRemote f;prepare(f,1,seconds,seconds);prime(f,1);uint32_t end=f.lastSend;
  at(f,end);int before=transmissions;uint32_t interval=seconds==1?1000:2500;
  at(f,end+999);assert(transmissions==before&&f.estimated==1);
  if(seconds>1){at(f,end+1000);assert(transmissions==before);at(f,end+interval-1);assert(transmissions==before);}
  at(f,end+interval);assert(transmissions==before+1&&f.estimated==2&&txStarts.back()==end+interval);
  end=f.lastSend;at(f,end+interval-1);assert(transmissions==before+1);
  at(f,end+interval);assert(transmissions==before+2&&f.estimated==3);
 }
 // A slower configured fall remains slower than the minimum as well.
 FanRemote f;prepare(f,3,1,24);prime(f,3);uint32_t end=f.lastSend;at(f,end,97,69);int before=transmissions;
 at(f,end+1000,97,69);assert(transmissions==before);at(f,end+2999,97,69);assert(transmissions==before);
 at(f,end+3000,97,69);assert(transmissions==before+1&&f.estimated==2);
 // Deadband evaluation cancels pending work even during the new cooldown.
 end=f.lastSend;at(f,end+1,97,71);assert(f.logicDecision.hold&&f.logicDirection==0&&f.logicWanted==2);
 at(f,end+5000,97,71);assert(transmissions==before+1&&f.estimated==2);
}
void off_and_retained_power(){
 FanRemote f;prepare(f,3);prime(f,3);uint32_t end=f.lastSend;at(f,end,97,69);int before=transmissions;
 for(int expected=2;expected>=0;expected--){
  at(f,end+999,97,69);assert(transmissions==before);
  at(f,end+1000,97,69);assert(transmissions==++before&&f.estimated==expected);
  assert(f.lastSent==(expected?2:0));end=f.lastSend;
 }
 assert(!f.powerOn&&f.retainedSpeed==1);
 at(f,end+999);assert(transmissions==before&&!f.powerOn);
 at(f,end+1000);assert(transmissions==++before&&f.powerOn&&f.estimated==1&&f.lastSent==0);
 end=f.lastSend;at(f,end);at(f,end+999);assert(transmissions==before);
 at(f,end+1000);assert(transmissions==before+1&&f.estimated==2);
}
void boost_boundaries(){
 for(bool down:{false,true}){
  FanRemote f;int initial=down?9:5;prepare(f,initial);prime(f,initial);
  auto profile=f.logic.profile;profile.rules[0].minimum=3;profile.rules[0].bodyCentiF=10500;
  auto& boost=profile.rules[0].boost;boost.enabled=1;boost.extra=6;boost.riseSeconds=1;boost.holdSeconds=10;boost.returnSeconds=10;
  f.logic.apply(profile);uint32_t end=f.lastSend;
  // Set an already-active boost in its hold or return phase; the real engine
  // still computes the wanted level and the actual automatic send path runs.
  f.logic.boosts[0].active=true;f.logic.boosts[0].at=end-(down?16000:1000);
  int before=transmissions;at(f,end+999);assert(f.logicDecision.boost&&transmissions==before);
  assert(down?f.logicWanted<initial:f.logicWanted>initial);
  at(f,end+1000);assert(f.logicDecision.boost&&transmissions==before+1&&f.estimated==initial+(down?-1:1));
  assert(txStarts.back()==end+1000);
 }
}
void wake_pair(bool target,uint32_t start){
 FanRemote f;prepare(f,1);
 if(target){assert(action(f,FanRemote::TARGET,4)==0);at(f,start);}
 else{at(f,start);at(f,start+1000);}
 assert(transmissions==1&&f.estimated==2&&f.repeatSlot==1);uint32_t firstEnd=f.lastSend;
 at(f,firstEnd+299);assert(transmissions==1);
 at(f,firstEnd+300);assert(transmissions==2&&f.estimated==2&&f.repeatSlot==FanRemote::NONE);
 uint32_t secondEnd=f.lastSend;assert(secondEnd==firstEnd+337);
 at(f,secondEnd+999);assert(transmissions==2&&f.estimated==2);
 at(f,secondEnd+1000);assert(transmissions==3&&f.estimated==3&&txStarts.back()==secondEnd+1000);
}
void manual_and_caller_time(){
 FanRemote f;prepare(f,1);assert(action(f,FanRemote::SEND,1)==0&&f.estimated==2);
 clockMs++;assert(action(f,FanRemote::SEND,1)==0&&f.estimated==3); // Direct taps have no 1s floor.
 uint32_t end=f.lastSend;assert(action(f,FanRemote::TARGET,5)==0);int before=transmissions;
 clockMs=end+999;f.tick(celsius(98),NAN,celsius(72),clockMs+10000);assert(transmissions==before);
 clockMs=end+1000;f.tick(celsius(98),NAN,celsius(72),clockMs-500);assert(transmissions==before+1&&f.estimated==4);
}
int main(){
 normal_and_longer_profiles();off_and_retained_power();boost_boundaries();
 wake_pair(false,61001);wake_pair(true,61001);wake_pair(false,UINT32_MAX-1800);wake_pair(true,UINT32_MAX-500);
 manual_and_caller_time();
 puts("PASS: actual automatic/TARGET 999ms/1000ms completed-transmission boundaries, rise/fall/boost/OFF/retained Power, wake repeat remains300ms and resets full1s floor, rollover, longer profiles/deadband, manual taps unchanged and caller-time isolation");
}
'''

with tempfile.TemporaryDirectory(prefix='breeze-auto-interval-test-') as directory:
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

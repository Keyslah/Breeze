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
void at(FanRemote& f,uint32_t time,float body=98,float room=74){clockMs=time;f.tick(celsius(body),NAN,celsius(room),clockMs);}
void prepare(FanRemote& f,int setting=1,int rise=1,int fall=1){
 saved.clear();clockMs=100;pending=scheduledCapture=false;loopbackMode=0;sendSupported=true;failStorage=false;
 transmissions=rawTransmissions=0;txStarts.clear();f=FanRemote();f.begin();
 FanRemote::Learned code;code.count=12;code.bits=32;code.protocol=NEC;code.decoded=1;code.value=123;
 for(int i=0;i<12;i++)code.raw[i]=600;
 for(int slot=0;slot<3;slot++){assert(f.save(slot,code));f.learnedMask|=1u<<slot;}
 // Defaults put 98 F in a 74 F room at H; no steadiness margin keeps each case exact.
 BreezeLogic::Profile p;p.averageSeconds=1;p.riseSeconds=rise;p.fallSeconds=fall;p.steadinessCenti=0;f.logic.apply(p);
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
  at(f,end);int before=transmissions;uint32_t interval=seconds==1?1000:2223;
  at(f,end+999);assert(transmissions==before&&f.estimated==1);
  if(seconds>1){at(f,end+1000);assert(transmissions==before);at(f,end+interval-1);assert(transmissions==before);}
  at(f,end+interval);assert(transmissions==before+1&&f.estimated==2&&txStarts.back()==end+interval);
  end=f.lastSend;at(f,end+interval-1);assert(transmissions==before+1);
  at(f,end+interval);assert(transmissions==before+2&&f.estimated==3);
 }
 // A slower configured fall remains slower than the minimum as well.
 FanRemote f;prepare(f,3,1,24);prime(f,3);uint32_t end=f.lastSend;at(f,end,97,66);int before=transmissions;
 at(f,end+1000,97,66);assert(transmissions==before);at(f,end+2666,97,66);assert(transmissions==before);
 at(f,end+2667,97,66);assert(transmissions==before+1&&f.estimated==2);
 // Losing the room reading holds the fan and cancels pending work, even during the cooldown.
 end=f.lastSend;at(f,end+1,97,NAN);assert(!f.logicDecision.ready&&f.logicDirection==0&&f.logicWanted==2&&f.error==FanRemote::NO_ROOM);
 at(f,end+5000,97,NAN);assert(transmissions==before+1&&f.estimated==2);
}
void off_and_retained_power(){
 FanRemote f;prepare(f,3);prime(f,3);uint32_t end=f.lastSend;at(f,end,97,66);int before=transmissions;
 for(int expected=2;expected>=0;expected--){
  at(f,end+999,97,66);assert(transmissions==before);
  at(f,end+1000,97,66);assert(transmissions==++before&&f.estimated==expected);
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
  auto profile=f.logic.profile;
  auto& boost=profile.boost;boost.enabled=1;boost.extra=6;boost.riseSeconds=1;boost.holdSeconds=10;boost.returnSeconds=10;
  f.logic.apply(profile);uint32_t end=f.lastSend;
  // Set an already-active boost in its hold or return phase; the real engine
  // still computes the wanted level and the actual automatic send path runs.
  // 97 F in a 70 F room is range level 3 underneath the boost.
  f.logic.boost.active=true;f.logic.boost.at=end-(down?16000:1000);
  int before=transmissions;at(f,end+999,97,70);assert(f.logicDecision.boost&&f.logicDecision.baseline==3&&transmissions==before);
  assert(down?f.logicWanted<initial:f.logicWanted>initial);
  at(f,end+1000,97,70);assert(f.logicDecision.boost&&transmissions==before+1&&f.estimated==initial+(down?-1:1));
  assert(txStarts.back()==end+1000);
 }
}
void idle_step_once(bool target,uint32_t start,bool down){
 FanRemote f;int initial=down?3:1;prepare(f,initial);float body=down?97:98,room=down?66:74;
 if(target){assert(action(f,FanRemote::TARGET,down?1:4)==0);at(f,start,body,room);}
 else{at(f,start,body,room);at(f,start+1000,body,room);}
 int expected=initial+(down?-1:1);
 assert(transmissions==1&&f.sendCounter==1&&f.estimated==expected&&f.lastSent==(down?2:1));uint32_t end=f.lastSend;
 at(f,end+299,body,room);assert(transmissions==1&&f.estimated==expected);
 at(f,end+300,body,room);assert(transmissions==1&&f.sendCounter==1&&f.estimated==expected&&f.lastSend==end);
 at(f,end+999,body,room);assert(transmissions==1&&f.estimated==expected);
 at(f,end+1000,body,room);assert(transmissions==2&&f.sendCounter==2&&f.estimated==expected+(down?-1:1)&&txStarts.back()==end+1000);
}
void idle_power_once(bool powerOn,uint32_t start){
 FanRemote f;prepare(f,1);if(powerOn)assert(action(f,FanRemote::SYNC,0,1)==0);
 float body=powerOn?98:97,room=powerOn?74:66;at(f,start,body,room);
 assert(transmissions==1&&f.sendCounter==1&&f.lastSent==0&&f.powerOn==powerOn&&f.estimated==(powerOn?1:0));
 uint32_t end=f.lastSend;at(f,end,body,room);
 for(uint32_t elapsed:{299u,300u,999u}){at(f,end+elapsed,body,room);assert(transmissions==1&&f.sendCounter==1&&f.powerOn==powerOn&&f.lastSend==end);}
 at(f,end+1000,body,room);
 if(powerOn)assert(transmissions==2&&f.estimated==2&&f.lastSent==1&&txStarts.back()==end+1000);
 else{assert(transmissions==1&&!f.powerOn);at(f,end+1000);assert(transmissions==2&&f.powerOn&&f.estimated==1&&f.lastSent==0);}
}
void idle_boost_once(bool down,uint32_t start){
 FanRemote f;int initial=down?9:5;prepare(f,initial);auto profile=f.logic.profile;
 auto& boost=profile.boost;boost.enabled=1;boost.extra=6;boost.riseSeconds=1;boost.holdSeconds=10;boost.returnSeconds=10;f.logic.apply(profile);
 f.logic.boost.active=true;f.logic.boost.at=start-(down?16000:1000);at(f,start,97,70);
 int expected=initial+(down?-1:1);assert(f.logicDecision.boost&&transmissions==1&&f.sendCounter==1&&f.estimated==expected);
 uint32_t end=f.lastSend;at(f,end+300,97,70);assert(transmissions==1&&f.estimated==expected&&f.lastSend==end);
 at(f,end+999,97,70);assert(transmissions==1&&f.estimated==expected);
 at(f,end+1000,97,70);assert(transmissions==2&&f.sendCounter==2&&f.estimated==expected+(down?-1:1)&&txStarts.back()==end+1000);
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
 for(bool down:{false,true}){
  idle_step_once(false,61001,down);idle_step_once(true,61001,down);
  idle_step_once(false,UINT32_MAX-1800,down);idle_step_once(true,UINT32_MAX-500,down);
  idle_boost_once(down,61001);idle_boost_once(down,UINT32_MAX-500);
 }
 for(bool powerOn:{false,true}){idle_power_once(powerOn,61001);idle_power_once(powerOn,UINT32_MAX-500);}
 manual_and_caller_time();
 puts("PASS: actual automatic/TARGET 999ms/1000ms completed-transmission boundaries, exactly one send per step after long idle with no 300ms duplicate across rise/fall/boost/OFF/retained Power and rollover, longer profiles/missing-room hold, manual taps unchanged and caller-time isolation");
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

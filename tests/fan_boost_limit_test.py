"""Verify actual FanRemote boost sends stop at H, using IR spies without hardware."""
import ast
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
tree = ast.parse((ROOT / 'tests/ir_test.py').read_text(encoding='utf-8'))
assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == 'stub'
                          for target in node.targets))
stub = ast.literal_eval(assignment.value)

HARNESS = r'''
#include "fake.h"
#define private public
#include "FanRemote.h"
#undef private
#include "FanRemote.cpp"

float celsius(float f){return (f-32)/1.8f;}
int action(FanRemote& fan,int kind,int value=0,int argument=0){
 uint8_t packet[20]={1,6};packet[4]=kind;packet[5]=value;packet[6]=argument;
 return fan.command(packet);
}
void prepare(FanRemote& fan,int baseline,int extra,int curve,bool raw){
 saved.clear();clockMs=0;pending=scheduledCapture=false;loopbackMode=0;
 sendSupported=true;failStorage=false;transmissions=rawTransmissions=0;
 fan=FanRemote();fan.begin();
 FanRemote::Learned code;code.count=12;code.bits=32;
 code.protocol=raw?SYMPHONY:NEC;code.decoded=1;
 for(int i=0;i<12;i++)code.raw[i]=600;
 for(int slot=0;slot<3;slot++){
  code.value=123+slot;assert(fan.save(slot,code));fan.learnedMask|=1u<<slot;
 }
 BreezeLogic::Profile profile;profile.averageSeconds=1;profile.steadinessCenti=0;
 profile.bodyWeightCenti=0;profile.riseSeconds=profile.fallSeconds=1;
 profile.boost.enabled=1;profile.boost.extra=extra;profile.boost.curve=curve;
 profile.boost.riseSeconds=4;profile.boost.holdSeconds=4;profile.boost.returnSeconds=4;
 assert(BreezeLogic::valid(profile));fan.logic.apply(profile);
 assert(action(fan,FanRemote::SYNC,baseline,1)==FanRemote::OK);
 assert(action(fan,FanRemote::AUTO,1)==FanRemote::OK);
 assert(fan.doubleHitSeconds==0&&transmissions==0);
}
void boost_commands_stop_at_h(){
 for(bool raw:{false,true})for(int baseline:{8,9,10})
  for(int extra=1;extra<=9;extra++)for(int curve=0;curve<=1;curve++){
   FanRemote fan;prepare(fan,baseline,extra,curve,raw);
   const float room=68+(baseline-.5f)*.6f;
   const int peak=baseline+extra>10?10:baseline+extra;
   int upward=0,downward=0;bool reachedPeak=false;
   // Real sample history arms boost. The body rise at 31s triggers the complete cycle.
   for(uint32_t now=0;now<=47000;now+=100){
    const int before=transmissions,previous=fan.estimated;
    clockMs=now;fan.tick(celsius(now<31000?97:98.2f),NAN,celsius(room),now);
    assert(fan.synchronized&&fan.powerOn&&fan.estimated>=baseline&&fan.estimated<=10);
    assert(fan.logicDecision.ready&&fan.logicDecision.baseline==baseline);
    assert(fan.logicWanted>=baseline&&fan.logicWanted<=10);
    if(now<31000)assert(!fan.logicDecision.boost&&transmissions==0);
    if(now>=31000&&now<43000)assert(fan.logicDecision.boost);
    if(now>=43000)assert(!fan.logicDecision.boost);
    if(transmissions!=before){
     assert(transmissions==before+1); // One burst per actual controller step.
     if(fan.lastSent==1){assert(previous<10&&fan.estimated==previous+1);upward++;}
     else{assert(fan.lastSent==2&&fan.estimated==previous-1);downward++;}
    }
    if(fan.estimated==peak)reachedPeak=true;
    // Once the plateau is reached, holding H cannot issue an extra Speed+.
    if(now>=35000&&now<39000)assert(fan.estimated==peak);
   }
   assert(reachedPeak&&fan.estimated==baseline&&fan.repeatSlot==FanRemote::NONE);
   assert(upward==peak-baseline&&downward==peak-baseline);
   assert(transmissions==2*(peak-baseline)&&fan.sendCounter==transmissions);
   assert(rawTransmissions==(raw?transmissions:0));
   // In particular, 9->H is exactly one Speed+; starting at H sends none.
   if(baseline==9)assert(upward==1&&downward==1);
   if(baseline==10)assert(transmissions==0);
  }
}
int main(){
 boost_commands_stop_at_h();
 puts("PASS: actual FanRemote boost for extras 1-9 and both curves from levels 8-H; decoded/raw sends stop at H through rise/hold/return; synchronized 9->H sends exactly one Speed+ and baseline H sends none. IR reception is not modeled.");
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix='breeze-boost-limit-test-') as directory:
        path = Path(directory)
        (path / 'fake.h').write_text(stub, encoding='utf-8')
        for header in ('Arduino.h', 'Preferences.h', 'IRrecv.h', 'IRsend.h', 'IRutils.h', 'nvs.h'):
            (path / header).write_text('#include "fake.h"\n', encoding='utf-8')
        (path / 'test.cpp').write_text(HARNESS, encoding='utf-8')
        (path / 'run.cmd').write_text(
            '@echo off\n'
            'call "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\n'
            'cl /nologo /EHsc /std:c++17 /I . /I "' + str(ROOT / 'firmware/BreezeController') + '" test.cpp /Fe:test.exe\n'
            'if errorlevel 1 exit /b 1\n'
            'test.exe\n', encoding='utf-8')
        subprocess.run([str(path / 'run.cmd')], cwd=path, check=True)


if __name__ == '__main__':
    main()

"""Exercise the hardware-timed IR output in actual firmware with local hardware spies."""
import ast
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
tree = ast.parse((ROOT / 'tests/ir_test.py').read_text(encoding='utf-8'))
assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == 'stub' for target in node.targets))
stub = ast.literal_eval(assignment.value)

HARNESS = r'''
#include "fake.h"
#define private public
#include "FanRemote.h"
#undef private
#include "FanRemote.cpp"

int action(FanRemote& f,int kind,int value=0,int argument=0){
 uint8_t b[20]={1,6};b[4]=kind;b[5]=value;b[6]=argument;b[7]=argument>>8;return f.command(b);
}
void reset(){
 saved.clear();clockMs=100;pending=scheduledCapture=false;loopbackMode=0;sendSupported=true;failStorage=false;
 transmissions=rawTransmissions=0;lastRaw.clear();rawCarriers.clear();rawSenderPins.clear();
 rmtAvailable=rmtWriteWorks=true;rmtAttached=false;rmtAttaches=rmtReleases=rmtWrites=0;rmtPin=-1;rmtTickHz=rmtCarrierHz=0;
}
std::vector<uint16_t> burst(int count){std::vector<uint16_t> raw(count);for(int i=0;i<count;i++)raw[i]=i%2?1200+i:400+i;return raw;}
void learn(FanRemote& f,int slot,const std::vector<uint16_t>& raw,int protocol,int carrier=38){
 FanRemote::Learned code;code.count=raw.size();code.bits=12;code.protocol=protocol;code.decoded=1;code.value=0xD80+slot;code.carrier=carrier;
 for(size_t i=0;i<raw.size();i++)code.raw[i]=raw[i];
 assert(f.save(slot,code));f.learnedMask|=1u<<slot;
}
void packing(){
 rmt_data_t out[8];
 // Mark-terminated bursts end on a zero second half; nothing is padded or dropped.
 uint16_t odd[]={600,1200,650};
 assert(IrOutput::pack(odd,3,out,8)==2);
 assert(out[0].level0==1&&out[0].duration0==600&&out[0].level1==0&&out[0].duration1==1200);
 assert(out[1].level0==1&&out[1].duration0==650&&out[1].duration1==0);
 uint16_t even[]={600,1200};
 assert(IrOutput::pack(even,2,out,8)==1&&out[0].duration1==1200&&out[0].level1==0);
 // A timing wider than 15 bits continues at the same level and keeps its total.
 uint16_t wide[]={40000,65535,1};
 assert(IrOutput::pack(wide,3,out,8)==3);
 assert(out[0].level0==1&&out[0].duration0==32767&&out[0].level1==1&&out[0].duration1==7233);
 assert(out[1].level0==0&&out[1].duration0==32767&&out[1].level1==0&&out[1].duration1==32767);
 assert(out[2].level0==0&&out[2].duration0==1&&out[2].level1==1&&out[2].duration1==1);
 // Empty or oversized bursts are refused instead of being truncated.
 assert(IrOutput::pack(odd,0,out,8)==0&&IrOutput::pack(odd,3,out,1)==0&&IrOutput::pack(even,2,out,1)==1);
 std::vector<uint16_t> largest=burst(768);static rmt_data_t full[IrOutput::CAPACITY];
 assert(IrOutput::pack(largest.data(),768,full,IrOutput::CAPACITY)==384);
}
void hardware_timing_is_the_normal_path(){
 for(int count:{215,191,12,768}){
  reset();FanRemote f;f.begin();
  assert(rmtAttaches==1&&rmtTickHz==1000000&&rmtPin==IR_TRANSMIT_PIN&&f.irTiming()==1&&transmissions==0&&rmtWrites==0);
  auto raw=burst(count);learn(f,1,raw,SYMPHONY);
  assert(action(f,FanRemote::SEND,1)==FanRemote::OK);
  assert(rmtWrites==1&&transmissions==1&&rawTransmissions==1&&lastRaw==raw);
  assert(rawCarriers.back()==38&&rawSenderPins.back()==IR_TRANSMIT_PIN&&rmtCarrierHz==38000);
  assert(f.irTiming()==1&&f.softwareBurstCount()==0&&rmtAttaches==1&&rmtReleases==0);
  // A second burst reuses the attached timer and its carrier.
  clockMs+=2000;assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&rmtWrites==2&&rmtAttaches==1&&lastRaw==raw);
 }
 // The carrier follows each learned code.
 reset();FanRemote f;f.begin();learn(f,1,burst(23),SYMPHONY,38);learn(f,2,burst(23),SYMPHONY,40);
 assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&rmtCarrierHz==38000);
 clockMs+=2000;assert(action(f,FanRemote::SEND,2)==FanRemote::OK&&rmtCarrierHz==40000&&rawCarriers.back()==40&&rmtAttaches==1);
}
void software_timing_only_when_the_timer_fails(){
 // A failed write still sends the burst, is counted, and the next burst tries the timer again.
 reset();FanRemote f;f.begin();auto raw=burst(215);learn(f,1,raw,SYMPHONY);
 rmtWriteWorks=false;
 assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&rmtWrites==0&&transmissions==1&&rawTransmissions==1&&lastRaw==raw);
 assert(f.softwareBurstCount()==1&&f.irTiming()==0&&rmtReleases==1&&f.sendCounter==1);
 rmtWriteWorks=true;clockMs+=2000;
 assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&rmtWrites==1&&transmissions==2&&f.softwareBurstCount()==1&&f.irTiming()==1&&rmtAttaches==2);
 // A timer that cannot start leaves every burst on software timing without losing any.
 reset();rmtAvailable=false;FanRemote g;g.begin();learn(g,1,raw,SYMPHONY);
 assert(g.irTiming()==0&&rmtAttaches==0&&transmissions==0);
 for(int i=1;i<=3;i++){clockMs+=2000;assert(action(g,FanRemote::SEND,1)==FanRemote::OK&&transmissions==i&&rmtWrites==0&&g.softwareBurstCount()==i&&lastRaw==raw);}
 g.softwareBursts=255;clockMs+=2000;assert(action(g,FanRemote::SEND,1)==FanRemote::OK&&g.softwareBurstCount()==255);
}
void library_protocols_get_the_pin_back(){
 reset();FanRemote f;f.begin();auto raw=burst(67);learn(f,1,raw,NEC);learn(f,2,raw,SYMPHONY);
 assert(f.irTiming()==1);
 assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&transmissions==1&&rawTransmissions==0&&rmtWrites==0);
 assert(rmtReleases==1&&f.irTiming()==0&&f.softwareBurstCount()==0);
 // The next learned raw burst takes the timer again.
 clockMs+=2000;assert(action(f,FanRemote::SEND,2)==FanRemote::OK&&rmtWrites==1&&rawTransmissions==1&&f.irTiming()==1&&rmtAttaches==2);
 // A protocol the library cannot send falls through to the timed raw replay.
 sendSupported=false;clockMs+=2000;
 assert(action(f,FanRemote::SEND,1)==FanRemote::OK&&rmtWrites==2&&rawTransmissions==2&&lastRaw==raw&&f.softwareBurstCount()==0);
}
void receiver_check_uses_the_same_output(){
 reset();FanRemote f;f.begin();
 assert(action(f,FanRemote::RECEIVER_TEST)==FanRemote::OK);
 clockMs+=5000;f.tick(NAN,NAN,NAN,clockMs);
 assert(rmtWrites==1&&rawTransmissions==1&&lastRaw.size()==27&&rawCarriers.back()==38&&f.softwareBurstCount()==0);
}
int main(){
 packing();hardware_timing_is_the_normal_path();software_timing_only_when_the_timer_fails();
 library_protocols_get_the_pin_back();receiver_check_uses_the_same_output();
 puts("PASS: actual firmware sends learned raw bursts and the receiver check through the hardware timer at 1 us ticks with the learned carrier on D7; exact mark/space reconstruction for odd, even, 215/768-timing and >15-bit values; counted software fallback with retry; library protocols release and re-take the pin. Optical output is not modeled.");
}
'''

with tempfile.TemporaryDirectory(prefix='breeze-ir-output-test-') as directory:
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

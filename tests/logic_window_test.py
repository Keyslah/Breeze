"""Exercise actual firmware time-window configuration, clock, and automatic output."""
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

constexpr uint32_t midnight=1700006400;
int action(FanRemote& f,int kind,int value=0,int argument=0){
 uint8_t b[20]={1,6};b[4]=kind;b[5]=value;b[6]=argument;b[7]=argument>>8;return f.command(b);
}
int clockCommand(FanRemote& f,uint32_t epoch,int offset=0,uint32_t transition=0,int nextOffset=0){
 uint8_t b[20]={1,6};b[4]=FanRemote::LOGIC_CLOCK;b[5]=1;set32(b+6,epoch);set16(b+10,offset);set32(b+12,transition);set16(b+16,nextOffset);return f.command(b);
}
int windowCommand(FanRemote& f,int enabled=1,int start=270,int end=480,int adjustment=-1){
 uint8_t b[20]={1,6};b[4]=FanRemote::LOGIC_WINDOW;b[5]=enabled;set16(b+6,start);set16(b+8,end);b[10]=adjustment;b[11]=1;return f.command(b);
}
int indexedWindowCommand(FanRemote& f,int index,int count,int adjustment=-1,int start=270,int end=480,int enabled=1){
 uint8_t b[20]={1,6};b[4]=FanRemote::LOGIC_WINDOW;b[5]=enabled;set16(b+6,start);set16(b+8,end);b[10]=adjustment;b[11]=2;set16(b+12,index);set16(b+14,count);return f.command(b);
}
void stageProfile(FanRemote& f,const BreezeLogic::Profile& p){
 uint8_t b[20]={1,6};b[4]=FanRemote::LOGIC_GLOBAL;set16(b+6,p.averageSeconds);set16(b+8,p.riseSeconds);set16(b+10,p.fallSeconds);set16(b+12,p.steadinessCenti);b[15]=3;assert(f.command(b)==0);
 memset(b+4,0,16);b[4]=FanRemote::LOGIC_RULE;set16(b+6,p.offRoomCentiF);set16(b+8,p.offBodyCentiF);set16(b+10,p.fullRoomCentiF);set16(b+12,p.fullBodyCentiF);set16(b+14,p.bodyWeightCenti);b[16]=3;assert(f.command(b)==0);
 memset(b+4,0,16);const auto& boost=p.boost;b[4]=FanRemote::LOGIC_BOOST;b[6]=boost.enabled;b[7]=boost.extra;set16(b+8,boost.triggerCentiF);set16(b+10,boost.windowSeconds);set16(b+12,boost.riseSeconds);set16(b+14,boost.holdSeconds);set16(b+16,boost.returnSeconds);b[18]=boost.curve;b[19]=3;assert(f.command(b)==0);
}
void configure(FanRemote& f,int adjustment=-1,int start=270,int end=480){
 stageProfile(f,f.logic.profile);assert(windowCommand(f,1,start,end,adjustment)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
}
void configurePair(FanRemote& f,int first=-1,int second=-3){
 stageProfile(f,f.logic.profile);assert(indexedWindowCommand(f,0,2,first)==0);assert(indexedWindowCommand(f,1,2,second)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
}
float celsius(float f){return (f-32)/1.8f;}
void tick(FanRemote& f,uint32_t elapsed,float body=98,float room=73){clockMs+=elapsed;f.tick(celsius(body),NAN,celsius(room),clockMs);}
void reset(){saved.clear();clockMs=100;pending=scheduledCapture=false;failStorage=false;loopbackMode=0;transmissions=0;nvsWrites=nvsRemoves=0;}
void prepare(FanRemote& f,int setting=5){
 reset();f.begin();
 for(int slot=0;slot<3;slot++){FanRemote::Learned code;code.count=12;code.decoded=1;code.protocol=NEC;code.bits=32;code.value=100+slot;for(auto& pulse:code.raw)pulse=600;assert(f.save(slot,code));f.learnedMask|=1<<slot;}
 BreezeLogic::Profile profile;profile.fullRoomCentiF=7800;profile.fullBodyCentiF=9800;profile.steadinessCenti=0;profile.averageSeconds=1;profile.riseSeconds=profile.fallSeconds=1;
 stageProfile(f,profile);assert(action(f,FanRemote::LOGIC_COMMIT)==0);assert(action(f,FanRemote::SYNC,setting,1)==0);
}
void clock_boundaries(){
 using namespace BreezeLogic;
 TimeWindow window;window.enabled=1;assert(window.valid());
 WindowClock c;assert(!c.ready()&&c.adjustment(window)==0);
 assert(c.set(100,midnight+4*3600+29*60+59,0,0,0));assert(c.adjustment(window)==0);
 c.advance(1100);assert(c.adjustment(window)==-1);
 c.advance(1100+(3*3600+30*60)*1000);assert(c.adjustment(window)==0);
 assert(c.set(100,midnight+9*3600+30*60,-300,0,0));assert(c.adjustment(window)==-1);
 window.startMinutes=23*60;window.endMinutes=60;window.adjustment=2;
 assert(c.set(100,midnight+23*3600,0,0,0)&&c.adjustment(window)==2);
 c.advance(3600100);assert(c.adjustment(window)==2);
 c.advance(7200100);assert(c.adjustment(window)==0);
 window.startMinutes=270;window.endMinutes=480;window.adjustment=-1;
 const uint32_t base=UINT32_MAX-500;
 assert(c.set(base,midnight+4*3600+29*60+59,0,0,0));c.advance(base+1000);assert(c.adjustment(window)==-1);
 // Repeated subsecond ticks retain their fractions instead of slowing down the clock.
 assert(c.set(100,midnight+4*3600+29*60+59,0,0,0));for(int t=200;t<=1100;t+=100)c.advance(t);assert(c.adjustment(window)==-1);
 // Spring transition skips the active window, even while the phone is disconnected.
 window.startMinutes=120;window.endMinutes=180;
 assert(c.set(100,midnight+7*3600+59*60+59,-360,midnight+8*3600,-300));assert(c.adjustment(window)==0);
 c.advance(1100);assert(c.adjustment(window)==0);
 // Fall transition repeats 01:30 with the correct new offset.
 window.startMinutes=90;window.endMinutes=120;
 assert(c.set(100,midnight+7*3600-1,-300,midnight+7*3600,-360));assert(c.adjustment(window)==-1);
 c.advance(1100);assert(c.adjustment(window)==0);c.advance(1801100);assert(c.adjustment(window)==-1);
 auto old=c;assert(!c.set(0,1600000000,0,0,0));assert(!c.set(0,midnight,841,0,0));assert(!c.set(0,midnight,0,midnight-1,0));assert(!c.set(0,midnight,0,0,-841));assert(c.adjustment(window)==old.adjustment(window));
 for(int level=0;level<=10;level++)for(int amount=-80;amount<=80;amount++){int expected=level+amount;expected=expected<0?0:expected>10?10:expected;assert(adjustedTarget(level,amount)==expected);}
}
void cumulative_boundaries(){
 using namespace BreezeLogic;
 FanRemote f;prepare(f);stageProfile(f,f.logic.profile);
 assert(indexedWindowCommand(f,0,2,-1,270,480)==0);assert(indexedWindowCommand(f,1,2,-3,300,360)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 auto& windows=f.logicWindows;auto& c=f.windowClock;assert(windows.enabled()&&c.adjustment(windows)==0);
 assert(c.set(100,midnight+269*60,0,0,0)&&c.adjustment(windows)==0);
 assert(c.set(100,midnight+270*60,0,0,0)&&c.adjustment(windows)==-1);
 assert(c.set(100,midnight+300*60,0,0,0)&&c.adjustment(windows)==-4);
 assert(c.set(100,midnight+360*60,0,0,0)&&c.adjustment(windows)==-1);
 assert(c.set(100,midnight+480*60,0,0,0)&&c.adjustment(windows)==0);
 stageProfile(f,f.logic.profile);assert(indexedWindowCommand(f,0,2,-1,1380,60)==0);assert(indexedWindowCommand(f,1,2,-3,1410,30)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 assert(c.set(100,midnight+1380*60,0,0,0)&&c.adjustment(windows)==-1);
 assert(c.set(100,midnight+1410*60,0,0,0)&&c.adjustment(windows)==-4);
 assert(c.set(100,midnight+86400,0,0,0)&&c.adjustment(windows)==-4);
 assert(c.set(100,midnight+86400+30*60,0,0,0)&&c.adjustment(windows)==-1);
 assert(c.set(100,midnight+86400+60*60,0,0,0)&&c.adjustment(windows)==0);
 stageProfile(f,f.logic.profile);assert(indexedWindowCommand(f,0,2,-1,1380,60)==0);assert(indexedWindowCommand(f,1,2,-3,1410,30,0)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 assert(c.set(100,midnight+1410*60,0,0,0)&&c.adjustment(windows)==-1);
}
void validation_and_atomic_config(){
 FanRemote f;prepare(f);const int writes=nvsWrites;const auto stored=saved;
 assert(windowCommand(f)==FanRemote::BAD_VALUE);
 stageProfile(f,f.logic.profile);assert(windowCommand(f,2)==FanRemote::BAD_VALUE);assert(action(f,FanRemote::LOGIC_COMMIT)==FanRemote::BAD_VALUE);
 stageProfile(f,f.logic.profile);assert(windowCommand(f,1,1440)==FanRemote::BAD_VALUE);
 stageProfile(f,f.logic.profile);assert(windowCommand(f,1,270,1440)==FanRemote::BAD_VALUE);
 stageProfile(f,f.logic.profile);assert(windowCommand(f,1,270,270)==FanRemote::BAD_VALUE);
 stageProfile(f,f.logic.profile);assert(windowCommand(f,1,270,480,-11)==FanRemote::BAD_VALUE);
 stageProfile(f,f.logic.profile);assert(windowCommand(f,1,270,480,11)==FanRemote::BAD_VALUE);
 stageProfile(f,f.logic.profile);assert(windowCommand(f)==0);assert(!f.logicWindows.enabled());assert(action(f,FanRemote::LOGIC_COMMIT)==0);assert(f.logicWindows.enabled());
 assert(nvsWrites==writes&&saved==stored); // Window alone never writes NVS.
 configure(f,2,1380,60);assert(f.logicWindows.count==1&&f.logicWindows.adjustments[1380]==2&&f.logicWindows.adjustments[60]==0&&nvsWrites==writes);
 BreezeLogic::Profile changed=f.logic.profile;changed.riseSeconds++;
 stageProfile(f,changed);assert(windowCommand(f,1,60,120,-3)==0);failStorage=true;assert(action(f,FanRemote::LOGIC_COMMIT)==FanRemote::STORAGE);failStorage=false;assert(f.logicWindows.adjustments[1380]==2&&f.logic.profile.riseSeconds!=changed.riseSeconds);
 stageProfile(f,f.logic.profile);assert(action(f,FanRemote::LOGIC_COMMIT)==0);assert(!f.logicWindows.enabled()&&f.logicWindows.count==0); // Legacy four-part upload explicitly clears every window.
 configure(f);FanRemote reboot;reboot.begin();assert(!reboot.logicWindows.enabled()&&!reboot.windowClock.ready());
}
void indexed_atomic_config(){
 using namespace BreezeLogic;
 FanRemote f;prepare(f);configure(f,2);assert(clockCommand(f,midnight+5*3600)==0);const int writes=nvsWrites;
 Profile changed=f.logic.profile;changed.riseSeconds++;
 stageProfile(f,changed);assert(indexedWindowCommand(f,1,2,-3)==0);
 assert(action(f,FanRemote::LOGIC_COMMIT)==FanRemote::BAD_VALUE);
 assert(f.logicWindows.count==1&&f.windowClock.adjustment(f.logicWindows)==2&&f.logic.profile.riseSeconds!=changed.riseSeconds&&nvsWrites==writes);
 assert(indexedWindowCommand(f,0,2,-1)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 assert(f.logicWindows.count==2&&f.windowClock.adjustment(f.logicWindows)==-4&&f.logic.profile.riseSeconds==changed.riseSeconds);
 const int afterChange=nvsWrites;
 // Invalid uploads cannot change the live set or sneak through a later COMMIT.
 for(int kind=0;kind<11;kind++){
  stageProfile(f,f.logic.profile);assert(indexedWindowCommand(f,0,2,-1)==0);int result=0;
  switch(kind){
   case 0:result=indexedWindowCommand(f,1,3,-3);break; // Mismatched counts.
   case 1:result=windowCommand(f);break; // Mixed legacy and indexed formats.
   case 2:result=indexedWindowCommand(f,2,2);break;
   case 3:result=indexedWindowCommand(f,0,0);break;
   case 4:result=indexedWindowCommand(f,0,MAX_TIME_WINDOW_SEGMENTS+1);break;
   case 5:result=indexedWindowCommand(f,1,2,-11);break;
   case 6:result=indexedWindowCommand(f,1,2,-3,300,300);break;
   case 7:result=indexedWindowCommand(f,1,2,-3,300,360,2);break;
   case 10:result=indexedWindowCommand(f,0,2);break; // Repeated indices cannot be accumulated twice.
   default:{uint8_t b[20]={1,6};b[4]=FanRemote::LOGIC_WINDOW;b[5]=1;set16(b+6,270);set16(b+8,480);b[10]=-3;b[11]=kind==8?3:2;set16(b+12,1);set16(b+14,2);if(kind==9)b[16]=1;result=f.command(b);break;}
  }
  assert(result==FanRemote::BAD_VALUE&&action(f,FanRemote::LOGIC_COMMIT)==FanRemote::BAD_VALUE);
  assert(f.windowClock.adjustment(f.logicWindows)==-4&&nvsWrites==afterChange);
 }
 stageProfile(f,f.logic.profile);assert(windowCommand(f)==0);assert(indexedWindowCommand(f,0,1)==FanRemote::BAD_VALUE);assert(action(f,FanRemote::LOGIC_COMMIT)==FanRemote::BAD_VALUE);
 // GLOBAL abandons incomplete staging. A smaller set never retains old entries.
 stageProfile(f,f.logic.profile);assert(indexedWindowCommand(f,7,8,-10)==0);
 stageProfile(f,f.logic.profile);assert(indexedWindowCommand(f,0,1,3)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 assert(f.logicWindows.count==1&&f.windowClock.adjustment(f.logicWindows)==3&&nvsWrites==afterChange);
 // The representation supports every minute, including indices above 255 and bitset boundaries.
 for(int adjustment:{-10,10}){
  stageProfile(f,f.logic.profile);
  for(int i=0;i<MAX_TIME_WINDOW_SEGMENTS-1;i++)assert(indexedWindowCommand(f,i,MAX_TIME_WINDOW_SEGMENTS,adjustment)==0);
  assert(action(f,FanRemote::LOGIC_COMMIT)==FanRemote::BAD_VALUE);
  assert(indexedWindowCommand(f,MAX_TIME_WINDOW_SEGMENTS-1,MAX_TIME_WINDOW_SEGMENTS,adjustment)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
  assert(f.logicWindows.count==MAX_TIME_WINDOW_SEGMENTS&&f.windowClock.adjustment(f.logicWindows)==adjustment);
  uint8_t status[20];f.logicStatusPacket(status,clockMs);assert(int8_t(status[18])==adjustment);
 }
 // Large intermediate sums cannot saturate before opposite signed spans arrive.
 stageProfile(f,f.logic.profile);
 for(int i=0;i<MAX_TIME_WINDOW_SEGMENTS;i++)assert(indexedWindowCommand(f,i,MAX_TIME_WINDOW_SEGMENTS,i<720?-10:10)==0);
 assert(action(f,FanRemote::LOGIC_COMMIT)==0&&f.windowClock.adjustment(f.logicWindows)==0);
 // A maximal compiled schedule covers every minute exactly, including 23:59 -> 00:00.
 stageProfile(f,f.logic.profile);
 for(int i=0;i<MINUTES_PER_DAY;i++)assert(indexedWindowCommand(f,i,MINUTES_PER_DAY,i%2?1:-1,i,(i+1)%MINUTES_PER_DAY)==0);
 assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 for(int i=0;i<MINUTES_PER_DAY;i++)assert(f.logicWindows.adjustments[i]==(i%2?1:-1));
 configure(f,-1);assert(f.logicWindows.count==1&&f.windowClock.adjustment(f.logicWindows)==-1); // Legacy replaces the entire set.
 stageProfile(f,f.logic.profile);assert(action(f,FanRemote::LOGIC_COMMIT)==0);assert(f.logicWindows.count==0&&f.windowClock.adjustment(f.logicWindows)==0);
}
void active_window_and_sync_do_not_accumulate(){
 FanRemote f;prepare(f);configure(f);assert(clockCommand(f,midnight+5*3600)==0);
 tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==4);tick(f,1000);assert(f.estimated==4);
 int count=transmissions;
 for(int i=0;i<20;i++){assert(action(f,FanRemote::SYNC,4,1)==0);tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==4&&f.estimated==4);}
 assert(transmissions==count);
 uint8_t status[20];f.logicStatusPacket(status,clockMs);assert(int8_t(status[18])==-1&&status[3]==4);
 // Hysteresis retains the ordinary level despite repeated corrected physical output.
 BreezeLogic::Profile p=f.logic.profile;p.steadinessCenti=30;stageProfile(f,p);assert(windowCommand(f)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 for(int i=0;i<10;i++){assert(action(f,FanRemote::SYNC,4,1)==0);tick(f,1000,98,72.8f);assert(f.logicBaseTarget==5&&f.logicWanted==4);}
 // Leaving the interval restores the ordinary target, without using the adjusted estimate.
 assert(clockCommand(f,midnight+8*3600)==0);tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==5);tick(f,1000);assert(f.estimated==5);
 configure(f,2);assert(clockCommand(f,midnight+5*3600)==0);tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==7);
 f.disconnected();tick(f,1000);assert(f.logicWanted==7); // Clock and schedule stay active while phone sleeps/disconnects.
}
void cumulative_output_and_sync_do_not_accumulate(){
 FanRemote f;prepare(f);configurePair(f);assert(clockCommand(f,midnight+5*3600)==0);
 for(int i=0;i<12;i++){tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==1);}
 assert(f.estimated==1);const int count=transmissions;
 for(int i=0;i<20;i++){assert(action(f,FanRemote::SYNC,1,1)==0);tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==1&&f.estimated==1);}
 assert(transmissions==count);uint8_t status[20];f.logicStatusPacket(status,clockMs);assert(int8_t(status[18])==-4&&status[3]==1);
 // Mixed signs are combined before Off/H clamping, independent of list order.
 configurePair(f,-10,10);tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==5);
 configurePair(f,10,-10);tick(f,1000);assert(f.logicWanted==5);
 configurePair(f,10,-3);tick(f,1000);assert(f.logicWanted==10);
 configurePair(f,-10,3);tick(f,1000);assert(f.logicWanted==0);
 configurePair(f);assert(clockCommand(f,midnight+8*3600)==0);tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==5);
}
void off_and_full_are_clamped(){
 FanRemote f;prepare(f,1);configurePair(f,-10,-10);assert(clockCommand(f,midnight+5*3600)==0);
 tick(f,1000,98,69);assert(f.logicWanted==0&&f.estimated==0&&!f.powerOn);
 const int count=transmissions;for(int i=0;i<20;i++){tick(f,1000,98,68);assert(f.estimated==0&&!f.powerOn);}
 assert(transmissions==count); // Never send Speed - while Off or wrap to H.
 configurePair(f,10,10);tick(f,1000,98,78);assert(f.logicWanted==10);
 for(int i=0;i<15;i++)tick(f,1000,98,78);assert(f.estimated==10&&f.powerOn);
 const int maximum=transmissions;for(int i=0;i<20;i++)tick(f,1000,98,100);assert(f.estimated==10&&transmissions==maximum);
}
void pause_clock_and_missing_input(){
 FanRemote f;prepare(f);configurePair(f);assert(action(f,FanRemote::AUTO,0)==0);const int count=transmissions,writes=nvsWrites;
 assert(clockCommand(f,midnight+5*3600)==0);tick(f,1000);assert(!f.automatic&&transmissions==count&&nvsWrites==writes);
 uint8_t invalid[20]={1,6};invalid[4]=FanRemote::LOGIC_CLOCK;invalid[5]=2;assert(f.command(invalid)==FanRemote::BAD_VALUE);assert(!f.automatic);
 assert(action(f,FanRemote::AUTO,1)==0);tick(f,1000,NAN,73);assert(f.estimated==5&&transmissions==count&&f.error==FanRemote::NO_BODY);
 // Before the first ready evaluation, a physical SYNC is not a normal-level seed.
 assert(action(f,FanRemote::SYNC,1,1)==0);assert(!f.logicBaselineReady);tick(f,1000);assert(f.logicBaseTarget==5&&f.logicWanted==1);
 // Manual speed changes remain ordinary button behavior and pause automatic control.
 assert(action(f,FanRemote::SEND,1)==0);const int manual=f.estimated;tick(f,1000);assert(!f.automatic&&f.estimated==manual);
}
void boost_then_window(){
 FanRemote f;prepare(f);BreezeLogic::Profile p=f.logic.profile;p.bodyWeightCenti=0;p.boost.enabled=1;p.boost.extra=2;p.boost.windowSeconds=1;p.boost.riseSeconds=1;p.boost.holdSeconds=3;p.boost.returnSeconds=1;
 stageProfile(f,p);assert(windowCommand(f,1,270,480,-2)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);assert(clockCommand(f,midnight+5*3600)==0);
 tick(f,1000,95);tick(f,1000,95);tick(f,1000,97);assert(f.logicDecision.boost);tick(f,1000,97);
 assert(f.logicBaseTarget==5&&f.logicDecision.target==7&&f.logicWanted==5);
 tick(f,4000,97);assert(!f.logicDecision.boost&&f.logicBaseTarget==5&&f.logicWanted==3);
 stageProfile(f,p);assert(indexedWindowCommand(f,0,2,-1)==0);assert(indexedWindowCommand(f,1,2,-3)==0);assert(action(f,FanRemote::LOGIC_COMMIT)==0);
 tick(f,1000,95);tick(f,1000,95);tick(f,1000,97);assert(f.logicDecision.boost);tick(f,1000,97);
 assert(f.logicBaseTarget==5&&f.logicDecision.target==7&&f.logicWanted==3);
 tick(f,4000,97);assert(!f.logicDecision.boost&&f.logicBaseTarget==5&&f.logicWanted==1);
}
int main(){clock_boundaries();cumulative_boundaries();validation_and_atomic_config();indexed_atomic_config();active_window_and_sync_do_not_accumulate();cumulative_output_and_sync_do_not_accumulate();off_and_full_are_clamped();pause_clock_and_missing_input();boost_then_window();puts("PASS: actual firmware cumulative windows (-1 + -3 = -4), mixed signs before final clamp, all 1440 daily schedule segments, atomic indexed/legacy replacement and rejection; daily/overnight/DST boundaries; baseline-preserving corrections; Off/H clamps without wrap; locked/disconnected operation, pause/manual/missing-input preservation; adjustments after boost.");}
'''

with tempfile.TemporaryDirectory(prefix='breeze-window-test-') as directory:
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

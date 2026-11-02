"""Exercise first manual sends through real firmware with hardware-only spies.

This checks calls into IRsend, not optical output, protocol timing, or fan response.
Run on Windows with Visual Studio 2022 Community C++ tools installed.
"""
import ast
from pathlib import Path
import re
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]
source = (root / 'firmware/BreezeController/BreezeController.ino').read_text(encoding='utf-8')


def function(name):
    start = re.search(r'void ' + name + r'\([^;{}]*\)\s*\{', source).start()
    opening = source.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


# Reuse only the existing hardware spies and helper declarations. Parsing avoids
# executing the broader test or depending on its generated legacy-build fixture.
assignments = {
    node.targets[0].id: node.value
    for node in ast.parse((root / 'tests/ir_test.py').read_text(encoding='utf-8')).body
    if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
}
stub = ast.literal_eval(assignments['stub'])
# Optional acceptance model counts every emitted complete burst, including an
# unintended delayed burst. It is not evidence of physical fan reception.
stub = stub.replace('inline int transmissions=0,rawTransmissions=0;', r'''
inline int transmissions=0,rawTransmissions=0;
inline int modeledSetting=1,modeledSlot=0;
inline bool modelAcceptsBursts=false;
inline void acceptModeledBurst(){
 if(!modelAcceptsBursts)return;
 assert(modeledSlot==1||modeledSlot==2);
 modeledSetting=modeledSlot==1?(modeledSetting==10?1:modeledSetting+1)
                              :(modeledSetting==1?10:modeledSetting-1);
}
''')
assert 'if(sendSupported)transmissions++;' in stub
assert 'transmissions++;rawTransmissions++;' in stub
stub = stub.replace('if(sendSupported)transmissions++;',
                    'if(sendSupported){transmissions++;acceptModeledBurst();}')
stub = stub.replace('transmissions++;rawTransmissions++;',
                    'transmissions++;rawTransmissions++;acceptModeledBurst();')
template = assignments['harness'].func.value.value
harness = template.split('int main(){', 1)[0]
harness = harness.replace('#include "legacy_logic_fixture.h"', '')
harness = harness.replace('FIRMWARE_PROCESS', function('process'))
server_events = source[source.index('class ServerEvents:'):source.index('class CommandEvents:')]

harness += r'''
struct BLEServer {};
struct BLEServerCallbacks {
 virtual void onConnect(BLEServer*) {}
 virtual void onDisconnect(BLEServer*) {}
};
bool connected=false,fanDisconnected=false,restartAdvertising=false;
uint32_t connectionGeneration=0;
int queueResets=0;
void* commands=reinterpret_cast<void*>(1);
void xQueueReset(void*) { queueResets++; }
SERVER_EVENTS

uint16_t wireSeq=1000;
void wire(uint8_t* b) {
 b[0]=1;b[1]=6;field16(b,2,++wireSeq);
 process(b,connectionGeneration);
 assert(commandError==0&&ack==wireSeq);
}
void wireAction(int command,int value=0,int arg=0) {
 uint8_t b[20]={};b[4]=command;b[5]=value;field16(b,6,arg);wire(b);
}
void wireProfile(const BreezeLogic::Profile& p) {
 uint8_t b[20]={};b[4]=13;field16(b,6,p.averageSeconds);field16(b,8,p.riseSeconds);field16(b,10,p.fallSeconds);
 field16(b,12,p.steadinessCenti);b[15]=3;wire(b);
 memset(b,0,sizeof(b));b[4]=14;field16(b,6,p.offRoomCentiF);field16(b,8,p.offBodyCentiF);
 field16(b,10,p.fullRoomCentiF);field16(b,12,p.fullBodyCentiF);field16(b,14,p.bodyWeightCenti);b[16]=3;wire(b);
 memset(b,0,sizeof(b));const auto& t=p.boost;b[4]=15;b[6]=t.enabled;b[7]=t.extra;
 field16(b,8,t.triggerCentiF);field16(b,10,t.windowSeconds);field16(b,12,t.riseSeconds);
 field16(b,14,t.holdSeconds);field16(b,16,t.returnSeconds);b[18]=t.curve;b[19]=3;wire(b);
 wireAction(16);
}
void learnAll(decode_type_t protocol) {
 saved.clear();clockMs=100;pending=scheduledCapture=false;loopbackMode=0;fan=FanRemote();fan.begin();
 for(int slot=0;slot<FanRemote::SLOT_COUNT;slot++) {
  assert(action(fan,1,slot,38)==0);nextCapture={};nextCapture.decode_type=protocol;
  nextCapture.bits=protocol==UNKNOWN?0:32;nextCapture.value=0x20DF0000ULL+slot;
  pending=true;tick(fan);assert(status(fan)[7]==FanRemote::SAVED);
 }
}
void expectSend(int slot,bool checkDelayed=false) {
 const int before=transmissions;const int counter=read16(status(fan),16);
 const auto previous=status(fan);const auto previousLogic=logicState(fan);
 wireAction(4,slot);
 assert(transmissions==before+1&&read16(status(fan),16)==counter+1);
 assert(status(fan)[15]==slot&&(status(fan)[1]&2)==(slot==4?(previous[1]&2):0));
 if(slot==4){
  assert(status(fan)[4]==previous[4]&&status(fan)[5]==previous[5]);
  assert((logicState(fan)[1]&1)==(previousLogic[1]&1)&&logicState(fan)[3]==previousLogic[3]);
 }
 if(checkDelayed) {
  const auto setting=status(fan)[4];const auto recorded=fan.recordedSetting();
  tick(fan,299);assert(transmissions==before+1&&status(fan)[4]==setting);
  tick(fan,1);assert(transmissions==before+1&&read16(status(fan),16)==counter+1);
  assert(status(fan)[4]==setting&&fan.recordedSetting()==recorded);
 }
}
void idleBurstsStayAligned(decode_type_t protocol) {
 learnAll(protocol);wireAction(6,1,1);wireAction(7,0);
 modelAcceptsBursts=true;modeledSetting=1;modeledSlot=1;
 const int before=transmissions;
 for(int expected=2;expected<=3;expected++) {
  tick(fan,60001);expectSend(1,true);
  assert(status(fan)[4]==expected&&modeledSetting==expected);
 }
 modeledSlot=2;
 // Separate intentional presses are immediately deliverable, with no cooldown.
 expectSend(2);assert(status(fan)[4]==2&&modeledSetting==2);
 expectSend(2,true);
 assert(transmissions==before+4&&status(fan)[4]==1&&modeledSetting==1&&fan.recordedSetting()==1);
 modelAcceptsBursts=false;
}
void horizontalPreservesLogic() {
 learnAll(NEC);
 BreezeLogic::Profile profile;profile.averageSeconds=1;profile.riseSeconds=9;profile.fallSeconds=9;
 profile.steadinessCenti=0;profile.boost.enabled=0;wireProfile(profile);wireAction(6,1,1);
 runtimeTick(fan,1,98,75);assert((logicState(fan)[1]&1)&&logicState(fan)[3]==10);
 expectSend(4);int before=transmissions;
 runtimeTick(fan,999,98,75);assert(transmissions==before&&status(fan)[4]==1);
 runtimeTick(fan,1,98,75);
 assert(transmissions==before+1&&status(fan)[15]==1&&status(fan)[4]==2&&(logicState(fan)[1]&1));
 // Horizontal while paused cannot enable Logic or schedule another speed change.
 wireAction(7,0);expectSend(4);before=transmissions;runtimeTick(fan,5000,98,75);
 assert(transmissions==before&&status(fan)[4]==2&&!(logicState(fan)[1]&1));
 // A separately requested target also survives a Horizontal press and keeps moving.
 wireAction(5,4);expectSend(4);before=transmissions;runtimeTick(fan,1000,98,75);
 assert(transmissions==before+1&&status(fan)[4]==3&&status(fan)[5]==4&&!(logicState(fan)[1]&1));
 // Vertical keeps the established manual override: pause Logic and cancel its work.
 wireAction(7,1);runtimeTick(fan,1,98,75);expectSend(5);before=transmissions;
 runtimeTick(fan,5000,98,75);assert(transmissions==before&&status(fan)[4]==3&&!(logicState(fan)[1]&1));
}
int main() {
 ServerEvents events;BLEServerCallbacks& callbacks=events;
 callbacks.onConnect(nullptr);assert(connected&&connectionGeneration==1);
 horizontalPreservesLogic();
 idleBurstsStayAligned(NEC);idleBurstsStayAligned(UNKNOWN);
 int scenarios=0;
 for(auto protocol:{NEC,RC5,RC6,UNKNOWN}) {
  learnAll(protocol);
  BreezeLogic::Profile profile;
  for(int slot=0;slot<FanRemote::SLOT_COUNT;slot++) {
   for(int elapsed:{0,1,249,450,60000,120000}) {
    // Switching pages sends no firmware event. Time/telemetry must not suppress
    // the next deliberately requested command, including within the old cooldown.
    wireAction(6,5,1);expectSend(slot);
    const int before=transmissions;tick(fan,elapsed);assert(transmissions==before);
    expectSend(slot,elapsed>60000);scenarios++;
   }
   // Run the actual BLE disconnect/connect callbacks. Match the real loop's
   // deferred fan.disconnected() before it processes a new queued command.
   wireAction(6,5,1);expectSend(slot);
   const uint16_t reused=wireSeq;const uint32_t generation=connectionGeneration;
   const int before=transmissions;
   callbacks.onDisconnect(nullptr);assert(!connected&&fanDisconnected);
   callbacks.onConnect(nullptr);assert(connected&&connectionGeneration==generation+1);
   if(fanDisconnected){fanDisconnected=false;fan.disconnected();}
   assert(transmissions==before);wireSeq=reused-1;expectSend(slot);scenarios++;
   // Replaying that exact ACK retry is suppressed; the next deliberate tap sends.
   uint8_t retry[20]={1,6};field16(retry,2,wireSeq);retry[4]=4;retry[5]=slot;
   process(retry,connectionGeneration);assert(commandError==0&&transmissions==before+1);
   expectSend(slot);
   // Both a changed and an identical profile can finish immediately before a
   // manual press. An automatic tick in between must also leave the first press deliverable.
   for(int changed=0;changed<2;changed++) {
    wireAction(6,5,1);if(changed)profile.averageSeconds=profile.averageSeconds==30?31:30;
    wireProfile(profile);const int committed=transmissions;
    runtimeTick(fan,1000,98,71);assert(transmissions==committed);
    expectSend(slot);scenarios++;
   }
  }
  // Walk both complete running cycles using the actual controller estimate.
  wireAction(6,1,1);
  for(int step=1;step<=20;step++) {
   expectSend(1);int expected=step%10+1;
   assert(status(fan)[4]==expected&&(status(fan)[1]&4)&&fan.recordedSetting()==expected);
  }
  for(int step=1;step<=20;step++) {
   expectSend(2);int expected=(10-step%10)%10+1;
   assert(status(fan)[4]==expected&&(status(fan)[1]&4)&&fan.recordedSetting()==expected);
  }
  // H remains a known level through power toggles and estimate persistence.
  wireAction(6,10,1);assert(fan.recordedSetting()==10);
  assert(action(fan,6,11,1)==FanRemote::BAD_VALUE&&action(fan,5,11)==FanRemote::BAD_VALUE);
  expectSend(0);assert(status(fan)[4]==0&&fan.recordedSetting()==0);
  auto learned=learnedRecords();fan=FanRemote();fan.begin();
  assert(status(fan)[4]==0&&fan.recordedSetting()==0);
  expectSend(0,clockMs>60000);assert(status(fan)[4]==10&&fan.recordedSetting()==10);
  fan=FanRemote();fan.begin();assert(status(fan)[4]==10&&fan.recordedSetting()==10&&learnedRecords()==learned);
  wireAction(7,0);
  // Idle commands wrap once and never schedule an unrequested second burst.
  tick(fan,60001);expectSend(1,true);assert(status(fan)[4]==1);
  tick(fan,60000);expectSend(2);assert(status(fan)[4]==10);
  tick(fan,60001);expectSend(1,true);assert(status(fan)[4]==1);
  tick(fan,60001);expectSend(2,true);assert(status(fan)[4]==10);
  wireAction(6,9,1);wireAction(5,10);tick(fan,1000);
  assert(status(fan)[4]==10&&status(fan)[5]==0&&fan.recordedSetting()==10);
  wireAction(5,1);for(int step=9;step>=1;step--){tick(fan,1000);assert(status(fan)[4]==step);}
  assert(status(fan)[5]==0&&learnedRecords()==learned);
  // The range always reaches H: 98 F in a 75 F room is past the Full point.
  profile.averageSeconds=1;profile.boost.extra=9;
  wireProfile(profile);wireAction(6,9,1);runtimeTick(fan,1000,98,75);
  runtimeTick(fan,1112,98,75);assert(status(fan)[4]==10&&fan.recordedSetting()==10);
  fan=FanRemote();fan.begin();assert(status(fan)[4]==10&&fan.recordedSetting()==10);
  runtimeTick(fan,1000,98,75);assert(logicState(fan)[3]==10&&learnedRecords()==learned);
 }
 // A successful transmitter return is not physical fan feedback.
 wireAction(6,1,1);expectSend(1,clockMs>60000);expectSend(1);assert(status(fan)[4]==3);
 // Clock rollover and long idle periods must not add an unrequested send.
 learnAll(NEC);wireAction(6,10,1);clockMs=UINT32_MAX-500;
 expectSend(1,true);assert(status(fan)[4]==1);
 tick(fan,60000);expectSend(2);assert(status(fan)[4]==10);
 tick(fan,60001);expectSend(1,true);assert(status(fan)[4]==1);
 printf("PASS: %d first-send cases across 12 slots and decoded/raw paths; Horizontal preserves active/paused Logic and targets, Vertical pauses, one burst after idle with no delayed repeat, two idle rises/two falls keep the acceptance model aligned, reconnect generations, duplicate ACKs, complete 1-9-H cycles, power/target/profile/recording/persistence at H and immutable learned codes (not physical fan proof)\n",scenarios);
}
'''.replace('SERVER_EVENTS', server_events)

with tempfile.TemporaryDirectory(prefix='breeze-first-send-test-') as directory:
    path = Path(directory)
    (path / 'fake.h').write_text(stub)
    for header in ('Arduino.h', 'Preferences.h', 'IRrecv.h', 'IRsend.h', 'IRutils.h', 'nvs.h'):
        (path / header).write_text('#include "fake.h"\n')
    (path / 'test.cpp').write_text(harness)
    (path / 'run.cmd').write_text(
        '@echo off\n'
        'call "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\n'
        'cl /nologo /EHsc /std:c++17 /I . /I "' + str(root / 'firmware/BreezeController') + '" test.cpp /Fe:test.exe\n'
        'if errorlevel 1 exit /b 1\n'
        'test.exe\n'
    )
    subprocess.run([str(path / 'run.cmd')], cwd=path, check=True)

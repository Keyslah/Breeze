"""Compile actual firmware functions with hardware spies, without a controller."""
from pathlib import Path
import subprocess
import tempfile
import re

root = Path(__file__).resolve().parents[1]
source = (root / 'firmware/BreezeController/BreezeController.ino').read_text(encoding='utf-8')
def function(name):
    start = source.index('void ' + name + '(')
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

harness = r'''
#include <cassert>
#include <cstdio>
#include <vector>
#include "CONTROL_HEADER"
uint32_t now=0,lastSample=0,lastRoomSample=0,lastLog=0,lastChange=0;
constexpr uint32_t BODY_SAMPLE_MS=30000,FAN_SAMPLE_MS=60000;
constexpr int PROBE_PIN=0;constexpr float DIVIDER_SUPPLY_MV=3300,PROBE_OFFSET_C=0;
Config cfg;bool active=false,automatic=false,storageOK=true,shtReady=true,fanSamplePending=false,motorTest=true;
float probe=34,room=22,rh=55;int speed=0,angle=60,commandError=0,errorCode=0;
int moves=0,detaches=0,bodyReads=0,fanReads=0;uint32_t epoch=1800000000;
uint32_t millis(){return now;}uint32_t nowEpoch(){return epoch;}
bool setSpeed(int s){speed=s;angle=cfg.angle[s];moves++;lastChange=now;return true;}
void detachServo(){detaches++;}
uint32_t analogReadMilliVolts(int){bodyReads++;return 1650;}
struct sensors_event_t{float temperature=22,relative_humidity=55;};
struct Sensor{bool begin(){return true;}bool getEvent(sensors_event_t*,sensors_event_t*){fanReads++;return true;}}sht;
struct Record{int event,speed;bool fan;};std::vector<Record> records;
void appendRecord(uint8_t event=0,bool freshRoom=true){records.push_back({event,speed,freshRoom});}
'''.replace('CONTROL_HEADER', (root / 'firmware/BreezeController/Control.h').as_posix())
harness = harness.replace('constexpr uint32_t BODY_SAMPLE_MS=30000,FAN_SAMPLE_MS=60000;', re.search(r'constexpr uint32_t BODY_SAMPLE_MS[^;]+;', source).group(0))
harness += '\n'.join(function(n) for n in ['stopSession', 'startSession', 'readFanSensor', 'readBodySensor', 'sampleSensors'])
harness += r'''
int main(){
 cfg.calibrated=1;
 // Start immediately selects the required speed, regardless of initial fan state.
 for(int initial=0;initial<4;initial++){
   active=false;automatic=false;speed=initial;angle=cfg.angle[initial];probe=34;moves=0;detaches=0;
   startSession();assert(active&&automatic&&speed==3&&moves==1);assert(records.back().event==64);
   moves=0;int heldAngle=angle;stopSession(true);
   assert(!active&&!automatic&&speed==3&&angle==heldAngle&&moves==0&&detaches==0);assert(records.back().event==128);
 }
 // Stop preserves all four fan settings, including Off, without touching PWM.
 for(int held=0;held<4;held++){
   active=automatic=true;speed=held;angle=cfg.angle[held];moves=detaches=0;
   stopSession(true);assert(speed==held&&angle==cfg.angle[held]&&moves==0&&detaches==0&&!active&&!automatic);
 }
 active=false;probe=28;speed=3;startSession();assert(speed==0&&automatic);stopSession(true);assert(speed==0);
 cfg.calibrated=0;moves=0;startSession();assert(active&&!automatic&&moves==0);stopSession(true);
 storageOK=false;startSession();assert(!active&&commandError==6);storageOK=true;epoch=0;startSession();assert(!active&&commandError==7);epoch=1800000000;
 // A full minute has two body samples and one SHT40 sample, no early reads.
 cfg.calibrated=1;probe=34;now=100;startSession();records.clear();bodyReads=fanReads=0;
 now=30099;sampleSensors(now);assert(bodyReads==0&&fanReads==0&&records.empty());
 now=30100;sampleSensors(now);assert(bodyReads==16&&fanReads==0&&records.size()==1&&!records.back().fan);
 now=60099;sampleSensors(now);assert(bodyReads==16&&fanReads==0);
 now=60100;sampleSensors(now);assert(bodyReads==32&&fanReads==1&&records.size()==2&&records.back().fan);
 stopSession(true);records.clear();moves=0;now=90100;sampleSensors(now);assert(bodyReads==48&&records.empty()&&moves==0);
 // Unsigned elapsed-time arithmetic still works over millis() rollover.
 lastSample=lastRoomSample=0xfffffff0u;bodyReads=fanReads=0;now=29984;sampleSensors(now);assert(bodyReads==16&&fanReads==0);
 now=59984;sampleSensors(now);assert(bodyReads==32&&fanReads==1);
 // Fan and body deadlines can differ slightly; do not lose the fan reading.
 active=true;automatic=false;lastRoomSample=0;lastSample=30001;fanSamplePending=false;records.clear();
 now=60000;sampleSensors(now);assert(records.empty()&&fanSamplePending);
 now=60001;sampleSensors(now);assert(records.size()==1&&records.back().fan&&!fanSamplePending);
 puts("PASS: immediate rule application; Stop preserves all fan settings/PWM; fault guards; 30s body/60s fan cadence; no recording or rules after Stop; timer rollover");
}
'''
with tempfile.TemporaryDirectory(prefix='breeze-session-test-') as directory:
    path = Path(directory)
    (path / 'session.cpp').write_text(harness, encoding='utf-8')
    build = path / 'run.cmd'
    build.write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 session.cpp /Fe:session.exe\nif errorlevel 1 exit /b 1\nsession.exe\n')
    subprocess.run([str(build)], cwd=path, check=True)

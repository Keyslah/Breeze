"""Compile actual firmware functions with hardware spies, without a controller."""
from pathlib import Path
import subprocess
import tempfile
import re

root = Path(__file__).resolve().parents[1]
source = (root / 'firmware/BreezeController/BreezeController.ino').read_text(encoding='utf-8')
def function(name):
    start = re.search(r'void ' + name + r'\([^;{}]*\)\s*\{', source).start()
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
#include "SENSOR_HEADER"
uint32_t now=0,lastSample=0,lastRoomSample=0,lastLog=0;
constexpr uint32_t BODY_SAMPLE_MS=30000,FAN_SAMPLE_MS=60000;
constexpr int PROBE_PIN=0;constexpr float DIVIDER_SUPPLY_MV=3300,SERIES_OHMS=10000,PROBE_OFFSET_C=0;
bool active=false,storageOK=true,shtReady=true,fanSamplePending=false;
float probe=34,room=22,rh=55;int commandError=0,errorCode=0;
int bodyReads=0,fanReads=0;uint32_t epoch=1800000000;int adcMv=1650;
uint32_t millis(){return now;}uint32_t nowEpoch(){return epoch;}
uint32_t analogReadMilliVolts(int){bodyReads++;return adcMv;}
void readFanSensor();void readBodySensor();
struct sensors_event_t{float temperature=22,relative_humidity=55;};
struct Sensor{bool begin(){return true;}bool getEvent(sensors_event_t*,sensors_event_t*){fanReads++;return true;}}sht;
struct Record{int event;bool fan;};std::vector<Record> records;
void appendRecord(uint8_t event=0,bool freshRoom=true){records.push_back({event,freshRoom});}
'''.replace('SENSOR_HEADER', (root / 'firmware/BreezeController/Sensors.h').as_posix())
harness = harness.replace('constexpr uint32_t BODY_SAMPLE_MS=30000,FAN_SAMPLE_MS=60000;', re.search(r'constexpr uint32_t BODY_SAMPLE_MS[^;]+;', source).group(0))
harness += '\n'.join(function(n) for n in ['stopSession', 'startSession', 'readFanSensor', 'readBodySensor', 'sampleSensors'])
harness += r'''
int main(){
 assert(fabs(thermistor(1650)-25)<.01);assert(!isfinite(thermistor(0)));assert(!isfinite(thermistor(3300)));
 startSession();assert(active&&records.back().event==64&&bodyReads==16&&fanReads==1);
 int count=records.size();startSession();assert(records.size()==count);stopSession();assert(!active&&records.back().event==128);count=records.size();stopSession();assert(records.size()==count);
 storageOK=false;startSession();assert(!active&&commandError==6);storageOK=true;epoch=0;startSession();assert(!active&&commandError==7);epoch=1800000000;
 // A full minute has two body samples and one SHT40 sample, no early reads.
 probe=34;now=100;startSession();records.clear();bodyReads=fanReads=0;
 now=30099;sampleSensors(now);assert(bodyReads==0&&fanReads==0&&records.empty());
 now=30100;sampleSensors(now);assert(bodyReads==16&&fanReads==0&&records.size()==1&&!records.back().fan);
 now=60099;sampleSensors(now);assert(bodyReads==16&&fanReads==0);
 now=60100;sampleSensors(now);assert(bodyReads==32&&fanReads==1&&records.size()==2&&records.back().fan);
 stopSession();records.clear();now=90100;sampleSensors(now);assert(bodyReads==48&&records.empty());
 // Unsigned elapsed-time arithmetic still works over millis() rollover.
 lastSample=lastRoomSample=0xfffffff0u;bodyReads=fanReads=0;now=29984;sampleSensors(now);assert(bodyReads==16&&fanReads==0);
 now=59984;sampleSensors(now);assert(bodyReads==32&&fanReads==1);
 // Fan and body deadlines can differ slightly; do not lose the fan reading.
 active=true;lastRoomSample=0;lastSample=30001;fanSamplePending=false;records.clear();
 now=60000;sampleSensors(now);assert(records.empty()&&fanSamplePending);
 now=60001;sampleSensors(now);assert(records.size()==1&&records.back().fan&&!fanSamplePending);
 adcMv=0;readBodySensor();assert(!isfinite(probe)&&errorCode==3);adcMv=1650;readBodySensor();assert(isfinite(probe)&&errorCode==0);
 puts("PASS: sensor conversion; recording start/stop; storage and clock guards; 30s body/60s fan cadence; live readings after Stop; rollover; missing probe recovery");
}
'''
with tempfile.TemporaryDirectory(prefix='breeze-session-test-') as directory:
    path = Path(directory)
    (path / 'session.cpp').write_text(harness, encoding='utf-8')
    build = path / 'run.cmd'
    build.write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 session.cpp /Fe:session.exe\nif errorlevel 1 exit /b 1\nsession.exe\n')
    subprocess.run([str(build)], cwd=path, check=True)

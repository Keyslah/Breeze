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
#include <string>
#include "SENSOR_HEADER"
uint32_t now=0,lastSample=0,lastRoomSample=0,lastLog=0,lastLogicSample=0;
constexpr uint32_t BODY_SAMPLE_MS=30000,FAN_SAMPLE_MS=60000;
constexpr int PROBE_PIN=0,SECOND_PROBE_PIN=1,THIRD_PROBE_PIN=2,INPUT=0;constexpr float DIVIDER_SUPPLY_MV=3300,SERIES_OHMS=10000,PROBE_OFFSET_C=0,SECOND_PROBE_OFFSET_C=0;
bool active=false,storageOK=true,shtReady=true,fanSamplePending=false;
float probe=34,secondProbe=NAN,thirdProbe=NAN,room=22,rh=55;uint8_t testStage=0;int commandError=0,errorCode=0;
int bodyReads=0,secondReads=0,thirdReads=0,fanReads=0;uint32_t epoch=1800000000;int adcMv=1650,thirdAdcMv=-1;
uint32_t millis(){return now;}uint32_t nowEpoch(){return epoch;}
void pinMode(int,int){}
uint32_t analogReadMilliVolts(int pin){if(pin==0)bodyReads++;else if(pin==1)secondReads++;else{thirdReads++;if(thirdAdcMv>=0)return thirdAdcMv;}return adcMv;}
struct SerialStub{
 int space=0,availabilityChecks=0;std::vector<std::string> writes;
 template<class... T> void printf(const char*,T...){assert(false&&"Sensor diagnostics must not use potentially blocking printf");}
 int availableForWrite(){availabilityChecks++;return space;}
 size_t write(const uint8_t* data,size_t length){assert(length>0&&length<=size_t(space));writes.emplace_back(reinterpret_cast<const char*>(data),length);space-=int(length);return length;}
}Serial;
void readFanSensor();void readBodySensor();
struct sensors_event_t{float temperature=22,relative_humidity=55;};
struct Sensor{bool begin(){return true;}bool getEvent(sensors_event_t*,sensors_event_t*){fanReads++;return true;}}sht;
struct Record{int event;bool fan;};std::vector<Record> records;
void appendRecord(uint8_t event=0,bool freshRoom=true){records.push_back({event,freshRoom});}
'''.replace('SENSOR_HEADER', (root / 'firmware/BreezeController/Sensors.h').as_posix())
harness = harness.replace('constexpr uint32_t BODY_SAMPLE_MS=30000,FAN_SAMPLE_MS=60000;', re.search(r'constexpr uint32_t BODY_SAMPLE_MS[^;]+;', source).group(0))
harness += '\n'.join(function(n) for n in ['stopSession', 'startSession', 'readFanSensor', 'readBodySensor', 'sampleSensors', 'serviceLogicSensors'])
harness += r'''
int main(){
 assert(fabs(thermistor(1650)-25)<.01);assert(!isfinite(thermistor(0)));assert(!isfinite(thermistor(3300)));
 startSession();assert(active&&records.back().event==64&&bodyReads==16&&secondReads==16&&thirdReads==16&&fanReads==1&&isfinite(thirdProbe));
 int count=records.size();startSession();assert(records.size()==count);stopSession();assert(!active&&records.back().event==128);count=records.size();stopSession();assert(records.size()==count);
 storageOK=false;startSession();assert(!active&&commandError==6);storageOK=true;epoch=0;startSession();assert(!active&&commandError==7);epoch=1800000000;
 // Every local sensor samples at ten seconds; nothing is recorded early.
 probe=34;now=100;startSession();records.clear();bodyReads=secondReads=thirdReads=fanReads=0;
 now=10099;sampleSensors(now);assert(bodyReads==0&&secondReads==0&&fanReads==0&&records.empty());
 now=10100;sampleSensors(now);assert(bodyReads==16&&secondReads==16&&thirdReads==16&&fanReads==1&&records.size()==1&&records.back().fan);
 now=20099;sampleSensors(now);assert(bodyReads==16&&secondReads==16&&fanReads==1);
 now=20100;sampleSensors(now);assert(bodyReads==32&&secondReads==32&&thirdReads==32&&fanReads==2&&records.size()==2&&records.back().fan);
 stopSession();records.clear();now=30100;sampleSensors(now);assert(bodyReads==48&&fanReads==3&&records.empty());
 // Unsigned elapsed-time arithmetic still works over millis() rollover.
 lastSample=lastRoomSample=0xfffffff0u;bodyReads=fanReads=0;now=9984;sampleSensors(now);assert(bodyReads==16&&fanReads==1);
 now=19984;sampleSensors(now);assert(bodyReads==32&&fanReads==2);
 // A user marker can shift the body deadline; retain the fresh SHT40 sample.
 active=true;lastRoomSample=0;lastSample=1;fanSamplePending=false;records.clear();
 now=10000;sampleSensors(now);assert(records.empty()&&fanSamplePending);
 now=10001;sampleSensors(now);assert(records.size()==1&&records.back().fan&&!fanSamplePending);
 adcMv=0;readBodySensor();assert(!isfinite(probe)&&errorCode==3);adcMv=1650;readBodySensor();assert(isfinite(probe)&&errorCode==0);
 adcMv=0;thirdAdcMv=1650;readBodySensor();assert(!isfinite(probe)&&!isfinite(secondProbe)&&isfinite(thirdProbe)&&errorCode==0&&bodySource(probe,secondProbe,thirdProbe)==3);
 thirdAdcMv=0;readBodySensor();assert(!isfinite(thirdProbe)&&errorCode==3);adcMv=1650;thirdAdcMv=-1;
 // Controller logic independently refreshes all three probes each second without extra log records.
 lastLogicSample=0;bodyReads=secondReads=thirdReads=0;int beforeLogicRecords=records.size();now=999;serviceLogicSensors(now);assert(bodyReads==0&&thirdReads==0);
 now=1000;serviceLogicSensors(now);assert(bodyReads==16&&secondReads==16&&thirdReads==16);now=1005;serviceLogicSensors(now);now=1999;serviceLogicSensors(now);assert(thirdReads==16);
 now=2000;serviceLogicSensors(now);assert(bodyReads==32&&secondReads==32&&thirdReads==32&&records.size()==beforeLogicRecords);
 // An undrained USB output buffer must never block or stop sensor refreshes.
 Serial=SerialStub{};bodyReads=secondReads=thirdReads=0;lastLogicSample=0;adcMv=1500;thirdAdcMv=1750;
 probe=secondProbe=thirdProbe=-999;errorCode=3;beforeLogicRecords=records.size();
 for(now=1000;now<=20000;now+=1000)serviceLogicSensors(now);
 assert(bodyReads==320&&secondReads==320&&thirdReads==320&&lastLogicSample==20000);
 assert(fabs(probe-thermistor(1500))<.001&&fabs(secondProbe-thermistor(1500))<.001&&fabs(thirdProbe-thermistor(1750))<.001&&errorCode==0);
 assert(Serial.writes.empty()&&Serial.availabilityChecks==20&&records.size()==beforeLogicRecords);
 // Measure the actual formatted line, then exercise insufficient, exact-fit,
 // and subsequently full buffers without asserting a duplicate format string.
 Serial.space=4096;readBodySensor();assert(Serial.writes.size()==1);
 std::string message=Serial.writes.back();assert(message.find("[SENSORS] D0=")==0&&message.back()=='\n'&&message.find('\0')==std::string::npos);
 assert(message.find(" D1=")!=std::string::npos&&message.find(" D2=")!=std::string::npos);
 Serial.space=int(message.size())-1;int readCount=thirdReads;readBodySensor();assert(Serial.writes.size()==1&&thirdReads==readCount+16);
 Serial.space=int(message.size());readBodySensor();assert(Serial.writes.size()==2&&Serial.writes.back()==message&&Serial.space==0);
 readBodySensor();assert(Serial.writes.size()==2);
 // Fault status is also independent of logging capacity.
 adcMv=thirdAdcMv=0;readBodySensor();assert(!isfinite(probe)&&!isfinite(secondProbe)&&!isfinite(thirdProbe)&&errorCode==3&&Serial.writes.size()==2);
 adcMv=1650;thirdAdcMv=-1;readBodySensor();assert(isfinite(probe)&&isfinite(secondProbe)&&isfinite(thirdProbe)&&errorCode==0&&Serial.writes.size()==2);
 assert(warmestBody(32,33)==33);assert(warmestBody(NAN,25)==25);assert(warmestBody(24,30)==30);assert(!isfinite(warmestBody(NAN,NAN)));
 puts("PASS: sensor conversion; recording start/stop; 10s logging cadence; 1Hz D0/D1/D2 refresh; full/partial/exact-fit USB diagnostic buffers preserve measurements and fault updates; all-three source/validity; live readings after Stop; storage/clock/rollover guards");
}
'''
with tempfile.TemporaryDirectory(prefix='breeze-session-test-') as directory:
    path = Path(directory)
    (path / 'session.cpp').write_text(harness, encoding='utf-8')
    build = path / 'run.cmd'
    build.write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 session.cpp /Fe:session.exe\nif errorlevel 1 exit /b 1\nsession.exe\n')
    subprocess.run([str(build)], cwd=path, check=True)

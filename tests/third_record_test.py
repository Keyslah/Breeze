"""Compile actual firmware record writing with deterministic storage/BLE spies."""

from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "firmware/BreezeController/BreezeController.ino").read_text(encoding="utf-8")


def function(name):
    match = re.search(r"(?:void|uint8_t) " + name + r"\([^;{}]*\)\s*\{", SOURCE)
    if match is None:
        raise RuntimeError(f"Firmware function missing: {name}")
    opening = SOURCE.index("{", match.start())
    depth, end = 1, opening + 1
    while depth:
        depth += (SOURCE[end] == "{") - (SOURCE[end] == "}")
        end += 1
    return SOURCE[match.start():end]


HARNESS = r'''
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>
#include <array>
#include <algorithm>
#include "Sensors.h"
#define CHECK(condition) do { if (!(condition)) { \
  std::fprintf(stderr, "FAIL: %s:%d: %s\n", __func__, __LINE__, #condition); \
  std::abort(); } } while (0)
bool active=true,storageOK=true,connected=true,synced=true,streaming=false,fanSamplePending=false;
float probe=34,secondProbe=33,thirdProbe=35.67f,room=22,rh=55;
uint8_t errorCode=0,testStage=0,commandError=0;
uint32_t totalRecord=100,lastSample=0,lastRoomSample=0,lastLog=0,clockMs=1000,epoch=1800000000;
int epochCalls=0,bodyReads=0,roomReads=0;
bool advanceEpoch=false;
struct FanSpy { int8_t value=-1; int8_t recordedSetting() const {return value;} } fan;
uint32_t millis(){return clockMs;}
uint32_t nowEpoch(){epochCalls++;return advanceEpoch?epoch++:epoch;}
void readBodySensor(){bodyReads++;}
void readFanSensor(){roomReads++;}
std::vector<std::array<uint8_t,20>> notifications;
struct FileSpy {
  std::vector<uint8_t> bytes;
  std::vector<size_t> writes;
  size_t limit=std::numeric_limits<size_t>::max();
  int flushes=0;
  size_t write(const uint8_t* data,size_t size){writes.push_back(size);size_t accepted=std::min(size,limit);bytes.insert(bytes.end(),data,data+accepted);return accepted;}
  void flush(){flushes++;}
} logFile;
void notifyBytes(uint8_t* b){
  CHECK(storageOK&&logFile.flushes==logFile.writes.size());
  CHECK(logFile.bytes.size()==60*logFile.writes.size());
  std::array<uint8_t,20> copy;std::copy(b,b+20,copy.begin());notifications.push_back(copy);
}
struct FilesystemSpy {
  uint32_t total=100000,used=1000;
  uint32_t totalBytes(){return total;}
  uint32_t usedBytes(){return used;}
} LittleFS;
FIRMWARE_FUNCTIONS
uint32_t read32(const uint8_t* b){return uint32_t(b[0])|uint32_t(b[1])<<8|uint32_t(b[2])<<16|uint32_t(b[3])<<24;}
int16_t readSigned16(const uint8_t* b){return int16_t(uint16_t(b[0])|uint16_t(b[1])<<8);}
// CRC-8/ATM is checked by its independent polynomial-remainder form, rather
// than using the production crc8 helper to validate the production checksum.
uint8_t expectedCrc(const uint8_t* data){
  unsigned remainder=0;
  for(int i=0;i<19;i++){
    remainder^=unsigned(data[i])<<8;
    for(int bit=0;bit<8;bit++)remainder=(remainder&0x8000)?(remainder<<1)^0x10700:remainder<<1;
    remainder&=0xFFFF;
  }
  return uint8_t(remainder>>8);
}
void reset(){
  active=storageOK=connected=synced=true;streaming=fanSamplePending=false;
  probe=34;secondProbe=33;thirdProbe=35.67f;room=22;rh=55;
  errorCode=testStage=commandError=0;totalRecord=100;epoch=1800000000;epochCalls=bodyReads=roomReads=0;advanceEpoch=false;
  lastSample=lastRoomSample=lastLog=0;clockMs=1000;
  fan.value=-1;
  logFile=FileSpy{};LittleFS=FilesystemSpy{};notifications.clear();
}
void checkGroup(size_t offset,uint32_t parentIndex,bool validThird,int setting=-1){
  CHECK(logFile.bytes.size()>=offset+60);
  const uint8_t* parent=logFile.bytes.data()+offset;
  const uint8_t* supplement=parent+20;
  const uint8_t* fanRecord=parent+40;
  CHECK(parent[0]==6&&supplement[0]==10);
  CHECK(read32(parent+2)==parentIndex&&read32(supplement+2)==parentIndex+1);
  CHECK(read32(supplement+12)==parentIndex);
  CHECK(read32(parent+6)==read32(supplement+6));
  CHECK(supplement[1]==(validThird?8:0));
  CHECK(supplement[16]==0&&supplement[17]==0&&supplement[18]==0);
  CHECK(parent[19]==expectedCrc(parent)&&supplement[19]==expectedCrc(supplement));
  CHECK(fanRecord[0]==11&&read32(fanRecord+2)==parentIndex+2);
  CHECK(read32(fanRecord+12)==parentIndex);
  CHECK(read32(fanRecord+6)==read32(parent+6));
  CHECK(fanRecord[1]==(setting>=0?1:0));
  CHECK(fanRecord[10]==(setting>=0?setting:0)&&fanRecord[11]==0);
  CHECK(fanRecord[16]==0&&fanRecord[17]==0&&fanRecord[18]==0);
  CHECK(fanRecord[19]==expectedCrc(fanRecord));
}
void successful_groups(){
  reset();advanceEpoch=true;testStage=2;
  appendRecord();
  CHECK(totalRecord==103&&epochCalls==1&&logFile.flushes==1);
  CHECK(logFile.writes.size()==1&&logFile.writes[0]==60);
  checkGroup(0,100,true);
  CHECK(readSigned16(logFile.bytes.data()+30)==3567);
  CHECK((logFile.bytes[1]&4)==0); // D2 validity must not impersonate "D1 warmer".
  CHECK(logFile.bytes[18]==128);
  CHECK(notifications.size()==3&&notifications[0][0]==6&&notifications[1][0]==10&&notifications[2][0]==11);
  CHECK(std::equal(notifications[0].begin(),notifications[0].end(),logFile.bytes.begin()));
  CHECK(std::equal(notifications[1].begin(),notifications[1].end(),logFile.bytes.begin()+20));
  CHECK(std::equal(notifications[2].begin(),notifications[2].end(),logFile.bytes.begin()+40));

  secondProbe=35;thirdProbe=-5.25f;
  appendRecord(0,false);
  CHECK(totalRecord==106&&logFile.flushes==2&&epochCalls==2);
  checkGroup(60,103,true);
  CHECK((logFile.bytes[61]&4)==4); // Only D1 hotter than D0 sets this parent bit.
  CHECK((logFile.bytes[61]&(16|32))==0); // A user marker does not claim fresh room/RH.
  CHECK(readSigned16(logFile.bytes.data()+90)==-525);

  thirdProbe=NAN;
  appendRecord();
  CHECK(totalRecord==109);
  checkGroup(120,106,false);
  CHECK(readSigned16(logFile.bytes.data()+150)==0);
  CHECK(notifications.size()==9);

  reset();secondProbe=NAN;thirdProbe=35;
  appendRecord();checkGroup(0,100,true);
  CHECK((flags()&2)==2&&(logFile.bytes[1]&2)==0); // Live D2-valid is not parent D1-valid.
  reset();thirdProbe=81;
  appendRecord();checkGroup(0,100,false);
  CHECK(readSigned16(logFile.bytes.data()+30)==0); // Finite but out-of-range D2 is invalid.

  // Streaming, unsynchronized and disconnected states still store all records
  // without producing unsolicited BLE records.
  for(int mode=0;mode<3;mode++){
    reset();if(mode==0)streaming=true;else if(mode==1)synced=false;else connected=false;
    appendRecord();checkGroup(0,100,true);CHECK(totalRecord==103&&notifications.empty());
  }
}
void fan_setting_validity(){
  // Off is a known setting; it must not be confused with an unavailable estimate.
  for(int setting=0;setting<=10;setting++){
    reset();fan.value=setting;advanceEpoch=true;
    appendRecord();checkGroup(0,100,true,setting);
    CHECK(totalRecord==103&&epochCalls==1&&notifications.size()==3);
    CHECK(notifications[2][1]==1&&notifications[2][10]==setting);
  }
  for(int setting:{-128,-2,-1,11,127}){
    reset();fan.value=setting;
    appendRecord();checkGroup(0,100,true);
    CHECK(totalRecord==103&&notifications[2][1]==0&&notifications[2][10]==0);
  }
  reset();fan.value=10;thirdProbe=NAN;
  appendRecord();checkGroup(0,100,false,10); // Fan validity is independent of D2.
}
void session_markers(){
  reset();active=false;
  startSession();
  CHECK(active&&bodyReads==1&&roomReads==1&&totalRecord==103);
  CHECK(lastSample==clockMs&&lastRoomSample==clockMs&&lastLog==clockMs&&!fanSamplePending);
  checkGroup(0,100,true);
  CHECK((logFile.bytes[1]&65)==65); // Recording+start remain on the type6 parent.
  size_t bytes=logFile.bytes.size();startSession();CHECK(logFile.bytes.size()==bytes);
  testStage=3;stopSession();
  CHECK(!active&&testStage==0&&totalRecord==106);
  checkGroup(60,103,true);
  CHECK((logFile.bytes[61]&128)==128&&(logFile.bytes[61]&1)==0);
  CHECK(logFile.bytes[78]==192); // The final record retains its marker before reset.
  bytes=logFile.bytes.size();stopSession();CHECK(logFile.bytes.size()==bytes);
}
void failed_writes_and_storage_guard(){
  for(size_t written : {size_t(0),size_t(1),size_t(19),size_t(20),size_t(21),size_t(39),size_t(40),size_t(41),size_t(59)}){
    reset();logFile.limit=written;
    appendRecord();
    CHECK(!storageOK&&!active&&errorCode==6);
    CHECK(totalRecord==100+written/20);
    CHECK(logFile.bytes.size()==written&&notifications.empty());
    CHECK(logFile.writes.size()==1&&logFile.writes[0]==60);
    appendRecord();CHECK(logFile.writes.size()==1); // A failed archive cannot silently resume.
  }
  reset();LittleFS.used=LittleFS.total-8191;appendRecord();
  CHECK(!storageOK&&!active&&errorCode==6&&totalRecord==100);
  CHECK(logFile.writes.empty()&&notifications.empty());
  reset();LittleFS.used=LittleFS.total-8192;appendRecord();
  CHECK(storageOK&&active&&totalRecord==103);checkGroup(0,100,true);
  reset();storageOK=false;appendRecord();
  CHECK(logFile.writes.empty()&&totalRecord==100&&notifications.empty());
}
int main(){
  successful_groups();
  fan_setting_validity();
  session_markers();
  failed_writes_and_storage_guard();
  std::puts("PASS: actual type6/type10/type11 grouped writer; signed D2 and known Off/1-9/H/unknown fan setting, parent association/timestamps/CRC, D1 flag isolation, session markers, 60-byte flush before BLE, partial write accounting and storage guard");
}
'''


def main():
    harness = HARNESS.replace(
        "FIRMWARE_FUNCTIONS",
        "\n".join(function(name) for name in ("put16", "put32", "crc8", "flags", "appendRecord", "stopSession", "startSession")),
    )
    setup = Path(r"C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat")
    if not setup.exists():
        raise SystemExit(f"Visual Studio compiler setup not found: {setup}")
    with tempfile.TemporaryDirectory(prefix="breeze-third-record-test-") as directory:
        path = Path(directory)
        (path / "test.cpp").write_text(harness, encoding="utf-8")
        (path / "run.cmd").write_text(
            '@echo off\n'
            f'call "{setup}" -arch=x64 -host_arch=x64 >nul\n'
            f'cl /nologo /EHsc /std:c++17 /I "{ROOT / "firmware/BreezeController"}" test.cpp /Fe:test.exe\n'
            'if errorlevel 1 exit /b 1\n'
            'test.exe\n',
            encoding="utf-8",
        )
        subprocess.run([str(path / "run.cmd")], cwd=path, check=True)


if __name__ == "__main__":
    main()

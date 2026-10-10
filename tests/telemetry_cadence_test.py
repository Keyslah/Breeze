"""Exercise actual firmware BLE scheduling and FanRemote with hardware-only spies.

Measures host-side packets and scheduling, not radio delivery or physical IR.
No controller, serial port, flash backup, or Arduino build is used.
"""
import ast
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "firmware/BreezeController/BreezeController.ino").read_text(encoding="utf-8")


def function(name):
    match = re.search(r"(?:void|uint8_t|uint16_t|uint32_t) " + name + r"\([^;{}]*\)\s*\{", SOURCE)
    if match is None:
        raise RuntimeError(f"Firmware function missing: {name}")
    opening = SOURCE.index("{", match.start())
    depth, end = 1, opening + 1
    while depth:
        depth += (SOURCE[end] == "{") - (SOURCE[end] == "}")
        end += 1
    return SOURCE[match.start():end]


assignments = {
    node.targets[0].id: node.value
    for node in ast.parse((ROOT / "tests/ir_test.py").read_text(encoding="utf-8")).body
    if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
}
STUB = ast.literal_eval(assignments["stub"])
SERVER_EVENTS = SOURCE[SOURCE.index("class ServerEvents:"):SOURCE.index("class CommandEvents:")]


HARNESS = r'''
#include "FanRemote.cpp"
#include "TelemetryCadence.h"
#include <array>
#include <algorithm>
FanRemote fan;Preferences prefs;BreezeTelemetryCadence telemetryCadence;
bool active=false,mounted=false,storageOK=true,streaming=false,synced=false;
bool connected=false,fanDisconnected=false,restartAdvertising=false;
uint32_t connectionGeneration=0;
uint8_t commandError=0,errorCode=0,testStage=0;uint16_t ack=0,sequence=0;
uint32_t streamRecord=0,totalRecord=0,baseRecord=0,archiveId=0,lastSample=0;
uint64_t epochBase=0,clockAt=0;
float probe=34,secondProbe=33,thirdProbe=35,room=23,rh=55;
constexpr uint8_t FIRMWARE_VERSION_MAJOR=1,FIRMWARE_VERSION_MINOR=2,FIRMWARE_VERSION_PATCH=10,CAPABILITY_OTA=1;
struct Notification {uint32_t at;std::array<uint8_t,20> bytes;};
std::vector<Notification> notifications;
struct BLECharacteristic {
 std::array<uint8_t,20> value{};
 void setValue(uint8_t* data,size_t length){assert(length==20);std::copy(data,data+20,value.begin());}
 void notify(){notifications.push_back({clockMs,value});}
} characteristic;
BLECharacteristic* tx=&characteristic;
uint32_t esp_random(){return 1;}uint64_t esp_timer_get_time(){return uint64_t(clockMs)*1000;}
uint32_t nowEpoch(){return 1800000000;}
void metadata(){}void retire(uint32_t){}void openStorage(){}
void stopSession(){active=false;}void startSession(){active=true;}void readBodySensor(){}void appendRecord(uint8_t=0,bool=true){}
struct FilesystemFake{bool format(){return true;}}LittleFS;
struct OtaFake {
 bool active(){return false;}bool pendingVerification(){return false;}
 uint8_t enter(void(*)(uint8_t*)){return 0;}uint8_t cancel(){return 0;}uint8_t confirmRunning(){return 0;}
}ota;
struct BLEServer {};
struct BLEServerCallbacks {
 virtual void onConnect(BLEServer*) {}
 virtual void onDisconnect(BLEServer*) {}
};
int queueResets=0;void* commands=reinterpret_cast<void*>(1);
void xQueueReset(void*){queueResets++;}
FIRMWARE_FUNCTIONS
SERVER_EVENTS
ServerEvents serverEvents;BLEServerCallbacks& callbacks=serverEvents;
uint16_t nextCommand=200;
void direct(int action,int value=0,int argument=0){
 uint8_t b[20]={1,6};b[4]=action;b[5]=value;put16(b+6,argument);assert(fan.command(b)==0);
}
void command(int action,int value=0,int argument=0){
 uint8_t b[20]={1,6};put16(b+2,++nextCommand);b[4]=action;b[5]=value;put16(b+6,argument);
 process(b,connectionGeneration);assert(ack==nextCommand&&commandError==0);
}
void update(uint32_t now){
 clockMs=now;fan.tick(probe,secondProbe,room,clockMs,thirdProbe);serviceTelemetry(clockMs);
}
void reset(uint32_t now=1000){
 clockMs=now;saved.clear();pending=scheduledCapture=false;loopbackMode=0;captureDelay=20;
 failStorage=false;sendSupported=true;receiverEnabled=false;
 fan=FanRemote();fan.begin();direct(FanRemote::AUTO,0);
 telemetryCadence=BreezeTelemetryCadence{};
 probe=34;secondProbe=33;thirdProbe=35;room=23;rh=55;
 active=false;storageOK=true;errorCode=commandError=testStage=0;ack=sequence=0;
 connected=false;fanDisconnected=restartAdvertising=false;connectionGeneration=0;
 callbacks.onConnect(nullptr);notifications.clear();update(now);assert(notifications.size()==6);
 notifications.clear();
}
const Notification& lastType(int type){
 auto found=std::find_if(notifications.rbegin(),notifications.rend(),[&](const Notification& n){return n.bytes[0]==type;});
 assert(found!=notifications.rend());return *found;
}
void fullBundle(uint16_t expectedAck){
 const int types[]={1,5,9,4,7,8};assert(notifications.size()==6);
 for(int i=0;i<6;i++)assert(notifications[i].bytes[0]==types[i]);
 const auto& first=notifications[0].bytes;assert((first[16]|uint16_t(first[17])<<8)==expectedAck);
 assert(first[19]==0&&notifications[3].bytes[8]&64&&notifications[3].bytes[11]==10);
}
void learn(int slot){
 direct(FanRemote::LEARN,slot,38);nextCapture={};nextCapture.value+=slot;pending=true;
 clockMs+=100;fan.tick(probe,secondProbe,room,clockMs,thirdProbe);
 uint8_t b[20];fan.statusPacket(b,secondProbe,testStage);assert(b[7]==FanRemote::SAVED);
}
void prepare(){
 reset();learn(0);learn(1);learn(2);direct(FanRemote::SYNC,3,1);
 telemetry();notifications.clear();
}
void idle_budget_and_source_churn(){
 reset();
 for(uint32_t now=1005;now<=31000;now+=5){
  // Temperatures, average, chosen probe and D1/D2 validity can change locally.
  probe=34+float(now%1000)/1000;secondProbe=(now/1000)%2?NAN:36;
  thirdProbe=(now/1000)%3?35:NAN;room=22+float(now%1000)/1000;
  update(now);
 }
 assert(notifications.size()==18);
 const int types[]={1,5,9,4,7,8};
 for(size_t i=0;i<notifications.size();i++){
  assert(notifications[i].at==11000+uint32_t(i/6)*10000);
  assert(notifications[i].bytes[0]==types[i%6]);
 }
}
void immediate_commands_and_retries(){
 prepare();update(10000);notifications.clear();int before=transmissions;command(FanRemote::SEND,1);
 fullBundle(nextCommand);assert(notifications[1].bytes[4]==4&&transmissions==before+1);
 uint8_t retry[20]={1,6};put16(retry+2,nextCommand);retry[4]=FanRemote::SEND;retry[5]=1;
 notifications.clear();process(retry,connectionGeneration);fullBundle(nextCommand);assert(transmissions==before+1);
 notifications.clear();update(10100);assert(notifications.empty());
 // The command reset the old periodic deadline; only its new deadline is due.
 for(uint32_t now=10200;now<20000;now+=100)update(now);
 assert(notifications.empty());update(20000);fullBundle(nextCommand);
 notifications.clear();clockMs=20001;command(FanRemote::SEND,0);
 fullBundle(nextCommand);assert(notifications[1].bytes[4]==0);
 notifications.clear();clockMs=20002;command(FanRemote::SEND,0);
 fullBundle(nextCommand);assert(notifications[1].bytes[4]==4);
}
void autonomous_steps_and_learning(){
 prepare();uint32_t start=clockMs;command(FanRemote::TARGET,6);notifications.clear();
 update(start+100);assert(lastType(5).bytes[4]==4);
 update(start+1100);assert(lastType(5).bytes[4]==5);
 update(start+2100);assert(lastType(5).bytes[4]==6&&lastType(5).bytes[5]==0);
 assert(lastType(9).bytes[3]==0);
 for(const auto& n:notifications)assert(n.bytes[0]==5||n.bytes[0]==9);
 notifications.clear();command(FanRemote::LEARN,3,38);notifications.clear();
 nextCapture={};nextCapture.value=999;pending=true;update(clockMs+300);
 assert(lastType(5).bytes[7]==FanRemote::SAVED&&lastType(5).bytes[14]==3);
 notifications.clear();command(FanRemote::LEARN,4,38);start=clockMs;notifications.clear();
 update(start+29999);assert(lastType(5).bytes[7]==FanRemote::WAITING);notifications.clear();
 update(start+30099);assert(lastType(5).bytes[7]==FanRemote::EXPIRED);
 for(const auto& n:notifications)assert(n.bytes[0]!=1);
}
void diagnostics_coalesce_and_finish(){
 reset();loopbackMode=1;command(FanRemote::RECEIVER_TEST);notifications.clear();
 for(uint32_t now=1005;now<=8500;now+=5)update(now);
 uint32_t lastTest=0,lastInput=0;int tests=0,inputs=0;
 for(const auto& n:notifications){
  assert(n.bytes[0]!=1&&n.bytes[0]!=4);
  if(n.bytes[0]==7){
   if(lastTest&&n.bytes[1]!=3&&n.bytes[1]!=4)assert(uint32_t(n.at-lastTest)>=1000);
   lastTest=n.at;tests++;
  }
  if(n.bytes[0]==8){if(lastInput)assert(uint32_t(n.at-lastInput)>=1000);lastInput=n.at;inputs++;}
 }
 assert(tests<=8&&inputs==3);const auto& result=lastType(7);
 assert(result.bytes[1]==3&&result.bytes[3]==3&&result.at>=8250&&result.at<=8350);
 assert(lastType(8).bytes[1]&16);loopbackMode=0;
}
void reconnect_and_rollover(){
 reset();callbacks.onDisconnect(nullptr);assert(!connected&&fanDisconnected&&queueResets);
 notifications.clear();update(1100);assert(notifications.empty());
 callbacks.onConnect(nullptr);if(fanDisconnected){fanDisconnected=false;fan.disconnected();}
 update(1105);fullBundle(0);notifications.clear();
 // The subscribed app's CLOCK command also returns an immediate complete bundle.
 uint8_t clock[20]={1,4};put16(clock+2,++nextCommand);put32(clock+4,1800000000);
 process(clock,connectionGeneration);fullBundle(nextCommand);notifications.clear();
 update(1205);assert(notifications.empty());
 reset(0xfffffff0u);
 for(uint32_t elapsed=100;elapsed<10000;elapsed+=100)update(uint32_t(0xfffffff0u+elapsed));
 assert(notifications.empty());update(uint32_t(0xfffffff0u+10000));fullBundle(0);
 // Diagnostic limits also survive wraparound, including a rapid terminal result.
 BreezeTelemetryCadence cadence;uint8_t f[20]={5},l[20]={9},t[20]={7},i[20]={8};
 cadence.fullSent(0xffffff00u,1,f,l,t,i);t[1]=1;i[6]=1;
 assert(cadence.changed(uint32_t(0xffffff00u+999),f,l,t,i)==0);
 assert(cadence.changed(uint32_t(0xffffff00u+1000),f,l,t,i)==12);
 t[1]=3;assert(cadence.changed(uint32_t(0xffffff00u+1100),f,l,t,i)==4);
}
int main(){
 idle_budget_and_source_churn();immediate_commands_and_retries();autonomous_steps_and_learning();
 diagnostics_coalesce_and_finish();reconnect_and_rollover();
 puts("PASS: actual telemetry/process/FanRemote; idle six packets per10s despite temperatures/source churn; immediate ACK-first manual power/speed and duplicate retries; reset deadline; autonomous steps and learn outcomes; 1Hz diagnostics with prompt terminal result; reconnect/CLOCK bootstrap and rollover");
}
'''


def main():
    functions = "\n".join(function(name) for name in (
        "u16", "u32", "put16", "put32", "notifyBytes", "flags", "capabilities", "telemetry", "serviceTelemetry", "process",
    ))
    harness = HARNESS.replace("FIRMWARE_FUNCTIONS", functions).replace("SERVER_EVENTS", SERVER_EVENTS)
    setup = Path(r"C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat")
    if not setup.exists():
        raise SystemExit(f"Visual Studio compiler setup not found: {setup}")
    with tempfile.TemporaryDirectory(prefix="breeze-telemetry-test-") as directory:
        path = Path(directory)
        (path / "fake.h").write_text(STUB, encoding="utf-8")
        for header in ("Arduino.h", "Preferences.h", "IRrecv.h", "IRsend.h", "IRutils.h", "nvs.h"):
            (path / header).write_text('#include "fake.h"\n', encoding="utf-8")
        (path / "test.cpp").write_text(harness, encoding="utf-8")
        (path / "run.cmd").write_text(
            '@echo off\n'
            f'call "{setup}" -arch=x64 -host_arch=x64 >nul\n'
            f'cl /nologo /EHsc /std:c++17 /I . /I "{ROOT / "firmware/BreezeController"}" test.cpp /Fe:test.exe\n'
            'if errorlevel 1 exit /b 1\n'
            'test.exe\n',
            encoding="utf-8",
        )
        subprocess.run([str(path / "run.cmd")], cwd=path, check=True)


if __name__ == "__main__":
    main()

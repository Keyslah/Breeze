// XIAO ESP32C6 sensor monitor. Recording starts explicitly from the phone.
#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_SHT4x.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <esp_timer.h>
#include <esp_ota_ops.h>
#include "Sensors.h"
#include "Pins.h"
#include "FanRemote.h"
#include "OtaUpdate.h"
#include "TelemetryCadence.h"

// Confirm supply voltage/resistor with a meter during calibration.
constexpr float DIVIDER_SUPPLY_MV=3300, SERIES_OHMS=10000, PROBE_OFFSET_C=0;
constexpr float SECOND_PROBE_OFFSET_C=0; // Same divider/B3950 model; independently verify new MF55.
constexpr uint32_t BODY_SAMPLE_MS=10000, FAN_SAMPLE_MS=10000;
#define BREEZE_STRINGIFY_VALUE(value) #value
#define BREEZE_STRINGIFY(value) BREEZE_STRINGIFY_VALUE(value)
#ifndef BREEZE_VERSION_MAJOR
#define BREEZE_VERSION_MAJOR 1
#endif
#ifndef BREEZE_VERSION_MINOR
#define BREEZE_VERSION_MINOR 2
#endif
#ifndef BREEZE_VERSION_PATCH
#define BREEZE_VERSION_PATCH 2
#endif
constexpr uint8_t FIRMWARE_VERSION_MAJOR=BREEZE_VERSION_MAJOR,FIRMWARE_VERSION_MINOR=BREEZE_VERSION_MINOR,FIRMWARE_VERSION_PATCH=BREEZE_VERSION_PATCH;
constexpr uint8_t CAPABILITY_OTA=1;
constexpr uint32_t OTA_BUTTON_HOLD_MS=2000,OTA_CONFIRM_HEALTHY_MS=10000,OTA_CONFIRM_RETRY_MS=1000;
const char BREEZE_FIRMWARE_MARKER[]="BREEZE-FW:" BREEZE_STRINGIFY(BREEZE_VERSION_MAJOR) "." BREEZE_STRINGIFY(BREEZE_VERSION_MINOR) "." BREEZE_STRINGIFY(BREEZE_VERSION_PATCH);
const char* SERVICE="689a1001-744d-4aca-89b3-028bc1fe3201";
const char* TX_UUID="689a1002-744d-4aca-89b3-028bc1fe3201";
const char* RX_UUID="689a1003-744d-4aca-89b3-028bc1fe3201";
Preferences prefs;
Adafruit_SHT4x sht;
BLECharacteristic* tx;
QueueHandle_t commands;
struct Command { uint8_t b[20]; uint32_t connectionGeneration; };
volatile bool connected=false, restartAdvertising=false;
volatile uint32_t connectionGeneration=0;
bool shtReady=false, mounted=false, storageOK=false, active=false;
float probe=NAN,room=NAN,rh=NAN;
float secondProbe=NAN;
float thirdProbe=NAN;
uint8_t testStage=0; // User markers only: 1 baseline, 2 lying on probes, 3 off probes.
FanRemote fan;
OtaUpdate ota;
BreezeTelemetryCadence telemetryCadence;
volatile bool fanDisconnected=false;
uint8_t errorCode=0,commandError=0;
uint16_t ack=0,sequence=0;
uint32_t lastSample=0,lastRoomSample=0,lastLog=0,lastStream=0;
uint32_t lastLogicSample=0;
uint64_t epochBase=0,clockAt=0;
uint32_t baseRecord=0,totalRecord=0,streamRecord=0;
uint32_t archiveId=0;
uint32_t otaButtonDownAt=0,firmwareStartedAt=0,otaConfirmationLastAttemptAt=0;
bool streaming=false, synced=false;
bool fanSamplePending=false;
bool otaButtonHandled=false,otaConfirmationAttempted=false;
String logPath;
File logFile;

uint16_t u16(const uint8_t* b){return b[0]|uint16_t(b[1])<<8;}
uint32_t u32(const uint8_t* b){return u16(b)|uint32_t(u16(b+2))<<16;}
void put16(uint8_t* b,int v){b[0]=v;b[1]=v>>8;}
void put32(uint8_t* b,uint32_t v){put16(b,v);put16(b+2,v>>16);}
uint8_t crc8(const uint8_t* b,int len){uint8_t crc=0;for(int i=0;i<len;i++){crc^=b[i];for(int bit=0;bit<8;bit++)crc=(crc&128)?(crc<<1)^7:crc<<1;}return crc;}
uint32_t nowEpoch(){return epochBase?epochBase+(esp_timer_get_time()/1000000-clockAt):0;}
void notifyBytes(uint8_t* b){tx->setValue(b,20);if(connected)tx->notify();}
uint8_t flags(){return (active?1:0)|(validBody(thirdProbe)?2:0)|(isfinite(probe)?8:0)|(isfinite(room)?16:0)|(isfinite(rh)?32:0);}
void capabilities(){uint8_t b[20]={4};b[4]=7;b[5]=FIRMWARE_VERSION_MAJOR;b[6]=FIRMWARE_VERSION_MINOR;b[7]=FIRMWARE_VERSION_PATCH;b[8]=CAPABILITY_OTA|2|4|8|16|32|64;b[9]=ota.pendingVerification()?1:0;b[10]=2;b[11]=BreezeTelemetryCadence::FULL_PERIOD_MS/1000;notifyBytes(b);}
void telemetry(){
  // Confirm the button press before sending supplementary controller status.
  uint8_t b[20]={1};b[1]=flags()|(active&&storageOK?64:0)|(storageOK?128:0);put16(b+2,++sequence);put16(b+4,isfinite(probe)?lroundf(probe*100):0);put16(b+6,isfinite(room)?lroundf(room*100):0);put16(b+8,isfinite(rh)?lroundf(rh*100):0);put16(b+10,validBody(thirdProbe)?lroundf(thirdProbe*100):0);put32(b+12,millis()/1000);put16(b+16,ack);b[18]=errorCode;b[19]=commandError;notifyBytes(b);
  uint32_t now=millis();uint8_t fanState[20],logicState[20],receiverTest[20],receiverInput[20];
  fan.statusPacket(fanState,secondProbe,testStage);notifyBytes(fanState);fan.logicStatusPacket(logicState,now);notifyBytes(logicState);capabilities();fan.receiverTestPacket(receiverTest,now);notifyBytes(receiverTest);fan.receiverInputPacket(receiverInput);notifyBytes(receiverInput);
  telemetryCadence.fullSent(millis(),connectionGeneration,fanState,logicState,receiverTest,receiverInput);
}
void serviceTelemetry(uint32_t now){
  if(!connected)return;
  if(telemetryCadence.fullDue(now,connectionGeneration)){telemetry();return;}
  if(!telemetryCadence.pollDue(now))return;
  uint8_t fanState[20],logicState[20],receiverTest[20],receiverInput[20];
  fan.statusPacket(fanState,secondProbe,testStage);fan.logicStatusPacket(logicState,now);fan.receiverTestPacket(receiverTest,now);fan.receiverInputPacket(receiverInput);
  uint8_t changed=telemetryCadence.changed(now,fanState,logicState,receiverTest,receiverInput);
  if(changed&BreezeTelemetryCadence::FAN)notifyBytes(fanState);
  if(changed&BreezeTelemetryCadence::LOGIC)notifyBytes(logicState);
  if(changed&BreezeTelemetryCadence::RECEIVER_TEST)notifyBytes(receiverTest);
  if(changed&BreezeTelemetryCadence::RECEIVER_INPUT)notifyBytes(receiverInput);
}
void metadata(){uint8_t b[20]={3};b[1]=active;put32(b+2,baseRecord);put32(b+6,totalRecord);put32(b+10,mounted?LittleFS.totalBytes()-LittleFS.usedBytes():0);b[14]=storageOK;put32(b+15,archiveId);notifyBytes(b);}
String pathFor(uint32_t n){char p[32];snprintf(p,sizeof(p),"/log_%010lu.bin",(unsigned long)n);return String(p);}
void openStorage(){
  mounted=LittleFS.begin(false);storageOK=false;if(!mounted)return;
  baseRecord=0;bool found=false;File root=LittleFS.open("/");File f=root.openNextFile();
  while(f){String n=f.name();if(!n.startsWith("/"))n="/"+n;if(n.startsWith("/log_")&&n.endsWith(".bin")){uint32_t v=strtoul(n.substring(5,15).c_str(),nullptr,10);if(!found||v>baseRecord){baseRecord=v;found=true;}}f.close();f=root.openNextFile();}root.close();
  logPath=pathFor(baseRecord);logFile=LittleFS.open(logPath,FILE_APPEND);
  if(!logFile){errorCode=6;return;}totalRecord=baseRecord+logFile.size()/20;if(logFile.size()%20){errorCode=6;return;}storageOK=true;
  // A newer durable generation is proof Android acknowledged every older generation.
  root=LittleFS.open("/");f=root.openNextFile();while(f){String n=f.name();if(!n.startsWith("/"))n="/"+n;f.close();if(n.startsWith("/log_")&&n.endsWith(".bin")&&n!=logPath)LittleFS.remove(n);f=root.openNextFile();}root.close();
}
void appendRecord(uint8_t event=0,bool freshRoom=true){
  if(!storageOK)return;
  if(LittleFS.totalBytes()-LittleFS.usedBytes()<8192){storageOK=false;active=false;errorCode=6;return;}
  // Keep legacy D0/D1 records readable, then associate a contiguous D2 record.
  uint8_t pair[60]={};uint8_t* b=pair;uint8_t* third=pair+20;uint8_t* setting=pair+40;uint32_t parentIndex=totalRecord,epoch=nowEpoch();
  b[0]=6;b[1]=(flags()&~2& (freshRoom?255:~(16|32)))|event|(isfinite(secondProbe)?2:0)|(bodySource(probe,secondProbe)==2?4:0);put32(b+2,parentIndex);put32(b+6,epoch);put16(b+10,isfinite(probe)?lroundf(probe*100):0);put16(b+12,isfinite(room)?lroundf(room*100):0);put16(b+14,isfinite(rh)?lroundf(rh*100):0);put16(b+16,isfinite(secondProbe)?lroundf(secondProbe*100):0);b[18]=errorCode|(testStage<<6);b[19]=crc8(b,19);
  third[0]=10;third[1]=validBody(thirdProbe)?8:0;put32(third+2,parentIndex+1);put32(third+6,epoch);put16(third+10,validBody(thirdProbe)?lroundf(thirdProbe*100):0);put32(third+12,parentIndex);third[19]=crc8(third,19);
  int8_t currentSetting=fan.recordedSetting();bool knownSetting=currentSetting>=0&&currentSetting<=10;
  setting[0]=11;setting[1]=knownSetting?1:0;put32(setting+2,parentIndex+2);put32(setting+6,epoch);setting[10]=knownSetting?currentSetting:0;put32(setting+12,parentIndex);setting[19]=crc8(setting,19);
  size_t written=logFile.write(pair,sizeof(pair));logFile.flush();totalRecord+=written/20;
  if(written!=sizeof(pair)){storageOK=false;active=false;errorCode=6;return;}
  if(connected&&synced&&!streaming){notifyBytes(b);notifyBytes(third);notifyBytes(setting);}
}
void readFanSensor();
void readBodySensor();
void enterComputerOta(){
  if(ota.active())return;
  uint8_t fanState[20];fan.statusPacket(fanState,secondProbe,testStage);
  if(active||streaming||fanState[5]||fanState[6]!=FanRemote::NONE){Serial.println("[OTA] computer update refused while recording or fan work is active");return;}
  uint8_t result=ota.enter(notifyBytes);if(result)Serial.printf("[OTA] computer update mode failed: %u\n",result);
}
void serviceComputerOtaTrigger(uint32_t now){
  bool pressed=digitalRead(OTA_BUTTON_PIN)==LOW;
  if(!pressed){otaButtonDownAt=0;otaButtonHandled=false;}
  else if(!otaButtonDownAt)otaButtonDownAt=now?now:1;
  else if(!otaButtonHandled&&uint32_t(now-otaButtonDownAt)>=OTA_BUTTON_HOLD_MS){otaButtonHandled=true;enterComputerOta();}
  while(Serial.available()){char value=Serial.read();if(value=='u'||value=='U')enterComputerOta();else if(value=='t'||value=='T'){uint8_t packet[20]={1,6};packet[4]=FanRemote::RECEIVER_TEST;uint8_t result=fan.command(packet);Serial.printf("[IR CHECK] Start result=%u\n",result);}}
  if(!otaConfirmationAttempted&&ota.pendingVerification()&&uint32_t(now-firmwareStartedAt)>=OTA_CONFIRM_HEALTHY_MS&&uint32_t(now-otaConfirmationLastAttemptAt)>=OTA_CONFIRM_RETRY_MS){
    otaConfirmationLastAttemptAt=now;
    otaConfirmationAttempted=ota.confirmRunning()==0;
  }
}
void stopSession(){if(!active)return;active=false;appendRecord(128);testStage=0;}
void startSession(){
  if(active)return;if(!storageOK){commandError=6;return;}if(!nowEpoch()){commandError=7;return;}
  readFanSensor();readBodySensor();active=true;
  lastSample=lastRoomSample=lastLog=millis();fanSamplePending=false;appendRecord(64);
}
void retire(uint32_t cursor){
  if(!mounted||cursor!=totalRecord||cursor==baseRecord)return;
  // Create and flush next generation BEFORE deleting the acknowledged old file.
  String next=pathFor(cursor);File fresh=LittleFS.open(next,FILE_APPEND);if(!fresh)return;fresh.flush();fresh.close();
  logFile.close();String previous=logPath;logPath=next;baseRecord=cursor;logFile=LittleFS.open(logPath,FILE_APPEND);
  if(logFile){LittleFS.remove(previous);storageOK=true;if(errorCode==6)errorCode=0;}else{storageOK=false;errorCode=6;}
}
void process(const uint8_t* b,uint32_t generation=0){
  // A repeated relative speed or power command must never transmit twice.
  struct Cached {uint32_t generation=0;uint8_t packet[20]={},result=0;bool used=false;};
  static Cached recent[8];static uint8_t cursor=0;
  for(const auto& old:recent)if(old.used&&old.generation==generation&&u16(old.packet+2)==u16(b+2)){
    ack=u16(b+2);commandError=memcmp(old.packet,b,20)==0?old.result:1;telemetry();return;
  }
  ack=u16(b+2);commandError=0;if(b[0]!=1){commandError=1;return;}
  Serial.printf("[BLE] opcode=%u seq=%u action=%u\n",b[1],ack,b[4]);
  if(ota.active()&&b[1]!=7)commandError=22;
  else switch(b[1]){
    case 2:switch(b[4]){
      case 0:case 8:stopSession();break;
      case 1:case 7:if(!active)testStage=0;startSession();break;
      case 6:if(active||mounted){commandError=4;break;}if(LittleFS.format()){archiveId=esp_random();if(!archiveId)archiveId=1;prefs.putUInt("archive",archiveId);openStorage();}if(!storageOK)commandError=6;metadata();break;
      default:commandError=1;
    }break;
    case 3:streamRecord=u32(b+4);if(streamRecord<baseRecord)streamRecord=baseRecord;if(streamRecord>totalRecord)streamRecord=totalRecord;streaming=true;synced=false;metadata();break;
    case 4:if(!active){uint32_t epoch=u32(b+4);if(epoch<1700000000UL){commandError=1;break;}epochBase=epoch;clockAt=esp_timer_get_time()/1000000;}break;
    case 5:retire(u32(b+4));metadata();break;
    case 6:
      if(b[4]==10){ // Probe contact test: record raw data, IR auto OFF; no classification rules.
        if(active){commandError=4;break;}
        fan.cancelAutomatic(0);testStage=1;startSession();if(!active)testStage=0;
        Serial.println("[CONTACT TEST] Record warming, lie-on, lift-off and recovery. Raw probes retained.");
      }else if(b[4]==11){
        if(!active||!testStage||b[5]<1||b[5]>3){commandError=26;break;}
        testStage=b[5];readBodySensor();lastSample=millis();appendRecord(0,false);Serial.printf("[CONTACT TEST] User marker stage=%u time=%lu\n",testStage,(unsigned long)nowEpoch());
      }else commandError=fan.command(b);break;
    case 7:
      if(b[4]==1){
        uint8_t fanState[20];fan.statusPacket(fanState,secondProbe,testStage);
        if(active){commandError=4;break;}
        if(streaming||fanState[5]||fanState[6]!=FanRemote::NONE||fan.receiverTestActive()){commandError=22;break;}
        commandError=ota.enter(notifyBytes);
      }else if(b[4]==2)commandError=ota.cancel();
      else if(b[4]==3)commandError=ota.confirmRunning();
      else commandError=26;
      break;
    default:commandError=1;
  }
  Cached& saved=recent[cursor++%8];saved.generation=generation;memcpy(saved.packet,b,20);saved.result=commandError;saved.used=true;
  telemetry();
}
class ServerEvents:public BLEServerCallbacks {void onConnect(BLEServer*)override{connectionGeneration++;connected=true;synced=false;}void onDisconnect(BLEServer*)override{connected=false;fanDisconnected=true;if(commands)xQueueReset(commands);restartAdvertising=true;synced=false;streaming=false;}};
class CommandEvents:public BLECharacteristicCallbacks {void onWrite(BLECharacteristic* c)override{auto v=c->getValue();if(connected&&v.length()==20){Command cmd;cmd.connectionGeneration=connectionGeneration;memcpy(cmd.b,v.c_str(),20);xQueueSend(commands,&cmd,0);}}};
void readFanSensor(){
  room=NAN;rh=NAN;
  if(!shtReady)shtReady=sht.begin();
  if(shtReady){sensors_event_t h,t;if(sht.getEvent(&h,&t)){if(t.temperature>=-40&&t.temperature<=125)room=t.temperature;if(h.relative_humidity>=0&&h.relative_humidity<=100)rh=h.relative_humidity;}else shtReady=false;}
}
void readBodySensor(){
  uint32_t sum=0;for(int i=0;i<16;i++)sum+=analogReadMilliVolts(PROBE_PIN);probe=thermistor(sum/16.0f,DIVIDER_SUPPLY_MV,SERIES_OHMS)+PROBE_OFFSET_C;
  sum=0;for(int i=0;i<16;i++)sum+=analogReadMilliVolts(SECOND_PROBE_PIN);secondProbe=thermistor(sum/16.0f,DIVIDER_SUPPLY_MV,SERIES_OHMS)+SECOND_PROBE_OFFSET_C;
  pinMode(THIRD_PROBE_PIN,INPUT);sum=0;for(int i=0;i<16;i++)sum+=analogReadMilliVolts(THIRD_PROBE_PIN);thirdProbe=thermistor(sum/16.0f,DIVIDER_SUPPLY_MV,SERIES_OHMS);
  bool usable=isfinite(warmestBody(probe,secondProbe,thirdProbe));
  if(!usable){if(errorCode==0)errorCode=3;}else if(errorCode==3)errorCode=0;
  char message[160];
  int length=snprintf(message,sizeof(message),"[SENSORS] D0=%.2fC D1=%.2fC D2=%.2fC room=%.2fC RH=%.1f warmerProbe=%u%s\n",probe,secondProbe,thirdProbe,room,rh,unsigned(bodySource(probe,secondProbe,thirdProbe)),usable?"":" NO USABLE BODY PROBE");
  // A connected USB host may stop reading. Drop this diagnostic instead of waiting for TX space.
  if(length>0&&size_t(length)<sizeof(message)&&Serial.availableForWrite()>=length)Serial.write(reinterpret_cast<const uint8_t*>(message),size_t(length));
}
void sampleSensors(uint32_t now){
  bool fanDue=now-lastRoomSample>=FAN_SAMPLE_MS;
  if(fanDue){lastRoomSample=now;readFanSensor();fanSamplePending=true;}
  if(now-lastSample>=BODY_SAMPLE_MS){lastSample=now;readBodySensor();if(active){lastLog=now;appendRecord(0,fanSamplePending);}fanSamplePending=false;}
}
void serviceLogicSensors(uint32_t now){
  if(uint32_t(now-lastLogicSample)<1000)return;lastLogicSample=now;
  readBodySensor();
}
void setup(){
  pinMode(THIRD_PROBE_PIN,INPUT);analogSetPinAttenuation(THIRD_PROBE_PIN,ADC_11db);
  Serial.begin(115200);pinMode(OTA_BUTTON_PIN,INPUT_PULLUP);analogReadResolution(12);analogSetPinAttenuation(PROBE_PIN,ADC_11db);analogSetPinAttenuation(SECOND_PROBE_PIN,ADC_11db);
  prefs.begin("breeze",false);archiveId=prefs.getUInt("archive",0);if(!archiveId){archiveId=esp_random();if(!archiveId)archiveId=1;prefs.putUInt("archive",archiveId);}
  Wire.begin(ROOM_SDA_PIN,ROOM_SCL_PIN);Wire.setTimeOut(100);shtReady=sht.begin();if(shtReady){sht.setPrecision(SHT4X_HIGH_PRECISION);sht.setHeater(SHT4X_NO_HEATER);}openStorage();commands=xQueueCreate(12,sizeof(Command));fan.begin();
  BLEDevice::init("Breeze Sensors");BLEServer* server=BLEDevice::createServer();server->setCallbacks(new ServerEvents());BLEService* service=server->createService(SERVICE);tx=service->createCharacteristic(TX_UUID,BLECharacteristic::PROPERTY_NOTIFY|BLECharacteristic::PROPERTY_READ);tx->addDescriptor(new BLE2902());BLECharacteristic* rx=service->createCharacteristic(RX_UUID,BLECharacteristic::PROPERTY_WRITE);rx->setCallbacks(new CommandEvents());service->start();BLEAdvertising* advertising=BLEDevice::getAdvertising();advertising->addServiceUUID(SERVICE);advertising->setScanResponse(true);advertising->start();readFanSensor();readBodySensor();lastSample=lastRoomSample=millis();
  firmwareStartedAt=millis();Serial.printf("%s Breeze IR-v7 ready. Raw body D0/D1. SHT40 D4/D5. IR RX D6, TX D7. Hold BOOT 2s for computer OTA. Auto OFF.\n",BREEZE_FIRMWARE_MARKER);
}
void loop(){
  if(fanDisconnected){fanDisconnected=false;fan.disconnected();}
  if(restartAdvertising){restartAdvertising=false;BLEDevice::startAdvertising();}
  Command command;while(xQueueReceive(commands,&command,0)==pdTRUE)if(connected&&command.connectionGeneration==connectionGeneration)process(command.b,command.connectionGeneration);
  uint32_t now=millis();serviceComputerOtaTrigger(now);serviceLogicSensors(millis());fan.tick(probe,secondProbe,room,millis(),thirdProbe);sampleSensors(millis());
  serviceTelemetry(millis());
  if(streaming&&connected&&now-lastStream>=30){lastStream=now;if(streamRecord<totalRecord){File read=LittleFS.open(logPath,FILE_READ);uint8_t b[20];if(read&&streamRecord>=baseRecord&&read.seek((streamRecord-baseRecord)*20)&&read.read(b,20)==20){notifyBytes(b);streamRecord++;}else{streaming=false;commandError=6;}read.close();}else{streaming=false;synced=true;metadata();}}
  ota.tick();
  delay(5);
}

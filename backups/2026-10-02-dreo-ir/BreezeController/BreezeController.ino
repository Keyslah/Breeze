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
#include "Sensors.h"

constexpr uint8_t PROBE_PIN=D0;
// Confirm supply voltage/resistor with a meter during calibration.
constexpr float DIVIDER_SUPPLY_MV=3300, SERIES_OHMS=10000, PROBE_OFFSET_C=0;
constexpr uint32_t BODY_SAMPLE_MS=30000, FAN_SAMPLE_MS=60000;
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
uint8_t errorCode=0,commandError=0;
uint16_t ack=0,sequence=0;
uint32_t lastSample=0,lastRoomSample=0,lastLog=0,lastNotify=0,lastStream=0;
uint64_t epochBase=0,clockAt=0;
uint32_t baseRecord=0,totalRecord=0,streamRecord=0;
uint32_t archiveId=0;
bool streaming=false, synced=false;
bool fanSamplePending=false;
String logPath;
File logFile;

uint16_t u16(const uint8_t* b){return b[0]|uint16_t(b[1])<<8;}
uint32_t u32(const uint8_t* b){return u16(b)|uint32_t(u16(b+2))<<16;}
void put16(uint8_t* b,int v){b[0]=v;b[1]=v>>8;}
void put32(uint8_t* b,uint32_t v){put16(b,v);put16(b+2,v>>16);}
uint8_t crc8(const uint8_t* b,int len){uint8_t crc=0;for(int i=0;i<len;i++){crc^=b[i];for(int bit=0;bit<8;bit++)crc=(crc&128)?(crc<<1)^7:crc<<1;}return crc;}
uint32_t nowEpoch(){return epochBase?epochBase+(esp_timer_get_time()/1000000-clockAt):0;}
void notifyBytes(uint8_t* b){tx->setValue(b,20);if(connected)tx->notify();}
uint8_t flags(){return (active?1:0)|(isfinite(probe)?8:0)|(isfinite(room)?16:0)|(isfinite(rh)?32:0);}
void capabilities(){uint8_t b[20]={4};b[4]=6;notifyBytes(b);}
void telemetry(){capabilities();uint8_t b[20]={1};b[1]=flags()|(active&&storageOK?64:0)|(storageOK?128:0);put16(b+2,++sequence);put16(b+4,isfinite(probe)?lroundf(probe*100):0);put16(b+6,isfinite(room)?lroundf(room*100):0);put16(b+8,isfinite(rh)?lroundf(rh*100):0);put32(b+12,millis()/1000);put16(b+16,ack);b[18]=errorCode;b[19]=commandError;notifyBytes(b);}
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
  uint8_t b[20]={2};b[1]=(flags()& (freshRoom?255:~(16|32)))|event;put32(b+2,totalRecord);put32(b+6,nowEpoch());put16(b+10,isfinite(probe)?lroundf(probe*100):0);put16(b+12,isfinite(room)?lroundf(room*100):0);put16(b+14,isfinite(rh)?lroundf(rh*100):0);b[18]=errorCode;
  b[19]=crc8(b,19);if(logFile.write(b,20)!=20){storageOK=false;active=false;errorCode=6;return;}logFile.flush();totalRecord++;
  if(connected&&synced&&!streaming)notifyBytes(b);
}
void readFanSensor();
void readBodySensor();
void stopSession(){if(!active)return;active=false;appendRecord(128);}
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
  ack=u16(b+2);commandError=0;if(b[0]!=1){commandError=1;return;}
  switch(b[1]){
    case 2:switch(b[4]){
      case 0:case 8:stopSession();break;
      case 1:case 7:startSession();break;
      case 6:if(active||mounted){commandError=4;break;}if(LittleFS.format()){archiveId=esp_random();if(!archiveId)archiveId=1;prefs.putUInt("archive",archiveId);openStorage();}if(!storageOK)commandError=6;metadata();break;
      default:commandError=1;
    }break;
    case 3:streamRecord=u32(b+4);if(streamRecord<baseRecord)streamRecord=baseRecord;if(streamRecord>totalRecord)streamRecord=totalRecord;streaming=true;synced=false;metadata();break;
    case 4:if(!active){uint32_t epoch=u32(b+4);if(epoch<1700000000UL){commandError=1;break;}epochBase=epoch;clockAt=esp_timer_get_time()/1000000;}break;
    case 5:retire(u32(b+4));metadata();break;
    default:commandError=1;
  }
  telemetry();
}
class ServerEvents:public BLEServerCallbacks {void onConnect(BLEServer*)override{connectionGeneration++;connected=true;synced=false;}void onDisconnect(BLEServer*)override{connected=false;if(commands)xQueueReset(commands);restartAdvertising=true;synced=false;streaming=false;}};
class CommandEvents:public BLECharacteristicCallbacks {void onWrite(BLECharacteristic* c)override{auto v=c->getValue();if(connected&&v.length()==20){Command cmd;cmd.connectionGeneration=connectionGeneration;memcpy(cmd.b,v.c_str(),20);xQueueSend(commands,&cmd,0);}}};
void readFanSensor(){
  room=NAN;rh=NAN;
  if(!shtReady)shtReady=sht.begin();
  if(shtReady){sensors_event_t h,t;if(sht.getEvent(&h,&t)){if(t.temperature>=-40&&t.temperature<=125)room=t.temperature;if(h.relative_humidity>=0&&h.relative_humidity<=100)rh=h.relative_humidity;}else shtReady=false;}
}
void readBodySensor(){
  uint32_t sum=0;for(int i=0;i<16;i++)sum+=analogReadMilliVolts(PROBE_PIN);probe=thermistor(sum/16.0f,DIVIDER_SUPPLY_MV,SERIES_OHMS)+PROBE_OFFSET_C;
  if(!isfinite(probe)){if(errorCode==0)errorCode=3;}else if(errorCode==3)errorCode=0;
}
void sampleSensors(uint32_t now){
  bool fanDue=now-lastRoomSample>=FAN_SAMPLE_MS;
  if(fanDue){lastRoomSample=now;readFanSensor();fanSamplePending=true;}
  if(now-lastSample>=BODY_SAMPLE_MS){lastSample=now;readBodySensor();if(active){lastLog=now;appendRecord(0,fanSamplePending);}fanSamplePending=false;}
}
void setup(){
  Serial.begin(115200);analogReadResolution(12);analogSetPinAttenuation(PROBE_PIN,ADC_11db);
  prefs.begin("breeze",false);archiveId=prefs.getUInt("archive",0);if(!archiveId){archiveId=esp_random();if(!archiveId)archiveId=1;prefs.putUInt("archive",archiveId);}
  Wire.begin(D4,D5);Wire.setTimeOut(100);shtReady=sht.begin();if(shtReady){sht.setPrecision(SHT4X_HIGH_PRECISION);sht.setHeater(SHT4X_NO_HEATER);}openStorage();commands=xQueueCreate(12,sizeof(Command));
  BLEDevice::init("Breeze Sensors");BLEServer* server=BLEDevice::createServer();server->setCallbacks(new ServerEvents());BLEService* service=server->createService(SERVICE);tx=service->createCharacteristic(TX_UUID,BLECharacteristic::PROPERTY_NOTIFY|BLECharacteristic::PROPERTY_READ);tx->addDescriptor(new BLE2902());BLECharacteristic* rx=service->createCharacteristic(RX_UUID,BLECharacteristic::PROPERTY_WRITE);rx->setCallbacks(new CommandEvents());service->start();BLEAdvertising* advertising=BLEDevice::getAdvertising();advertising->addServiceUUID(SERVICE);advertising->setScanResponse(true);advertising->start();readFanSensor();readBodySensor();lastSample=lastRoomSample=millis();
  Serial.println("Breeze sensors-v6 ready. Body: D0. Fan temperature and humidity: SHT40 D4/D5.");
}
void loop(){
  if(restartAdvertising){restartAdvertising=false;BLEDevice::startAdvertising();}
  Command command;while(xQueueReceive(commands,&command,0)==pdTRUE)if(connected&&command.connectionGeneration==connectionGeneration)process(command.b,command.connectionGeneration);
  uint32_t now=millis();sampleSensors(now);
  if(connected&&now-lastNotify>=1000){lastNotify=now;telemetry();}
  if(streaming&&connected&&now-lastStream>=30){lastStream=now;if(streamRecord<totalRecord){File read=LittleFS.open(logPath,FILE_READ);uint8_t b[20];if(read&&streamRecord>=baseRecord&&read.seek((streamRecord-baseRecord)*20)&&read.read(b,20)==20){notifyBytes(b);streamRecord++;}else{streaming=false;commandError=6;}read.close();}else{streaming=false;synced=true;metadata();}}
  delay(5);
}

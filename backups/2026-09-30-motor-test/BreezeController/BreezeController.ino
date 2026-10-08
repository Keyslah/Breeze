// XIAO ESP32C6, Arduino-ESP32 3.3.7. No motion or auto-start at boot.
#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_SHT4x.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <esp_timer.h>
#include "Control.h"

constexpr uint8_t PROBE_PIN=D0, JOYSTICK_PIN=D1, BUTTON_PIN=D2, SERVO_PIN=D3;
// Confirm supply voltage/resistor with a meter during calibration.
constexpr float DIVIDER_SUPPLY_MV=3300, SERIES_OHMS=10000, PROBE_OFFSET_C=0;
constexpr uint32_t BODY_SAMPLE_MS=30000, FAN_SAMPLE_MS=60000;
constexpr uint16_t SERVO_MIN_US=1000, SERVO_MAX_US=2000;
const char* SERVICE="689a1001-744d-4aca-89b3-028bc1fe3201";
const char* TX_UUID="689a1002-744d-4aca-89b3-028bc1fe3201";
const char* RX_UUID="689a1003-744d-4aca-89b3-028bc1fe3201";
Config cfg;
Preferences prefs;
Adafruit_SHT4x sht;
BLECharacteristic* tx;
QueueHandle_t commands;
struct Command { uint8_t b[20]; };
volatile bool connected=false, restartAdvertising=false;
bool shtReady=false, mounted=false, storageOK=false, active=false, automatic=false, pwm=false;
float probe=NAN,room=NAN,rh=NAN;
int speed=0,angle=60;
uint8_t errorCode=0,commandError=0;
uint16_t ack=0,sequence=0;
uint32_t lastSample=0,lastRoomSample=0,lastLog=0,lastChange=0,lastNotify=0,lastStream=0;
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
uint8_t flags(){return (active?1:0)|(automatic?2:0)|(cfg.calibrated?4:0)|(isfinite(probe)?8:0)|(isfinite(room)?16:0)|(isfinite(rh)?32:0);}
void telemetry(){uint8_t b[20]={1};b[1]=flags()|(active&&storageOK?64:0)|(storageOK?128:0);put16(b+2,++sequence);put16(b+4,isfinite(probe)?lroundf(probe*100):0);put16(b+6,isfinite(room)?lroundf(room*100):0);put16(b+8,isfinite(rh)?lroundf(rh*100):0);b[10]=speed;b[11]=angle;put32(b+12,millis()/1000);put16(b+16,ack);b[18]=errorCode;b[19]=commandError;notifyBytes(b);}
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
void detachServo(){if(pwm){ledcDetach(SERVO_PIN);pwm=false;}pinMode(SERVO_PIN,OUTPUT);digitalWrite(SERVO_PIN,LOW);}
bool moveAngle(int a){
  if(a<0||a>180)return false;
  if(!pwm){pwm=ledcAttach(SERVO_PIN,50,14);if(!pwm){errorCode=5;return false;}}
  uint32_t us=SERVO_MIN_US+(SERVO_MAX_US-SERVO_MIN_US)*a/180;
  ledcWrite(SERVO_PIN,(uint32_t)((uint64_t)us*16383/20000));angle=a;return true;
}
bool setSpeed(int s){if(!cfg.calibrated){commandError=2;return false;}if(moveAngle(cfg.angle[s])){speed=s;lastChange=millis();return true;}return false;}
void appendRecord(uint8_t event=0,bool freshRoom=true){
  if(!storageOK)return;
  if(LittleFS.totalBytes()-LittleFS.usedBytes()<8192){storageOK=false;active=false;automatic=false;errorCode=6;detachServo();return;}
  uint8_t b[20]={2};b[1]=(flags()& (freshRoom?255:~(16|32)))|event;put32(b+2,totalRecord);put32(b+6,nowEpoch());put16(b+10,isfinite(probe)?lroundf(probe*100):0);put16(b+12,isfinite(room)?lroundf(room*100):0);put16(b+14,isfinite(rh)?lroundf(rh*100):0);b[16]=speed;b[17]=angle;b[18]=errorCode;
  b[19]=crc8(b,19);if(logFile.write(b,20)!=20){storageOK=false;active=false;automatic=false;errorCode=6;detachServo();return;}logFile.flush();totalRecord++;
  if(connected&&synced&&!streaming)notifyBytes(b);
}
void stopSession(bool preserveFan=false){if(!active)return;automatic=false;if(!preserveFan&&cfg.calibrated)setSpeed(0);active=false;appendRecord(128);}
void startSession(){
  if(active)return;if(!storageOK){commandError=6;return;}if(!nowEpoch()){commandError=7;return;}
  active=true;automatic=cfg.calibrated&&isfinite(probe);
  if(automatic)setSpeed(chooseSpeed(effectiveTemp(probe,room,rh,cfg),speed,cfg));
  // Apply rules immediately on Start; subsequent samples use their own cadence.
  lastSample=lastRoomSample=lastLog=millis();fanSamplePending=false;appendRecord(64);
}
void retire(uint32_t cursor){
  if(!mounted||cursor!=totalRecord||cursor==baseRecord)return;
  // Create and flush next generation BEFORE deleting the acknowledged old file.
  String next=pathFor(cursor);File fresh=LittleFS.open(next,FILE_APPEND);if(!fresh)return;fresh.flush();fresh.close();
  logFile.close();String previous=logPath;logPath=next;baseRecord=cursor;logFile=LittleFS.open(logPath,FILE_APPEND);
  if(logFile){LittleFS.remove(previous);storageOK=true;if(errorCode==6)errorCode=0;}else{storageOK=false;errorCode=6;}
}
void process(const uint8_t* b){
  ack=u16(b+2);commandError=0;if(b[0]!=1){commandError=1;return;}
  switch(b[1]){
    case 1:{if(active){commandError=4;break;}Config next;for(int i=0;i<3;i++)next.threshold[i]=(int16_t)u16(b+4+2*i);for(int i=0;i<4;i++)next.angle[i]=b[10+i];next.hysteresis=b[14];next.dwell=b[15];next.ambientWeight=b[16];next.humidityThreshold=b[17];next.humidityBoost=b[18];next.calibrated=b[19];if(!validConfig(next)){commandError=1;break;}cfg=next;prefs.putBytes("config",&cfg,sizeof(cfg));break;}
    case 2:switch(b[4]){
      case 0:stopSession();break;
      case 1:case 7:startSession();break;
      case 8:stopSession(true);break;
      case 2:if(b[5]>3){commandError=1;break;}if(!cfg.calibrated){commandError=2;break;}automatic=false;setSpeed(b[5]);if(active)appendRecord();break;
      case 3:if(!active){commandError=1;break;}if(!cfg.calibrated){commandError=2;break;}if(!isfinite(probe)){commandError=3;break;}automatic=true;break;
      case 4:automatic=false;detachServo();break;
      case 5:if(active){commandError=4;break;}if(b[5]>180){commandError=1;break;}moveAngle(b[5]);break;
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
class ServerEvents:public BLEServerCallbacks {void onConnect(BLEServer*)override{connected=true;synced=false;}void onDisconnect(BLEServer*)override{connected=false;restartAdvertising=true;synced=false;streaming=false;}};
class CommandEvents:public BLECharacteristicCallbacks {void onWrite(BLECharacteristic* c)override{auto v=c->getValue();if(v.length()==20){Command cmd;memcpy(cmd.b,v.c_str(),20);xQueueSend(commands,&cmd,0);}}};
void readFanSensor(){
  room=NAN;rh=NAN;
  if(!shtReady)shtReady=sht.begin();
  if(shtReady){sensors_event_t h,t;if(sht.getEvent(&h,&t)){if(t.temperature>=-40&&t.temperature<=125)room=t.temperature;if(h.relative_humidity>=0&&h.relative_humidity<=100)rh=h.relative_humidity;}else shtReady=false;}
}
void readBodySensor(){
  uint32_t sum=0;for(int i=0;i<16;i++)sum+=analogReadMilliVolts(PROBE_PIN);probe=thermistor(sum/16.0f,DIVIDER_SUPPLY_MV)+PROBE_OFFSET_C;
  if(!isfinite(probe)){if(errorCode==0)errorCode=3;}else if(errorCode==3)errorCode=0;
  if(active&&automatic){if(!isfinite(probe)){automatic=false;detachServo();}else{int next=chooseSpeed(effectiveTemp(probe,room,rh,cfg),speed,cfg);if(next!=speed&&millis()-lastChange>=uint32_t(cfg.dwell)*1000)setSpeed(next);}}
}
void sampleSensors(uint32_t now){
  bool fanDue=now-lastRoomSample>=FAN_SAMPLE_MS;
  if(fanDue){lastRoomSample=now;readFanSensor();fanSamplePending=true;}
  if(now-lastSample>=BODY_SAMPLE_MS){lastSample=now;readBodySensor();if(active){lastLog=now;appendRecord(0,fanSamplePending);}fanSamplePending=false;}
}
void joystick(){
  static bool neutral=false,lastRaw=true,stable=true,longHandled=false;static uint32_t changed=0,pressed=0;
  int y=analogRead(JOYSTICK_PIN);if(y>1400&&y<2700)neutral=true;
  if(neutral&&(y<700||y>3400)){neutral=false;if(cfg.calibrated){automatic=false;setSpeed(constrain(speed+(y>3400?1:-1),0,3));if(active)appendRecord();}}
  bool raw=digitalRead(BUTTON_PIN);if(raw!=lastRaw){changed=millis();lastRaw=raw;}
  if(millis()-changed>40&&raw!=stable){stable=raw;if(!stable){pressed=millis();longHandled=false;}else if(!longHandled){if(active){if(cfg.calibrated&&isfinite(probe))automatic=!automatic;}else{uint8_t b[20]={1,2};b[4]=1;process(b);}}}
  if(!stable&&!longHandled&&millis()-pressed>1500){longHandled=true;stopSession();}
}
void setup(){
  Serial.begin(115200);pinMode(BUTTON_PIN,INPUT_PULLUP);detachServo();analogReadResolution(12);analogSetPinAttenuation(PROBE_PIN,ADC_11db);analogSetPinAttenuation(JOYSTICK_PIN,ADC_11db);
  prefs.begin("breeze",false);archiveId=prefs.getUInt("archive",0);if(!archiveId){archiveId=esp_random();if(!archiveId)archiveId=1;prefs.putUInt("archive",archiveId);}if(prefs.getBytesLength("config")==sizeof(Config)){Config saved;prefs.getBytes("config",&saved,sizeof(saved));if(validConfig(saved))cfg=saved;}angle=cfg.angle[0];
  Wire.begin(D4,D5);Wire.setTimeOut(100);shtReady=sht.begin();if(shtReady){sht.setPrecision(SHT4X_HIGH_PRECISION);sht.setHeater(SHT4X_NO_HEATER);}openStorage();commands=xQueueCreate(12,sizeof(Command));
  BLEDevice::init("Breeze Fan");BLEServer* server=BLEDevice::createServer();server->setCallbacks(new ServerEvents());BLEService* service=server->createService(SERVICE);tx=service->createCharacteristic(TX_UUID,BLECharacteristic::PROPERTY_NOTIFY|BLECharacteristic::PROPERTY_READ);tx->addDescriptor(new BLE2902());BLECharacteristic* rx=service->createCharacteristic(RX_UUID,BLECharacteristic::PROPERTY_WRITE);rx->setCallbacks(new CommandEvents());service->start();BLEAdvertising* advertising=BLEDevice::getAdvertising();advertising->addServiceUUID(SERVICE);advertising->setScanResponse(true);advertising->start();readFanSensor();readBodySensor();lastSample=lastRoomSample=millis();
  Serial.println("Breeze ready. Servo disabled until calibration. Clock must be set by phone before first session after boot.");
}
void loop(){
  if(restartAdvertising){restartAdvertising=false;BLEDevice::startAdvertising();}
  Command command;while(xQueueReceive(commands,&command,0)==pdTRUE)process(command.b);
  uint32_t now=millis();sampleSensors(now);joystick();
  if(connected&&now-lastNotify>=1000){lastNotify=now;telemetry();}
  if(streaming&&connected&&now-lastStream>=30){lastStream=now;if(streamRecord<totalRecord){File read=LittleFS.open(logPath,FILE_READ);uint8_t b[20];if(read&&streamRecord>=baseRecord&&read.seek((streamRecord-baseRecord)*20)&&read.read(b,20)==20){notifyBytes(b);streamRecord++;}else{streaming=false;commandError=6;}read.close();}else{streaming=false;synced=true;metadata();}}
  delay(5);
}

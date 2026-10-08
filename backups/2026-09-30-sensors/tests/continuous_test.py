"""Run real controller motion functions with timer/PWM spies, without hardware."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'firmware/BreezeController/BreezeController.ino').read_text(encoding='utf-8')

def function(kind, name):
    start = source.index(kind+' '+name+'(')
    opening = source.index('{', start)
    end, depth = opening+1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

harness = r'''
#include <cassert>
#include <cmath>
#include <cstdio>
#include "SERVO_HEADER"
#include "CR_HEADER"
constexpr int SERVO_PIN=17,OUTPUT=1,LOW=0,portMAX_DELAY=1,ESP_OK=0;
bool pwm=false,motorTest=true,active=false,automatic=false,motorRunning=false,motorContinuous=false,connected=true;
bool attachOK=true,writeOK=true,timerOK=true,haveJogSequence=false;
uint16_t lastJogSequence=0;
uint32_t connectionGeneration=1;
int angle=60,servoPulse=0,errorCode=0,commandError=0,writes=0,lastDuty=0;
int motorNeutral=1500,motorDirection=0,motorPercent=0,motorLock=1,motorTimer=1,commands=1;
int64_t now=0,motorDeadline=0,timerDue=0;
int timerStarts=0,queueResets=0,lockDepth=0;
struct Prefs {int value=0;void putUShort(const char*,int v){value=v;}} prefs;
void xSemaphoreTake(int,int){assert(lockDepth==0);lockDepth++;}
void xSemaphoreGive(int){assert(lockDepth==1);lockDepth--;}
void xQueueReset(int){queueResets++;}
int64_t esp_timer_get_time(){return now;}
int esp_timer_stop(int){timerDue=0;return 0;}
int esp_timer_start_once(int,uint64_t duration){timerStarts++;timerDue=now+duration;return timerOK?0:1;}
bool ledcAttach(int pin,int hz,int bits){assert(pin==17&&hz==50&&bits==14);return attachOK;}
bool ledcWrite(int pin,int duty){assert(pin==17);writes++;lastDuty=duty;return writeOK;}
void ledcDetach(int){}void pinMode(int,int){}void digitalWrite(int,int){}
template<class T>T constrain(T v,T lo,T hi){return v<lo?lo:v>hi?hi:v;}
'''.replace('SERVO_HEADER', (root/'firmware/BreezeController/ServoTest.h').as_posix()).replace('CR_HEADER', (root/'firmware/BreezeController/ContinuousTest.h').as_posix())
harness += '\n'.join(function(k,n) for k,n in [
    ('uint16_t','u16'),('void','detachServo'),('bool','movePulse'),('bool','moveAngle'),
    ('void','testMotor'),('void','stopContinuousLocked'),('void','motorTimeout'),
    ('void','stopContinuous'),('void','disconnectMotor'),('void','continuousCommand')])
harness += r'''
uint16_t seq=1;
void jog(int direction=1,int percent=20,int duration=100,int neutral=1500,uint32_t generation=1,bool newSeq=true){
 uint8_t b[20]={1,2};if(newSeq)seq++;b[2]=seq;b[3]=seq>>8;b[4]=14;b[5]=direction;b[6]=percent;b[7]=duration;b[8]=duration>>8;b[9]=neutral;b[10]=neutral>>8;
 commandError=0;continuousCommand(b,generation);
}
void stop(){uint8_t b[20]={1,2};b[4]=15;continuousCommand(b,connectionGeneration);}
void run(int direction=1,int percent=100,int neutral=1500,uint32_t generation=2,bool newSeq=true,int duration=0){
 uint8_t b[20]={1,2};if(newSeq)seq++;b[2]=seq;b[3]=seq>>8;b[4]=17;b[5]=direction;b[6]=percent;b[7]=duration;b[8]=duration>>8;b[9]=neutral;b[10]=neutral>>8;
 commandError=0;continuousCommand(b,generation);
}
void trim(int neutral){uint8_t b[20]={1,2};b[4]=16;b[5]=neutral;b[6]=neutral>>8;commandError=0;continuousCommand(b,connectionGeneration);}
int main(){
 // Boot/released connection loss produces no PWM at all.
 disconnectMotor();assert(!pwm&&writes==0&&queueResets==1);
 // No forced start position. A tap directly drives chosen direction/speed.
 jog();assert(commandError==0&&pwm&&motorRunning&&servoPulse==1600&&motorDeadline==100000&&timerDue==100000);
 int before=writes;now=99999;motorTimeout(nullptr);assert(motorRunning&&writes==before);
 // This callback runs independently of main-loop sensor and storage work.
 now=100000;motorTimeout(nullptr);assert(!motorRunning&&pwm&&servoPulse==1500&&motorDirection==0&&motorPercent==0);
 // Retrying the same command after completion must not rotate a second time.
 before=writes;jog(1,20,100,1500,1,false);assert(writes==before&&!motorRunning);
 // Full output in both directions, and the shortest allowed timed turn.
 jog(2,100,20);assert(servoPulse==1000&&motorDirection==-1&&motorDeadline==120000);stop();assert(!motorRunning&&servoPulse==1500);
 jog(1,100,2000);assert(servoPulse==2000);before=writes;jog();assert(commandError==11&&writes==before);stop();
 // Explicit stop overrides running motion immediately; stale timer cannot restart it.
 before=writes;now+=3000000;motorTimeout(nullptr);assert(!motorRunning&&writes==before);
 // Invalid packets never change output.
 for(int direction: {0,3}){before=writes;jog(direction);assert(commandError==9&&writes==before);}
 for(int percent: {0,101}){before=writes;jog(1,percent);assert(commandError==9&&writes==before);}
 for(int duration: {19,2001}){before=writes;jog(1,20,duration);assert(commandError==9&&writes==before);}
 for(int neutral: {1399,1601}){before=writes;jog(1,20,100,neutral);assert(commandError==9&&writes==before);}
 trim(1510);assert(commandError==0&&servoPulse==1510&&prefs.value==1510&&!motorRunning);
 jog(2,100,100,1510);assert(servoPulse==1000);connected=false;disconnectMotor();assert(!motorRunning&&servoPulse==1510&&pwm);
 // Reconnect cannot replay a packet from the old connection.
 connectionGeneration=2;connected=true;before=writes;jog(1,20,100,1510,1);assert(commandError==12&&writes==before);
 // Trim cannot change a running burst. A stale timer cannot stop a newer burst early.
 jog(1,20,100,1510,2);before=writes;trim(1520);assert(commandError==11&&writes==before);motorTimeout(nullptr);assert(motorRunning);stop();
 active=true;before=writes;jog(1,20,100,1500,2);assert(commandError==4&&writes==before);active=false;
 // Timer or PWM failure cannot leave rotation enabled.
 timerOK=false;jog(1,20,100,1500,2);assert(commandError==5&&!motorRunning&&servoPulse==1500);timerOK=true;
 stopContinuous(true);before=writes;attachOK=false;jog(1,20,100,1500,2);assert(commandError==5&&!pwm&&!motorRunning);attachOK=true;
 writeOK=false;jog(1,20,100,1500,2);assert(commandError==5&&!pwm&&!motorRunning);writeOK=true;
 // Continuous is a latched command, never a chain of timed jogs.
 int previousStarts=timerStarts;run();assert(commandError==0&&motorRunning&&motorContinuous&&servoPulse==2000&&motorDeadline==0&&timerDue==0&&timerStarts==previousStarts);
 before=writes;now+=60000000;motorTimeout(nullptr);assert(motorRunning&&motorContinuous&&writes==before&&servoPulse==2000);
 // Direction and speed can be changed explicitly while continuously running.
 run(2,50);assert(commandError==0&&motorRunning&&motorContinuous&&servoPulse==1250&&motorDirection==-1&&motorPercent==50);
 before=writes;run(2,50,1500,2,false);assert(writes==before); // No replay.
 jog(1,20,100,1500,2);assert(commandError==11&&writes==before&&motorContinuous);
 trim(1510);assert(commandError==11&&writes==before);
 for(int direction:{0,3}){run(direction);assert(commandError==9&&writes==before);}
 for(int percent:{0,101}){run(1,percent);assert(commandError==9&&writes==before);}
 run(1,100,1500,2,true,20);assert(commandError==9&&writes==before); // No ambiguous duration.
 run(1,100,1399);assert(commandError==9&&writes==before);
 stop();assert(!motorRunning&&!motorContinuous&&servoPulse==1500);
 run();stop();before=writes;run(1,100,1500,2,false);assert(writes==before&&!motorRunning);
 run();stopContinuous(true);assert(!pwm&&!motorRunning&&!motorContinuous);
 run(2,100);connected=false;disconnectMotor();assert(pwm&&!motorRunning&&!motorContinuous&&servoPulse==1500);
 connected=true;connectionGeneration=3;before=writes;run(1,100,1500,2);assert(commandError==12&&writes==before);
 jog(1,20,100,1500,3);before=writes;run(1,100,1500,3);assert(commandError==11&&writes==before&&!motorContinuous);stop();
 // Saved absolute positions and legacy endpoint packets can never rotate this build.
 for(int action: {0,1,2,3,5,7,9,10,11,12,13})assert(legacyPositionCommand(2,action));
 assert(legacyPositionCommand(1,0));for(int action:{4,6,8,14,15,16,17})assert(!legacyPositionCommand(2,action));
 before=writes;assert(!moveAngle(90)&&commandError==10&&writes==before);testMotor(12,0);assert(commandError==10&&writes==before);
 puts("PASS: timed and continuous modes; continuous has no duration timeout; direction/speed updates; Stop/Release/disconnect; retry suppression; invalid packet guards; stale-connection guards; trim; timer/PWM faults; positional command rejection");
}
'''
harness = harness.replace('#include <cstdio>', '#include <cstdio>\n#include <initializer_list>')
with tempfile.TemporaryDirectory(prefix='breeze-continuous-test-') as directory:
    path = Path(directory)
    (path/'continuous.cpp').write_text(harness, encoding='utf-8')
    script = path/'run.cmd'
    script.write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 continuous.cpp /Fe:continuous.exe\nif errorlevel 1 exit /b 1\ncontinuous.exe\n')
    subprocess.run([str(script)], cwd=path, check=True)

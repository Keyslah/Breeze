"""Exercise the real firmware movement functions against PWM spies, no hardware."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'firmware/BreezeController/BreezeController.ino').read_text(encoding='utf-8')
def function(kind,name):
    start=source.index(kind+' '+name+'(');opening=source.index('{',start);end=opening+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
harness=r'''
#include <cassert>
#include <cmath>
#include <cstdio>
#include "HEADER"
constexpr int SERVO_PIN=17,OUTPUT=1,LOW=0;
bool pwm=false,motorTest=true,active=false,automatic=false,attachOK=true,writeOK=true;
int angle=60,servoPulse=0,errorCode=0,commandError=0,writes=0,lastDuty=0;
bool ledcAttach(int pin,int hz,int bits){assert(pin==17&&hz==50&&bits==14);return attachOK;}
bool ledcWrite(int pin,int duty){assert(pin==17);writes++;lastDuty=duty;return writeOK;}
void ledcDetach(int){} void pinMode(int,int){} void digitalWrite(int,int){}
'''.replace('HEADER',(root/'firmware/BreezeController/ServoTest.h').as_posix())
harness+='\n'.join(function(k,n) for k,n in [('void','detachServo'),('bool','movePulse'),('bool','moveAngle'),('void','testMotor')])
harness+=r'''
int main(){
 testMotor(10,2);assert(commandError==8&&writes==0&&!pwm);
 commandError=0;testMotor(9,0);assert(commandError==0&&pwm&&servoPulse==1500&&angle==90&&writes==1);
 int centerDuty=lastDuty;testMotor(10,2);assert(servoPulse==1502&&lastDuty>centerDuty&&writes==2);
 testMotor(11,2);assert(servoPulse==1500&&lastDuty==centerDuty&&writes==3);
 testMotor(10,100);assert(servoPulse==1600&&writes==4);
 int before=writes;testMotor(10,101);assert(commandError==9&&writes==before&&servoPulse==1600);
 testMotor(11,1);assert(commandError==9&&writes==before);
 active=true;testMotor(9,0);assert(commandError==4&&writes==before);active=false;
 movePulse(2000);before=writes;testMotor(10,2);assert(commandError==9&&writes==before&&servoPulse==2000);
 movePulse(1000);before=writes;testMotor(11,2);assert(commandError==9&&writes==before&&servoPulse==1000);
 detachServo();testMotor(10,2);assert(commandError==8&&writes==before&&!pwm);
 attachOK=false;testMotor(9,0);assert(commandError==5&&!pwm&&writes==before);
 attachOK=true;writeOK=false;testMotor(9,0);assert(commandError==5&&servoPulse==1000);
 puts("PASS: D7 PWM; explicit initialization; +/- microsteps; step/range limits; recording guard; release requires initialization; hardware error reporting");
}
'''
with tempfile.TemporaryDirectory(prefix='breeze-motor-test-') as directory:
    path=Path(directory);(path/'motor.cpp').write_text(harness,encoding='utf-8')
    build=path/'run.cmd';build.write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 motor.cpp /Fe:motor.exe\nif errorlevel 1 exit /b 1\nmotor.exe\n')
    subprocess.run([str(build)],cwd=path,check=True)

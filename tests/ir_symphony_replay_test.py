"""Verify complete Dreo/Symphony replay from the six preserved learned captures.

Uses the existing private backup reader and hardware spies. No hardware is
opened; original blobs are read only. This verifies the exact raw timings and
carrier supplied to IRsend, not optical output or physical fan acceptance.
"""
from pathlib import Path
import argparse
import runpy
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
helpers = runpy.run_path(str(ROOT / 'tests/ir_nvs_migration_test.py'))

HARNESS = r'''
#include "fake.h"
#define private public
#include "FanRemote.h"
#undef private
#include "FanRemote.cpp"
#include "private_backup_fixture.h"

int action(FanRemote& fan,uint8_t command,uint8_t value=0,uint8_t arg=0){
 uint8_t packet[20]={1,6};packet[4]=command;packet[5]=value;packet[6]=arg;
 return fan.command(packet);
}
FanRemote::Learned original(int slot){
 FanRemote::Learned code;const auto& bytes=legacy.at("ir"+std::to_string(slot));
 assert(bytes.size()==sizeof(code));memcpy(&code,bytes.data(),sizeof(code));return code;
}
void exactBurst(const FanRemote::Learned& expected){
 assert(lastRaw==std::vector<uint16_t>(expected.raw,expected.raw+expected.count));
 assert(rawCarriers.back()==expected.carrier&&rawSenderPins.back()==IR_TRANSMIT_PIN);
}
void unchangedCodes(const std::map<std::string,std::vector<uint8_t>>& before){
 for(int slot=0;slot<6;slot++){auto key="ir"+std::to_string(slot);assert(saved.at(key)==before.at(key));}
 assert(nvsRemoves==0);
}
void captureFixture(){
 const uint64_t values[]={0xD81,0xD82,0xD92,0xD84,0xD90,0xDA0};
 for(int slot=0;slot<6;slot++){
  auto code=original(slot);const int frames=slot<2?9:8;
  assert(code.decoded==1&&code.protocol==SYMPHONY&&code.bits==12&&code.carrier==38);
  assert(code.value==values[slot]&&code.count==frames*24-1);
  // Decode all captured frames independently from the separated mark widths.
  // In particular, Speed- D92 and Horizontal D90 must remain distinct.
  for(int frame=0;frame<frames;frame++){
   uint16_t word=0;
   for(int bit=0;bit<12;bit++){
    uint16_t mark=code.raw[frame*24+bit*2];assert(mark>=300&&mark<=1400);
    assert(mark<=500||mark>=1100);word=(word<<1)|(mark>800);
   }
   assert(word==code.value);
  }
 }
}
void actualCaptures(bool compact){
 saved=legacy;clockMs=100;availableEntries=64;statsFail=!compact;
 failStorage=false;nvsRemoves=0;writeAttempts.clear();cutPowerAfter=-1;
 pending=scheduledCapture=false;loopbackMode=0;sendSupported=true;
 FanRemote fan;fan.begin();statsFail=false;assert(fan.learnedMask==63);
 for(int slot=0;slot<6;slot++)assert(saved.at("ir"+std::to_string(slot)).size()==(compact?36+2*original(slot).count:sizeof(FanRemote::Learned)));
 const auto preserved=saved;
 for(int slot=0;slot<6;slot++){
  assert(action(fan,FanRemote::SYNC,5,1)==0);
  int rawBefore=rawTransmissions,decodedBefore=decodedCalls,totalBefore=transmissions;
  uint32_t started=clockMs;auto code=original(slot);uint32_t duration=0;
  for(int i=0;i<code.count;i++)duration+=code.raw[i];
  assert(action(fan,FanRemote::SEND,slot)==0);
  assert(rawTransmissions==rawBefore+1&&decodedCalls==decodedBefore&&transmissions==totalBefore+1);
  exactBurst(code);assert(clockMs-started==(duration+999)/1000);
  assert(fan.lastSend==clockMs&&fan.repeatSlot==FanRemote::NONE);unchangedCodes(preserved);
  clockMs+=500;
 }
 // Preserve wake timing after the actual full burst finishes; wrap only once.
 assert(action(fan,FanRemote::SYNC,10,1)==0);clockMs=fan.lastSend+60001;
 int rawBefore=rawTransmissions,decodedBefore=decodedCalls;uint16_t counter=fan.sendCounter;
 assert(action(fan,FanRemote::SEND,1)==0&&fan.estimated==1);
 assert(rawTransmissions==rawBefore+1&&fan.repeatSlot==1);exactBurst(original(1));
 uint32_t firstEnd=clockMs;assert(fan.lastSend==firstEnd);
 assert(action(fan,FanRemote::SEND,2)==FanRemote::BUSY&&rawTransmissions==rawBefore+1);
 clockMs=firstEnd+299;fan.tick(35,29,23,clockMs);assert(rawTransmissions==rawBefore+1);
 clockMs=firstEnd+300;fan.tick(35,29,23,clockMs);
 assert(rawTransmissions==rawBefore+2&&decodedCalls==decodedBefore&&fan.sendCounter==counter+2);
 assert(fan.estimated==1&&fan.recordedSetting()==1&&fan.repeatSlot==FanRemote::NONE);
 exactBurst(original(1));assert(fan.lastSend==clockMs&&clockMs>firstEnd+300);
 clockMs=fan.lastSend+60000;assert(action(fan,FanRemote::SEND,2)==0);
 assert(rawTransmissions==rawBefore+3&&fan.repeatSlot==FanRemote::NONE&&fan.estimated==10);
 exactBurst(original(2));unchangedCodes(preserved);
}
void otherProtocols(){
 for(auto protocol:{NEC,RC5,RC6,UNKNOWN}){
  auto code=original(1);code.protocol=protocol;code.decoded=protocol!=UNKNOWN;
  code.checksum=FanRemote::checksum(code);auto* bytes=reinterpret_cast<uint8_t*>(&code);
  saved.clear();saved["ir1"]=std::vector<uint8_t>(bytes,bytes+sizeof(code));
  clockMs=100;availableEntries=0;statsFail=true;FanRemote fan;fan.begin();statsFail=false;
  int rawBefore=rawTransmissions,decodedBefore=decodedCalls;sendSupported=true;
  assert(action(fan,FanRemote::SEND,1)==0);
  assert(decodedCalls==decodedBefore+(protocol!=UNKNOWN)&&rawTransmissions==rawBefore+(protocol==UNKNOWN));
  if(protocol==UNKNOWN)exactBurst(code);
  else{
   sendSupported=false;assert(action(fan,FanRemote::SEND,1)==0);
   assert(decodedCalls==decodedBefore+2&&rawTransmissions==rawBefore+1);exactBurst(code);
  }
 }
}
int main(){
 captureFixture();actualCaptures(false);actualCaptures(true);otherProtocols();
 puts("PASS: all six real Symphony captures replay exact 8/9-frame raw timings/carriers from legacy and compact storage; Speed-/Horizontal remain distinct; actual burst-end timestamps, 60s/300ms wake duplicate, H wrap once, immutable learned bytes, other decoded/raw fallback paths unchanged (not physical fan proof)");
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backup', type=Path, default=helpers['DEFAULT_BACKUP'])
    args = parser.parse_args()
    codes, _ = helpers['backup_codes'](args.backup)
    stub = helpers['ir_stub']()
    stub = stub.replace('inline int transmissions=0,rawTransmissions=0;',
                        'inline int transmissions=0,rawTransmissions=0,decodedCalls=0;')
    stub = stub.replace('int){if(sendSupported)transmissions++;return sendSupported;}',
                        'int){decodedCalls++;if(sendSupported)transmissions++;return sendSupported;}')
    stub = stub.replace('rawSenderPins.push_back(pin);',
                        'rawSenderPins.push_back(pin);uint32_t duration=0;for(int i=0;i<count;i++)duration+=raw[i];clockMs+=(duration+999)/1000;')
    assert 'decodedCalls++;' in stub and 'clockMs+=(duration+999)/1000;' in stub
    with tempfile.TemporaryDirectory(prefix='breeze-symphony-replay-test-') as directory:
        path = Path(directory)
        (path / 'fake.h').write_text(stub)
        for header in ('Arduino.h', 'Preferences.h', 'IRrecv.h', 'IRsend.h', 'IRutils.h', 'nvs.h'):
            (path / header).write_text('#include "fake.h"\n')
        fixture = 'const std::map<std::string,std::vector<uint8_t>> legacy={\n'
        fixture += ',\n'.join('{"' + key + '",{' + ','.join(map(str, value)) + '}}' for key, value in sorted(codes.items()))
        (path / 'private_backup_fixture.h').write_text(fixture + '\n};\n')
        (path / 'test.cpp').write_text(HARNESS)
        (path / 'run.cmd').write_text(
            '@echo off\n'
            'call "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\n'
            'cl /nologo /EHsc /std:c++17 /I . /I "' + str(ROOT / 'firmware/BreezeController') + '" test.cpp /Fe:test.exe\n'
            'if errorlevel 1 exit /b 1\n'
            'test.exe\n')
        subprocess.run([str(path / 'run.cmd')], cwd=path, check=True)


if __name__ == '__main__':
    main()

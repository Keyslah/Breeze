"""Exercise actual FanRemote learned-code migration with atomic, capacity-limited NVS.

Uses the private local flash backup by default. Only aggregate PASS results are
printed; extracted metadata and pulses exist only in a task-local temp directory.
This host test does not open a serial port, write the backup, or run firmware.
"""
import argparse
import ast
import importlib.util
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BACKUP = ROOT / 'backups/2026-10-09-ota-nvs-recovery/boot-partition-nvs-before-repair.bin'
PARSER = ROOT / 'firmware/out-fan-history-20261009/verification/nvs-summary.py'


def backup_codes(path):
    spec = importlib.util.spec_from_file_location('breeze_nvs_redacted', PARSER)
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    raw = path.read_bytes()
    data = raw[0x9000:0xE000]
    assert len(data) == 0x5000, 'Backup does not contain the verified NVS range'
    report = parser.inspect(data, 0x9000)
    codes = {}
    live_entries = 0
    for item in report['entries']:
        if item['namespace'] != 'breeze-ir' or item['type'] != 'blob_index' or item['key'] not in {f'ir{i}' for i in range(6)}:
            continue
        assert item['blob_integrity'] == 'pass' and item['breeze_integrity'] == 'pass', 'Backup learned-code integrity failed'
        live_entries += item['span']
        chunks = []
        for index in range(item['chunk_start'], item['chunk_start'] + item['chunk_count']):
            found = [part for part in report['entries'] if part['namespace'] == 'breeze-ir' and part['key'] == item['key'] and part['type'] == 'blob_data' and part['chunk_index'] == index]
            assert len(found) == 1, 'Ambiguous live learned-code chunk'
            part = found[0]
            live_entries += part['span']
            start = part['page'] * 4096 + 64 + (part['slot'] + 1) * 32
            chunks.append(data[start:start + part['size']])
        payload = b''.join(chunks)
        assert len(payload) == item['size'] == 1576, 'Unexpected legacy learned-code size'
        assert item['key'] not in codes, 'Duplicate live learned-code index'
        codes[item['key']] = payload
    assert sorted(codes) == [f'ir{i}' for i in range(6)], 'Expected six learned codes in backup'
    assert [struct.unpack_from('<H', codes[f'ir{i}'], 6)[0] for i in range(6)] == [215, 215, 191, 191, 191, 191], 'Unexpected backup pulse counts'
    assert live_entries == 314, 'Unexpected learned-code NVS occupancy'
    return codes, live_entries


def ir_stub():
    tree = ast.parse((ROOT / 'tests/ir_test.py').read_text(encoding='utf-8'))
    assignment = next(node for node in tree.body if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == 'stub' for target in node.targets))
    stub = ast.literal_eval(assignment.value)
    start = stub.index('class Preferences{')
    end = stub.index('class IRrecv{', start)
    return stub[:start] + NVS_STUB + stub[end:]


NVS_STUB = r'''
constexpr int ESP_OK=0,ESP_FAIL=-1;
struct nvs_stats_t {size_t used_entries=0,free_entries=0,available_entries=0,total_entries=630,namespace_count=0;};
inline size_t availableEntries=64;
inline bool statsFail=false,shortRead=false;
struct SimulatedPowerLoss {};
inline int cutPowerAfter=-1;
struct WriteAttempt {std::string key;size_t bytes,available;bool accepted;};
inline std::vector<WriteAttempt> writeAttempts;
inline size_t blobEntries(size_t bytes){return bytes?2+(bytes+31)/32:0;}
inline int nvs_get_stats(const char*,nvs_stats_t* result){
 if(statsFail)return ESP_FAIL;
 result->total_entries=630;result->available_entries=availableEntries;
 result->free_entries=availableEntries;result->used_entries=630-availableEntries;return ESP_OK;
}
class Preferences{public:
 bool begin(const char*,bool){return true;}
 bool getBool(const char*,bool fallback){return fallback;}
 size_t putBool(const char*,bool){nvsWrites++;return failStorage?0:1;}
 size_t putUInt(const char*,uint32_t){nvsWrites++;return failStorage?0:4;}
 size_t getBytesLength(const char* key){auto it=saved.find(key);return it==saved.end()?0:it->second.size();}
 size_t getBytes(const char* key,void* target,size_t bytes){
  auto it=saved.find(key);if(it==saved.end()||it->second.size()!=bytes||shortRead)return 0;
  memcpy(target,it->second.data(),bytes);return bytes;
 }
 size_t putBytes(const char* key,const void* source,size_t bytes){
  if(cutPowerAfter>=0&&writeAttempts.size()==size_t(cutPowerAfter))throw SimulatedPowerLoss{};
  nvsWrites++;size_t required=blobEntries(bytes);bool accepted=!failStorage&&required<=availableEntries;
  writeAttempts.push_back({key,bytes,availableEntries,accepted});if(!accepted)return 0;
  size_t previous=blobEntries(getBytesLength(key));
  // New data and index must fit before the old committed blob is reclaimed.
  availableEntries-=required;auto* data=static_cast<const uint8_t*>(source);
  saved[key]=std::vector<uint8_t>(data,data+bytes);availableEntries+=previous;return bytes;
 }
 bool remove(const char* key){nvsRemoves++;availableEntries+=blobEntries(getBytesLength(key));return saved.erase(key)>0;}
};
'''


HARNESS = r'''
#include "fake.h"
#define private public
#include "FanRemote.h"
#undef private
#include "FanRemote.cpp"
#include "private_backup_fixture.h"

using Blob=std::vector<uint8_t>;
uint16_t little16(const Blob& b,size_t at){return b.at(at)|uint16_t(b.at(at+1))<<8;}
uint32_t little32(const Blob& b,size_t at){return little16(b,at)|uint32_t(little16(b,at+2))<<16;}
uint32_t fnv(const Blob& b,size_t length){uint32_t value=2166136261u;for(size_t i=0;i<length;i++)value=(value^b.at(i))*16777619u;return value;}
void put16(Blob& b,size_t at,uint16_t value){b.at(at)=value;b.at(at+1)=value>>8;}
void fixCompactChecksum(Blob& b){uint32_t hash=fnv(b,b.size()-4);for(int i=0;i<4;i++)b[b.size()-4+i]=hash>>(8*i);}
FanRemote::Learned legacyCode(int slot){FanRemote::Learned code;assert(legacy.at("ir"+std::to_string(slot)).size()==sizeof(code));memcpy(&code,legacy.at("ir"+std::to_string(slot)).data(),sizeof(code));return code;}
void sameCode(const FanRemote::Learned& expected,const FanRemote::Learned& actual){
 assert(expected.magic==actual.magic&&actual.version==1&&expected.count==actual.count&&expected.carrier==actual.carrier);
 assert(expected.bits==actual.bits&&expected.protocol==actual.protocol&&expected.decoded==actual.decoded&&expected.reserved==actual.reserved);
 assert(expected.value==actual.value&&expected.address==actual.address&&expected.command==actual.command);
 for(size_t i=0;i<expected.count;i++)assert(expected.raw[i]==actual.raw[i]);
 assert(actual.checksum==FanRemote::checksum(actual));
}
void checkCompact(int slot,const Blob& compact){
 const auto& original=legacy.at("ir"+std::to_string(slot));size_t count=little16(original,6);
 assert(compact.size()==36+2*count&&little16(compact,4)==2&&little32(compact,compact.size()-4)==fnv(compact,compact.size()-4));
 for(size_t i=0;i<32+2*count;i++)if(i!=4&&i!=5)assert(original[i]==compact[i]);
}
void reset(size_t available=64){saved=legacy;saved["unrelated"]={9,8,7};availableEntries=available;nvsWrites=nvsRemoves=0;writeAttempts.clear();failStorage=statsFail=shortRead=false;cutPowerAfter=-1;}
void expectRejected(const Blob& bytes){
 saved.clear();saved["ir0"]=bytes;FanRemote f;f.storageReady=true;FanRemote::Learned code;
 int before=nvsWrites;assert(!f.load(0,code)&&nvsWrites==before&&saved["ir0"]==bytes);
}
int main(){
 static_assert(sizeof(FanRemote::Learned)==1576&&offsetof(FanRemote::Learned,raw)==32&&offsetof(FanRemote::Learned,checksum)==1568,"Frozen legacy record layout");
 // Real backed-up v1 bytes are migrated before receiver initialization and BLE setup.
 reset();FanRemote migrated;migrated.begin();assert(migrated.learnedMask==63&&nvsWrites==6&&nvsRemoves==0&&transmissions==0);
 assert(writeAttempts.size()==6&&writeAttempts[0].available==64);
 for(int slot=0;slot<6;slot++){
  auto name="ir"+std::to_string(slot);assert(writeAttempts[slot].key==name&&writeAttempts[slot].accepted);
  checkCompact(slot,saved.at(name));FanRemote::Learned loaded;assert(migrated.load(slot,loaded));sameCode(legacyCode(slot),loaded);
 }
 assert(availableEntries==278&&saved.at("unrelated")==Blob({9,8,7}));
 auto compact=saved;int writes=nvsWrites;FanRemote reboot;reboot.begin();assert(reboot.learnedMask==63&&nvsWrites==writes&&nvsRemoves==0&&saved==compact);
 for(int slot=0;slot<6;slot++){FanRemote::Learned loaded;assert(reboot.load(slot,loaded));sameCode(legacyCode(slot),loaded);}
 // Account for the two additional old chunk headers measured in this backup.
 // available_entries already excludes IDF's reserved page: do not subtract it again.
 availableEntries+=measuredLegacyEntries-6*blobEntries(1576);assert(availableEntries==280);
 assert(availableEntries>=8);availableEntries-=8;assert(availableEntries==272); // New profile/estimate budget.
 // Existing PHY/Wi-Fi replacements need room for the new copy before reclaiming
 // the previous same-size live data. These are transient allocations, not new
 // permanently occupied records. This models bookkeeping only, not IDF page GC.
 for(size_t temporary:std::vector<size_t>{65,36}){assert(availableEntries>=temporary);availableEntries-=temporary;availableEntries+=temporary;}
 assert(availableEntries==272);
 for(int slot=0;slot<6;slot++){auto replacement=legacyCode(slot);assert(reboot.save(slot,replacement));}
 assert(saved==compact&&availableEntries==272&&nvsRemoves==0);
 availableEntries=271;auto belowReserve=legacyCode(0);assert(!reboot.save(0,belowReserve)&&saved==compact);
 // An interrupted boot can resume from a mix of individually committed v1/v2 slots.
 reset();cutPowerAfter=2;try{FanRemote interrupted;interrupted.begin();assert(false);}catch(const SimulatedPowerLoss&){}
 assert(nvsWrites==2&&nvsRemoves==0);for(int slot=0;slot<6;slot++)assert(saved.at("ir"+std::to_string(slot))==(slot<2?compact:legacy).at("ir"+std::to_string(slot)));
 cutPowerAfter=-1;FanRemote resumed;resumed.begin();assert(resumed.learnedMask==63&&nvsWrites==6&&saved==compact);
 // A failed migration never removes/replaces a working old code, and can retry on reboot.
 reset();failStorage=true;FanRemote unavailable;unavailable.begin();assert(unavailable.learnedMask==63&&saved.at("ir0")==legacy.at("ir0")&&nvsRemoves==0);
 for(int slot=0;slot<6;slot++){FanRemote::Learned loaded;assert(unavailable.load(slot,loaded));sameCode(legacyCode(slot),loaded);}
 failStorage=false;FanRemote retry;retry.begin();assert(retry.learnedMask==63);for(int slot=0;slot<6;slot++)checkCompact(slot,saved.at("ir"+std::to_string(slot)));
 // Capacity/stat failure must preserve legacy data; no destructive free-space workaround.
 reset(0);auto original=saved;FanRemote full;full.begin();assert(full.learnedMask==63&&saved==original&&nvsRemoves==0);
 reset();statsFail=true;original=saved;FanRemote noStats;noStats.begin();assert(noStats.learnedMask==63&&saved==original&&nvsRemoves==0);statsFail=false;
 // Re-learning does not receive the boot-migration reserve exception.
 reset(64);FanRemote writer;writer.storageReady=true;auto replacement=legacyCode(0);replacement.raw[0]^=2;
 original=saved;assert(!writer.save(0,replacement)&&saved==original&&nvsRemoves==0);
 assert(!writer.saveCompact(0,replacement,true)&&saved==original); // Migration cannot authorize changed learned content.
 saved=compact;availableEntries=100;original=saved;assert(!writer.save(0,replacement)&&saved==original);
 availableEntries=300;failStorage=true;assert(!writer.save(0,replacement)&&saved==original);failStorage=false;
 assert(writer.save(0,replacement));FanRemote::Learned changed;assert(writer.load(0,changed));sameCode(replacement,changed);
 assert(saved.at("ir1")==compact.at("ir1")&&nvsRemoves==0);
 // Reject malformed compact headers even with a recomputed valid payload checksum.
 Blob good=compact.at("ir0"),bad=good;bad[32]^=1;expectRejected(bad);
 bad=good;bad.pop_back();expectRejected(bad);bad=good;bad.push_back(0);expectRejected(bad);
 for(auto field:std::vector<std::pair<size_t,uint16_t>>{{4,3},{6,9},{6,769},{8,29},{8,61},{10,65}}){bad=good;put16(bad,field.first,field.second);fixCompactChecksum(bad);expectRejected(bad);}
 bad=good;bad[14]=2;fixCompactChecksum(bad);expectRejected(bad);bad=good;bad[0]^=1;fixCompactChecksum(bad);expectRejected(bad);
 bad=legacy.at("ir0");bad[32]^=1;expectRejected(bad);bad=legacy.at("ir0");bad.pop_back();expectRejected(bad);
 saved.clear();saved["ir0"]=good;FanRemote shortReader;shortReader.storageReady=true;FanRemote::Learned loaded;shortRead=true;assert(!shortReader.load(0,loaded));
 puts("PASS: six actual v1 backups preserve all metadata/pulses in compact v2; 64-entry migration, interrupted migration, and reboot idempotence; corruption/length/read rejection; failed migration/replacement retains old codes; radio allocation bookkeeping, capacity reserve, and no-delete guard (not IDF flash/GC proof)");
}
'''


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--backup', type=Path, default=DEFAULT_BACKUP)
    options = args.parse_args()
    codes, measured_entries = backup_codes(options.backup)
    firmware = (ROOT / 'firmware/BreezeController/BreezeController.ino').read_text(encoding='utf-8')
    setup = firmware[firmware.index('void setup()'):firmware.index('void loop()')]
    assert setup.index('fan.begin()') < setup.index('BLEDevice::init'), 'Migration must run before BLE starts writing NVS'
    with tempfile.TemporaryDirectory(prefix='breeze-ir-nvs-migration-') as directory:
        path = Path(directory)
        (path / 'fake.h').write_text(ir_stub())
        for header in ('Arduino.h', 'Preferences.h', 'IRrecv.h', 'IRsend.h', 'IRutils.h', 'nvs.h'):
            (path / header).write_text('#include "fake.h"\n')
        fixture = f'constexpr size_t measuredLegacyEntries={measured_entries};\n'
        fixture += 'const std::map<std::string,std::vector<uint8_t>> legacy={\n'
        fixture += ',\n'.join('{"' + key + '",{' + ','.join(map(str, value)) + '}}' for key, value in sorted(codes.items()))
        (path / 'private_backup_fixture.h').write_text(fixture + '\n};\n')
        (path / 'test.cpp').write_text(HARNESS)
        (path / 'run.cmd').write_text('@echo off\ncall "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul\ncl /nologo /EHsc /std:c++17 /I . /I "' + str(ROOT / 'firmware/BreezeController') + '" test.cpp /Fe:test.exe\nif errorlevel 1 exit /b 1\ntest.exe\n')
        subprocess.run([str(path / 'run.cmd')], cwd=path, check=True)


if __name__ == '__main__':
    main()

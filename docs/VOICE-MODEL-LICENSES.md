# Offline voice test: asset provenance and licenses

Verified and downloaded on 2026-10-07. The locked-screen voice test uses the
bundled US English Vosk model. Recognition runs on the Android device; installing
these assets does not upload speech audio or require an account or cloud service.

## Vosk model

- Model: `vosk-model-small-en-us-0.15`.
- Official listing: https://alphacephei.com/vosk/models . The entry identifies this
  as the 40M lightweight Android/Raspberry Pi US English model under Apache 2.0.
- Official archive: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
- Download size: **41,205,931 bytes**.
- Archive SHA-256:
  `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.
- Local bundled model: `app/src/main/assets/voice-model/`.
- Original archive retained in `app/voice-test-downloads/` as a task download.

The archive's single `vosk-model-small-en-us-0.15/` wrapper directory was removed
when extracting. Its 14 files (70,898,967 bytes total) are otherwise unchanged.
All 20 ZIP entries were inspected and extraction checked for path traversal,
unexpected roots, symbolic links, and existing target files before writing.
The supplied `README`, including the 2020 Alpha Cephei Inc copyright notice,
is retained verbatim. The archive contains no separate license file. The
official model listing supplies its Apache 2.0 license declaration; the Apache
2.0 text from the official Vosk repository is bundled as
`voice-model/LICENSE-APACHE-2.0.txt`:
https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING .

### Extracted upstream file inventory

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `README` | 199 | `c0cf286e4f7783306c5f6469b37db69228fb16803b03cae661edb2d7bba64ebb` |
| `am/final.mdl` | 15,962,575 | `75370a0137f9daf8f469dedd7daa4513ae7a621f03240c6e512e2b50b656a7b6` |
| `conf/mfcc.conf` | 131 | `1e2228006d01d805ad1c267fee9f79709ca87ac51bd82b0e3f5c69ba543f0fc4` |
| `conf/model.conf` | 290 | `8f14cb1eeb07c762c371db648c6be688d347236155ca0f64fb13b6567a8ce81f` |
| `graph/disambig_tid.int` | 102 | `9ad87cc166d0998f08f758f47a6223a120dfffcbee805c4849c3aa5e6bb3c0fc` |
| `graph/Gr.fst` | 24,013,795 | `023c8b7e30704a9e37765c635c252e608a02f361235bf94abdcf2a5225d85b20` |
| `graph/HCLr.fst` | 22,416,994 | `5caafba3081e1646545ac6bff0dd7a318e53dcbdc86f237909ce1d2ac1293d34` |
| `graph/phones/word_boundary.int` | 1,761 | `da199d9c991e0e84681ddbb34627b915b26302d50a8fdaa23c51e2bc3a50b5c3` |
| `ivector/final.dubm` | 168,048 | `8c5d7dd69d2122313baaf19f61f35dd3fa18b70c62ac0687e311e1c46e6daca7` |
| `ivector/final.ie` | 8,288,887 | `3f37faf90c375b9e4740b569398b5829ed9cc07d19be6d441f72c3b71d7efcc6` |
| `ivector/final.mat` | 44,975 | `ddd83586dc5f928cda8738b922c85ffe38fc789cb5f9151a712ca12f37265382` |
| `ivector/global_cmvn.stats` | 1,080 | `33be09afcc80059847a275c3d043b51f1ab954c7c2438ddbbf4745e8ba144ff9` |
| `ivector/online_cmvn.conf` | 95 | `a2f3571754b64297cb7efb2e7ca3df61995c5a45fcbb97188f90613552bb2dfe` |
| `ivector/splice.conf` | 35 | `9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756` |

## Android libraries

The integration pins the native dependencies used by the official Android demo:

- `com.alphacephei:vosk-android:0.3.75@aar` — Vosk API, Apache 2.0.
  Source: https://github.com/alphacep/vosk-api .
- `net.java.dev.jna:jna:5.18.1@aar` — Java Native Access, offered under
  **Apache-2.0 OR LGPL-2.1-or-later**. Breeze uses the Apache 2.0 option.
  Versioned source: https://github.com/java-native-access/jna/tree/5.18.1 .

Official Android demo dependency declaration:
https://github.com/alphacep/vosk-android-demo/blob/master/app/build.gradle .

JNA's upstream dual-license declaration and both license texts are retained as
additional assets (they are not part of the upstream model archive):

- `voice-model/JNA-LICENSE.txt` —
  https://raw.githubusercontent.com/java-native-access/jna/5.18.1/LICENSE
- `voice-model/JNA-APACHE-2.0.txt` —
  https://raw.githubusercontent.com/java-native-access/jna/5.18.1/AL2.0
- `voice-model/JNA-LGPL-2.1.txt` —
  https://raw.githubusercontent.com/java-native-access/jna/5.18.1/LGPL2.1

Neither official repository root had a separate `NOTICE` file at inspection.
Keep the upstream copyright and dependency notices supplied in the AARs when
packaging. The model archive hash above records the exact downloaded artifact;
it is a local integrity measurement, not an independently published checksum.

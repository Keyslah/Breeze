#pragma once

#include <Arduino.h>
#include <NetworkClient.h>
#include <NetworkServer.h>
#include <mbedtls/sha256.h>

class OtaUpdate {
 public:
  using NotifyFunction = void (*)(uint8_t* packet);

  enum State : uint8_t {
    IDLE = 0,
    PREPARING = 1,
    READY = 2,
    UPLOADING = 3,
    VERIFYING = 4,
    REBOOTING = 5,
    COMPLETE = 6,
    ERROR = 7,
  };

  enum Error : uint8_t {
    OTA_OK = 0,
    AUTH = 1,
    BAD_LENGTH = 2,
    BAD_SHA256 = 3,
    UPDATE_BEGIN = 4,
    UPDATE_WRITE = 5,
    UPDATE_VERIFY = 6,
    UPLOAD_ABORTED = 7,
    TIMEOUT = 8,
    AP_START = 9,
    WRONG_STATE = 10,
  };

  static constexpr uint8_t COMMAND_AP_START_FAILED = 31;
  static constexpr uint8_t COMMAND_CONFIRM_FAILED = 32;

  OtaUpdate();
  uint8_t enter(NotifyFunction notify);
  uint8_t cancel();
  uint8_t confirmRunning();
  void tick();
  bool active() const { return modeActive; }
  bool uploading() const { return uploadActive; }
  bool pendingVerification() const;

 private:
  enum ClientStage : uint8_t { NO_CLIENT, HEADERS, BODY };
  static constexpr uint32_t MODE_TIMEOUT_MS = 5UL * 60UL * 1000UL;
  static constexpr uint32_t REQUEST_STALL_MS = 30UL * 1000UL;
  static constexpr uint32_t REBOOT_DELAY_MS = 1000;
  static constexpr uint16_t MAX_HEADER_BYTES = 2048;
  static constexpr uint16_t MAX_LINE_BYTES = 191;

  NetworkServer server;
  NetworkClient client;
  NotifyFunction notify = nullptr;
  ClientStage clientStage = NO_CLIENT;
  bool modeActive = false;
  bool uploadActive = false;
  bool rebootPending = false;
  bool requestLineSeen = false;
  bool requestLineOK = false;
  bool lengthSeen = false;
  bool tokenSeen = false;
  bool shaSeen = false;
  bool shaStarted = false;
  uint8_t state = IDLE;
  uint8_t error = OTA_OK;
  uint8_t progress = 0;
  uint8_t lastStatusProgress = 255;
  uint8_t lastLogProgress = 255;
  uint8_t token[8] = {};
  char tokenHex[17] = {};
  // The full one-time token is included in the temporary SSID so the Windows
  // uploader can discover the credentials without a phone or home router.
  char ssid[28] = {};
  char expectedSha[65] = {};
  char line[MAX_LINE_BYTES + 1] = {};
  uint16_t lineLength = 0;
  uint16_t headerBytes = 0;
  uint32_t capacityBytes = 0;
  uint32_t expectedBytes = 0;
  uint32_t receivedBytes = 0;
  uint32_t lastActivityAt = 0;
  uint32_t rebootAt = 0;
  mbedtls_sha256_context shaContext;

  void makeSessionCredentials();
  void acceptClient();
  void serviceClient();
  void resetRequest();
  void consumeHeaderByte(char value);
  void parseHeaderLine();
  void finishHeaders();
  void readBody();
  void finishUpload();
  void clearSha();
  bool finishShaMatches();
  void failRequest(uint8_t reason, uint16_t httpCode, bool abortUpdate = true);
  void closeClient();
  void sendResponse(uint16_t code, const char* phrase, const char* body);
  void stopMode(bool sendIdle);
  void emitStatus();
  void updateProgress();
  bool tokenMatches(const char* value) const;
  static bool parseLength(const char* value, uint32_t& result);
  static bool validSha256(const char* value);
  static const char* skipSpaces(const char* value);
  static void put32(uint8_t* destination, uint32_t value);
};

#include "OtaUpdate.h"

#include <Update.h>
#include <WiFi.h>
#include <esp_app_format.h>
#include <esp_ota_ops.h>
#include <esp_random.h>
#include <ctype.h>
#include <string.h>

namespace {
const char HEX_DIGITS[] = "0123456789ABCDEF";
const char JSON_OK[] = "{\"ok\":true,\"status\":\"rebooting\"}";
const char JSON_AUTH[] = "{\"ok\":false,\"error\":\"Unauthorized\"}";
const char JSON_BAD_REQUEST[] = "{\"ok\":false,\"error\":\"Invalid firmware request\"}";
const char JSON_BUSY[] = "{\"ok\":false,\"error\":\"OTA is not ready\"}";
const char JSON_UPDATE[] = "{\"ok\":false,\"error\":\"Firmware update failed\"}";
const char JSON_TIMEOUT[] = "{\"ok\":false,\"error\":\"Upload timed out\"}";

bool startsWithIgnoreCase(const char* value, const char* prefix) {
  while (*prefix) {
    if (!*value || tolower(static_cast<unsigned char>(*value)) != tolower(static_cast<unsigned char>(*prefix))) return false;
    ++value;
    ++prefix;
  }
  return true;
}

const char* errorBody(uint8_t reason) {
  if (reason == OtaUpdate::AUTH) return JSON_AUTH;
  if (reason == OtaUpdate::WRONG_STATE) return JSON_BUSY;
  if (reason == OtaUpdate::TIMEOUT) return JSON_TIMEOUT;
  if (reason == OtaUpdate::UPDATE_BEGIN || reason == OtaUpdate::UPDATE_WRITE || reason == OtaUpdate::UPDATE_VERIFY) return JSON_UPDATE;
  return JSON_BAD_REQUEST;
}

const char* reasonPhrase(uint16_t code) {
  switch (code) {
    case 200: return "OK";
    case 400: return "Bad Request";
    case 401: return "Unauthorized";
    case 408: return "Request Timeout";
    case 409: return "Conflict";
    default: return "Internal Server Error";
  }
}
}

// Arduino-ESP32 otherwise marks a pending OTA image valid before sketch setup.
extern "C" bool verifyRollbackLater() {
  return true;
}

OtaUpdate::OtaUpdate() : server(80, 1) {
  mbedtls_sha256_init(&shaContext);
}

void OtaUpdate::put32(uint8_t* b, uint32_t v) {
  b[0] = v;
  b[1] = v >> 8;
  b[2] = v >> 16;
  b[3] = v >> 24;
}

const char* OtaUpdate::skipSpaces(const char* value) {
  while (*value == ' ' || *value == '\t') ++value;
  return value;
}

bool OtaUpdate::parseLength(const char* value, uint32_t& result) {
  value = skipSpaces(value);
  if (*value < '0' || *value > '9') return false;
  uint32_t parsed = 0;
  while (*value >= '0' && *value <= '9') {
    const uint8_t digit = *value++ - '0';
    if (parsed > (UINT32_MAX - digit) / 10U) return false;
    parsed = parsed * 10U + digit;
  }
  value = skipSpaces(value);
  if (*value) return false;
  result = parsed;
  return true;
}

bool OtaUpdate::validSha256(const char* value) {
  for (uint8_t i = 0; i < 64; ++i) {
    const char c = value[i];
    if (!c || !isxdigit(static_cast<unsigned char>(c))) return false;
  }
  return value[64] == 0;
}

void OtaUpdate::clearSha() {
  mbedtls_sha256_free(&shaContext);
  mbedtls_sha256_init(&shaContext);
  shaStarted = false;
}

bool OtaUpdate::finishShaMatches() {
  if (!shaStarted) return false;
  uint8_t digest[32];
  const bool finished = mbedtls_sha256_finish(&shaContext, digest) == 0;
  clearSha();
  if (!finished) return false;

  uint8_t difference = 0;
  for (uint8_t i = 0; i < sizeof(digest); ++i) {
    const char high = static_cast<char>(toupper(static_cast<unsigned char>(expectedSha[i * 2])));
    const char low = static_cast<char>(toupper(static_cast<unsigned char>(expectedSha[i * 2 + 1])));
    const uint8_t expected = static_cast<uint8_t>(((high <= '9' ? high - '0' : high - 'A' + 10) << 4)
                                                   | (low <= '9' ? low - '0' : low - 'A' + 10));
    difference |= digest[i] ^ expected;
  }
  return difference == 0;
}

bool OtaUpdate::tokenMatches(const char* value) const {
  for (uint8_t i = 0; i < 16; ++i) {
    if (!value[i] || toupper(static_cast<unsigned char>(value[i])) != tokenHex[i]) return false;
  }
  return value[16] == 0;
}

void OtaUpdate::makeSessionCredentials() {
  esp_fill_random(token, sizeof(token));
  for (uint8_t i = 0; i < sizeof(token); ++i) {
    tokenHex[i * 2] = HEX_DIGITS[token[i] >> 4];
    tokenHex[i * 2 + 1] = HEX_DIGITS[token[i] & 15];
  }
  tokenHex[16] = 0;
  memcpy(ssid, "Breeze-OTA-", 11);
  memcpy(ssid + 11, tokenHex, 16);
  ssid[27] = 0;
}

uint8_t OtaUpdate::enter(NotifyFunction callback) {
  notify = callback;
  if (modeActive) {
    emitStatus();
    return 0;
  }

  makeSessionCredentials();
  const esp_partition_t* next = esp_ota_get_next_update_partition(nullptr);
  capacityBytes = next ? next->size : 0;
  expectedBytes = receivedBytes = 0;
  progress = 0;
  error = OTA_OK;
  state = PREPARING;
  emitStatus();
  Serial.println("[OTA] mode entered");

  if (!capacityBytes) {
    state = ERROR;
    error = UPDATE_BEGIN;
    emitStatus();
    Serial.println("[OTA] no inactive OTA partition");
    return COMMAND_AP_START_FAILED;
  }
  if (!WiFi.mode(WIFI_AP) || !WiFi.softAP(ssid, tokenHex, 1, false, 1)) {
    state = ERROR;
    error = AP_START;
    emitStatus();
    Serial.println("[OTA] access point start failed");
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_OFF);
    return COMMAND_AP_START_FAILED;
  }

  server.begin();
  if (!server) {
    state = ERROR;
    error = AP_START;
    emitStatus();
    Serial.println("[OTA] HTTP socket start failed");
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_OFF);
    return COMMAND_AP_START_FAILED;
  }

  modeActive = true;
  uploadActive = rebootPending = false;
  clientStage = NO_CLIENT;
  lastActivityAt = millis();
  state = READY;
  emitStatus();
  Serial.println("[OTA] access point started at 192.168.4.1");
  return 0;
}

uint8_t OtaUpdate::cancel() {
  if (!modeActive) return 0;
  if (uploadActive || rebootPending) return 22;
  stopMode(true);
  Serial.println("[OTA] mode canceled; normal operation resumed");
  return 0;
}

bool OtaUpdate::pendingVerification() const {
  const esp_partition_t* running = esp_ota_get_running_partition();
  esp_ota_img_states_t imageState;
  return running
      && esp_ota_get_state_partition(running, &imageState) == ESP_OK
      && imageState == ESP_OTA_IMG_PENDING_VERIFY;
}

uint8_t OtaUpdate::confirmRunning() {
  if (!pendingVerification()) return 0;
  const esp_err_t result = esp_ota_mark_app_valid_cancel_rollback();
  if (result == ESP_OK) {
    Serial.println("[OTA] startup health check passed; rollback canceled");
    return 0;
  }
  Serial.printf("[OTA] could not confirm running firmware: %s\n", esp_err_to_name(result));
  return COMMAND_CONFIRM_FAILED;
}

void OtaUpdate::resetRequest() {
  requestLineSeen = requestLineOK = false;
  lengthSeen = tokenSeen = shaSeen = false;
  lineLength = headerBytes = 0;
  expectedBytes = receivedBytes = 0;
  progress = 0;
  lastStatusProgress = lastLogProgress = 255;
  expectedSha[0] = 0;
  clientStage = HEADERS;
}

void OtaUpdate::acceptClient() {
  if (clientStage != NO_CLIENT) return;
  NetworkClient incoming = server.accept();
  if (!incoming) return;
  client = incoming;
  client.setNoDelay(true);
  resetRequest();
  lastActivityAt = millis();
}

void OtaUpdate::consumeHeaderByte(char value) {
  if (++headerBytes > MAX_HEADER_BYTES) {
    failRequest(BAD_LENGTH, 400, false);
    return;
  }
  if (value == '\r') return;
  if (value != '\n') {
    if (lineLength >= MAX_LINE_BYTES) {
      failRequest(BAD_LENGTH, 400, false);
      return;
    }
    line[lineLength++] = value;
    return;
  }

  line[lineLength] = 0;
  if (!lineLength) finishHeaders();
  else parseHeaderLine();
  lineLength = 0;
}

void OtaUpdate::parseHeaderLine() {
  if (!requestLineSeen) {
    requestLineSeen = true;
    requestLineOK = strcmp(line, "POST /firmware HTTP/1.1") == 0 || strcmp(line, "POST /firmware HTTP/1.0") == 0;
    return;
  }
  if (startsWithIgnoreCase(line, "Content-Length:")) {
    lengthSeen = parseLength(line + 15, expectedBytes);
  } else if (startsWithIgnoreCase(line, "X-Breeze-Token:")) {
    tokenSeen = tokenMatches(skipSpaces(line + 15));
  } else if (startsWithIgnoreCase(line, "X-Firmware-SHA256:")) {
    const char* value = skipSpaces(line + 18);
    shaSeen = validSha256(value);
    if (shaSeen) memcpy(expectedSha, value, 65);
  }
}

void OtaUpdate::finishHeaders() {
  if (!requestLineOK) {
    failRequest(WRONG_STATE, 409, false);
    return;
  }
  if (!tokenSeen) {
    failRequest(AUTH, 401, false);
    return;
  }
  if (!lengthSeen || expectedBytes < sizeof(esp_image_header_t) || expectedBytes > capacityBytes) {
    failRequest(BAD_LENGTH, 400, false);
    return;
  }
  if (!shaSeen) {
    failRequest(BAD_SHA256, 400, false);
    return;
  }
  if (!Update.begin(expectedBytes, U_FLASH)) {
    Serial.printf("[OTA] Update.begin failed: %s\n", Update.errorString());
    failRequest(UPDATE_BEGIN, 500);
    return;
  }
  clearSha();
  if (mbedtls_sha256_starts(&shaContext, 0) != 0) {
    Serial.println("[OTA] SHA-256 setup failed");
    failRequest(UPDATE_BEGIN, 500);
    return;
  }
  shaStarted = true;

  uploadActive = true;
  state = UPLOADING;
  error = OTA_OK;
  clientStage = BODY;
  emitStatus();
  Serial.println("[OTA] computer upload client connected");
  Serial.printf("[OTA] upload started: %lu bytes\n", static_cast<unsigned long>(expectedBytes));
}

void OtaUpdate::readBody() {
  uint8_t buffer[1460];
  size_t budget = 8192;
  while (clientStage == BODY && receivedBytes < expectedBytes && budget && client.available() > 0) {
    const size_t remaining = expectedBytes - receivedBytes;
    size_t amount = static_cast<size_t>(client.available());
    if (amount > sizeof(buffer)) amount = sizeof(buffer);
    if (amount > remaining) amount = remaining;
    if (amount > budget) amount = budget;
    const int count = client.read(buffer, amount);
    if (count <= 0) break;
    lastActivityAt = millis();
    if (Update.write(buffer, static_cast<size_t>(count)) != static_cast<size_t>(count)) {
      Serial.printf("[OTA] write failed: %s\n", Update.errorString());
      failRequest(UPDATE_WRITE, 500);
      return;
    }
    if (mbedtls_sha256_update(&shaContext, buffer, static_cast<size_t>(count)) != 0) {
      Serial.println("[OTA] SHA-256 calculation failed");
      failRequest(UPDATE_WRITE, 500);
      return;
    }
    receivedBytes += static_cast<uint32_t>(count);
    budget -= static_cast<size_t>(count);
    updateProgress();
  }
  if (clientStage == BODY && receivedBytes == expectedBytes) finishUpload();
}

void OtaUpdate::finishUpload() {
  state = VERIFYING;
  progress = 100;
  emitStatus();
  Serial.println("[OTA] upload complete; validating image and SHA-256");
  if (!finishShaMatches()) {
    Serial.println("[OTA] SHA-256 mismatch");
    failRequest(BAD_SHA256, 400);
    return;
  }
  if (!Update.isFinished() || !Update.end(false)) {
    Serial.printf("[OTA] validation failed: %s\n", Update.errorString());
    failRequest(UPDATE_VERIFY, 500);
    return;
  }

  uploadActive = false;
  state = REBOOTING;
  error = OTA_OK;
  emitStatus();
  Serial.println("[OTA] validation succeeded; reboot pending");
  sendResponse(200, "OK", JSON_OK);
  client.flush();
  closeClient();
  rebootPending = true;
  rebootAt = millis() + REBOOT_DELAY_MS;
}

void OtaUpdate::sendResponse(uint16_t code, const char* phrase, const char* body) {
  if (!client.connected()) return;
  client.printf(
    "HTTP/1.1 %u %s\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n%s",
    code, phrase, static_cast<unsigned>(strlen(body)), body
  );
}

void OtaUpdate::failRequest(uint8_t reason, uint16_t httpCode, bool abortUpdate) {
  if (abortUpdate && Update.isRunning()) Update.abort();
  clearSha();
  uploadActive = false;
  state = ERROR;
  error = reason;
  lastActivityAt = millis();
  emitStatus();
  Serial.printf("[OTA] request failed: reason=%u\n", reason);
  if (httpCode) {
    sendResponse(httpCode, reasonPhrase(httpCode), errorBody(reason));
    client.flush();
  }
  closeClient();
  // A failed upload must not leave normal BLE/sensor/IR commands blocked for
  // the remainder of the OTA-mode timeout. The ERROR notification above is
  // retained on the phone, while the AP and partially written image are
  // closed immediately and the currently running firmware stays active.
  stopMode(false);
}

void OtaUpdate::closeClient() {
  if (client) client.stop();
  client = NetworkClient();
  clientStage = NO_CLIENT;
  lineLength = headerBytes = 0;
}

void OtaUpdate::serviceClient() {
  if (clientStage == NO_CLIENT) return;
  if (!client.connected() && client.available() <= 0) {
    failRequest(UPLOAD_ABORTED, 0);
    return;
  }

  if (clientStage == HEADERS) {
    uint16_t budget = MAX_HEADER_BYTES;
    while (clientStage == HEADERS && budget-- && client.available() > 0) {
      const int value = client.read();
      if (value < 0) break;
      lastActivityAt = millis();
      consumeHeaderByte(static_cast<char>(value));
    }
  }
  if (clientStage == BODY) readBody();
}

void OtaUpdate::updateProgress() {
  progress = expectedBytes ? static_cast<uint8_t>((uint64_t(receivedBytes) * 100U) / expectedBytes) : 0;
  if (lastStatusProgress == 255 || progress == 100 || progress >= lastStatusProgress + 5) {
    lastStatusProgress = progress;
    emitStatus();
  }
  if (lastLogProgress == 255 || progress == 100 || progress >= lastLogProgress + 10) {
    lastLogProgress = progress;
    Serial.printf("[OTA] progress %u%%\n", progress);
  }
}

void OtaUpdate::emitStatus() {
  if (!notify) return;
  uint8_t packet[20] = {7, state, progress, error};
  put32(packet + 4, receivedBytes);
  const uint32_t total = (state == PREPARING || state == READY) ? capacityBytes : expectedBytes;
  put32(packet + 8, total);
  memcpy(packet + 12, token, sizeof(token));
  notify(packet);
}

void OtaUpdate::stopMode(bool sendIdle) {
  if (Update.isRunning()) Update.abort();
  clearSha();
  uploadActive = rebootPending = false;
  closeClient();
  if (modeActive) server.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
  modeActive = false;
  capacityBytes = expectedBytes = receivedBytes = 0;
  progress = 0;
  error = OTA_OK;
  state = IDLE;
  if (sendIdle) emitStatus();
  memset(token, 0, sizeof(token));
  memset(tokenHex, 0, sizeof(tokenHex));
  memset(ssid, 0, sizeof(ssid));
  memset(expectedSha, 0, sizeof(expectedSha));
}

void OtaUpdate::tick() {
  if (!modeActive) return;
  const uint32_t now = millis();
  if (rebootPending) {
    if (int32_t(now - rebootAt) >= 0) {
      Serial.println("[OTA] rebooting into updated firmware");
      Serial.flush();
      ESP.restart();
    }
    return;
  }

  acceptClient();
  serviceClient();
  const uint32_t afterService = millis();
  if (clientStage != NO_CLIENT && uint32_t(afterService - lastActivityAt) >= REQUEST_STALL_MS) {
    failRequest(TIMEOUT, 408);
  }
  if (clientStage == NO_CLIENT && uint32_t(afterService - lastActivityAt) >= MODE_TIMEOUT_MS) {
    state = ERROR;
    error = TIMEOUT;
    emitStatus();
    Serial.println("[OTA] mode timed out; normal operation resumed");
    stopMode(false);
  }
}

#pragma once
#include <Arduino.h>
// XIAO ESP32C6 board labels; preserve existing D0 and SHT40 connections.
constexpr uint8_t PROBE_PIN=D0, SECOND_PROBE_PIN=D1;
constexpr uint8_t ROOM_SDA_PIN=D4, ROOM_SCL_PIN=D5;
constexpr uint8_t IR_RECEIVE_PIN=D6, IR_TRANSMIT_PIN=D7;
static_assert(IR_RECEIVE_PIN!=IR_TRANSMIT_PIN && SECOND_PROBE_PIN!=PROBE_PIN,"Distinct pins required");

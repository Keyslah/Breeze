#pragma once
#include <stddef.h>
#include <Arduino.h>

// Hardware-timed IR output. The RMT peripheral generates the carrier and every pulse width,
// so Bluetooth activity on this single-core chip cannot stretch, shorten or split a pulse.
class IrOutput {
 public:
  // A learned burst holds at most 768 timings; each symbol carries two.
  static constexpr size_t CAPACITY=448;
  static constexpr uint32_t MAX_TICKS=32767;
  explicit IrOutput(uint8_t pin):pin(pin){}
  // Alternating mark/space microseconds, starting with a mark. Returns the symbols written,
  // or 0 when the burst is empty or does not fit.
  static size_t pack(const uint16_t* timings,uint16_t count,rmt_data_t* symbols,size_t capacity){
    size_t used=0;bool second=false;
    for(uint16_t i=0;i<count;i++){
      uint32_t remaining=timings[i];const uint32_t level=(i&1)?0:1;
      // One half-symbol holds 15 bits; a longer mark or space continues at the same level.
      while(remaining){
        const uint32_t part=remaining>MAX_TICKS?MAX_TICKS:remaining;remaining-=part;
        if(!second){
          if(used==capacity)return 0;
          symbols[used].val=0;symbols[used].level0=level;symbols[used].duration0=part;second=true;
        }else{symbols[used].level1=level;symbols[used].duration1=part;second=false;used++;}
      }
    }
    // A burst ending on its first half keeps the zero second half: that is the end marker.
    return second?used+1:used;
  }
  // Take the pin with the LED dark. Safe to repeat.
  bool attach(){
    if(attached)return true;
    // One-microsecond ticks, so learned timings are replayed exactly as captured.
    if(!rmtInit(pin,RMT_TX_MODE,RMT_MEM_NUM_BLOCKS_2,1000000))return false;
    attached=true;carrier=0;
    if(!rmtSetEOT(pin,LOW)){release();return false;}
    return true;
  }
  // Blocks until the whole burst has left the LED. False means nothing reliable was sent.
  bool sendRaw(const uint16_t* timings,uint16_t count,uint16_t kilohertz){
    static rmt_data_t symbols[CAPACITY];
    const size_t used=pack(timings,count,symbols,CAPACITY);
    if(!used||!kilohertz||!attach())return false;
    if(carrier!=kilohertz){
      // false = modulate the high level: the LED pulses during marks and stays dark in spaces.
      if(!rmtSetCarrier(pin,true,false,uint32_t(kilohertz)*1000,0.5f)){release();return false;}
      carrier=kilohertz;
    }
    if(rmtWrite(pin,symbols,used,WRITE_TIMEOUT_MS))return true;
    release();return false;
  }
  // Hand the pin back so other code can drive it directly.
  void release(){if(attached)rmtDeinit(pin);attached=false;carrier=0;}
  bool active()const{return attached;}
 private:
  static constexpr uint32_t WRITE_TIMEOUT_MS=3000;
  uint8_t pin;
  bool attached=false;
  uint16_t carrier=0;
};

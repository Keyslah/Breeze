"""Compile the actual firmware temperature/ramp engine against numeric scenarios."""

from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
#include "LogicControl.h"
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstdint>

using namespace BreezeLogic;
#define CHECK(condition) do { if (!(condition)) { \
  std::fprintf(stderr, "FAIL: %s:%d: %s\n", __func__, __LINE__, #condition); \
  std::abort(); } } while (0)
float celsius(float f) { return (f - 32.0f) / 1.8f; }
bool near(float actual, float expected, float tolerance = .002f) {
  return std::isfinite(actual) && std::fabs(actual - expected) <= tolerance;
}
Decision feed(Engine& engine, uint32_t now, float bodyF, float roomF, uint8_t held = 0) {
  CHECK(engine.sample(now, celsius(bodyF), NAN));
  return engine.evaluate(now, celsius(roomF), held);
}
uint8_t table(const Profile& profile, float roomF, float bodyF) { return levelFor(score(profile, roomF, bodyF)); }
// One-second averaging and no steadiness margin make each sample decide by itself.
Profile direct() { Profile profile; profile.averageSeconds = 1; profile.steadinessCenti = 0; return profile; }

void defaults_and_range_table() {
  Profile profile;
  CHECK(valid(profile));
  CHECK(profile.schema == 3 && sizeof(profile) == 44);
  CHECK(profile.offRoomCentiF == 6800 && profile.offBodyCentiF == 9800);
  CHECK(profile.fullRoomCentiF == 7400 && profile.fullBodyCentiF == 9700);
  CHECK(profile.bodyWeightCenti == 100 && profile.steadinessCenti == 30);
  CHECK(profile.averageSeconds == 30 && profile.riseSeconds == 10 && profile.fallSeconds == 5);
  CHECK(!profile.boost.enabled && profile.boost.extra == 2);
  CHECK(profile.boost.triggerCentiF == 100 && profile.boost.windowSeconds == 30);
  CHECK(profile.boost.riseSeconds == 3 && profile.boost.holdSeconds == 120);
  CHECK(profile.boost.returnSeconds == 5 && profile.boost.curve == 1);
  CHECK(near(bodyWeight(profile), 1) && near(roomWeight(profile), 11.0f / 6));
  // The two chosen points: the Off edge and the first score that is H.
  CHECK(near(score(profile, 68, 98), 0) && near(score(profile, 74, 97), 10));
  const float rooms[] = {68, 70, 72, 74}, bodies[] = {94, 97, 99};
  const uint8_t expected[4][3] = {{0, 0, 1}, {0, 3, 5}, {4, 7, 9}, {7, 10, 10}};
  for (int room = 0; room < 4; room++)
    for (int body = 0; body < 3; body++)
      CHECK(table(profile, rooms[room], bodies[body]) == expected[room][body]);
  CHECK(table(profile, 68, 97.99f) == 0 && table(profile, 68, 98) == 0 && table(profile, 68, 98.01f) == 1);
  CHECK(table(profile, 74, 96) == 9 && table(profile, 74, 96.01f) == 10);
  CHECK(table(profile, 71, 96) == 4 && table(profile, 73, 99) == 10);
  // Probes left at room temperature read far below the Off point.
  CHECK(table(profile, 72.6f, 74.98f) == 0 && table(profile, 79, 75) == 0 && table(profile, 81, 75) == 1);
  CHECK(table(profile, 40, 120) == 0 && table(profile, 120, 60) == 10);

  Profile gentle = profile;
  gentle.bodyWeightCenti = 50;
  CHECK(valid(gentle) && near(roomWeight(gentle), 1.75f));
  const uint8_t gentleExpected[4][3] = {{0, 0, 1}, {2, 3, 4}, {5, 7, 8}, {9, 10, 10}};
  for (int room = 0; room < 4; room++)
    for (int body = 0; body < 3; body++)
      CHECK(table(gentle, rooms[room], bodies[body]) == gentleExpected[room][body]);
  Profile roomOnly = profile;
  roomOnly.bodyWeightCenti = 0;
  CHECK(valid(roomOnly) && near(roomWeight(roomOnly), 10.0f / 6));
  CHECK(table(roomOnly, 71, 60) == 5 && table(roomOnly, 71, 110) == 5);

  Engine engine;
  CHECK(engine.stepIntervalMs(true) == 1112 && engine.stepIntervalMs(false) == 556);
  CHECK(engine.stepIntervalMs(true) * 9 >= 10000 && engine.stepIntervalMs(false) * 9 >= 5000);
}

void steadiness_margin() {
  Profile profile;
  CHECK(steadyLevel(profile, 4.2f, 4) == 4 && steadyLevel(profile, 4.31f, 4) == 5);
  CHECK(steadyLevel(profile, 2.8f, 4) == 4 && steadyLevel(profile, 2.69f, 4) == 3);
  CHECK(steadyLevel(profile, -.2f, 1) == 1 && steadyLevel(profile, -.31f, 1) == 0);
  CHECK(steadyLevel(profile, .2f, 0) == 0 && steadyLevel(profile, .31f, 0) == 1);
  CHECK(steadyLevel(profile, 9.2f, 9) == 9 && steadyLevel(profile, 9.31f, 9) == 10);
  CHECK(steadyLevel(profile, 5.5f, 0) == 6 && steadyLevel(profile, 3.5f, 10) == 4);
  CHECK(steadyLevel(profile, 3.5f, 255) == 4 && steadyLevel(profile, 20, 3) == 10 && steadyLevel(profile, -20, 3) == 0);
  for (uint8_t held = 0; held <= 10; held++) CHECK(steadyLevel(profile, held - .5f, held) == held);
  profile.steadinessCenti = 0;
  for (float s = -1; s <= 11; s += .07f)
    for (uint8_t held = 0; held <= 10; held++) CHECK(steadyLevel(profile, s, held) == levelFor(s));
}

void inputs_and_average() {
  Engine engine;
  CHECK(!engine.evaluate(0, celsius(72)).ready); // No body reading yet: nothing to decide.
  CHECK(engine.sample(0, celsius(98), celsius(97), celsius(120)));
  CHECK(near(engine.averageF(0), 120)); // Every connected probe counts toward the highest.
  for (uint32_t now = 5; now < 1000; now += 5)
    CHECK(!engine.sample(now, celsius(60), celsius(60), celsius(60)));
  CHECK(near(engine.averageF(995), 120)); // Repeated loop ticks do not weight the average.
  CHECK(engine.sample(1000, celsius(100), celsius(97)));
  CHECK(near(engine.averageF(1000), 110));
  CHECK(engine.bodyAvailable(3500) && !engine.bodyAvailable(3501));
  Decision stale = engine.evaluate(3501, celsius(72), 7);
  CHECK(!stale.ready && std::isnan(stale.bodyF) && !stale.boost);
  CHECK(!engine.evaluate(1000, NAN, 7).ready);
  CHECK(!engine.evaluate(1000, -41, 7).ready && !engine.evaluate(1000, 126, 7).ready);

  engine.apply(direct());
  Decision d = feed(engine, 0, 96, 71);
  CHECK(d.ready && near(d.score, 3.5f, .01f) && d.baseline == 4 && d.target == 4 && !d.boost);
  CHECK(near(d.bodyF, 96) && near(d.roomF, 71));
  d = feed(engine, 1000, 74.98f, 72.6f, 4);
  CHECK(d.ready && d.target == 0); // The room no longer selects a rule that ignores the body.
  d = feed(engine, 2000, 97, 74, 0);
  CHECK(d.ready && d.target == 10);

  Profile minute;
  minute.averageSeconds = 60;
  engine.apply(minute);
  for (uint32_t second = 0; second < 60; ++second) {
    // The hottest probe changes halfway through. Averaging probes separately
    // then taking their maximum would incorrectly produce 98 rather than 100.
    CHECK(engine.sample(second * 1000,
      celsius(second < 30 ? 100 : 96), celsius(second < 30 ? 96 : 100)));
  }
  CHECK(near(engine.averageF(59000), 100));
  engine.apply(minute);
  CHECK(engine.sample(0, celsius(110), NAN));
  for (uint32_t now = 1000; now < 60000; now += 1000) feed(engine, now, 98, 71);
  CHECK(near(engine.averageF(59000), 98.2f));
  feed(engine, 60000, 98, 71);
  CHECK(near(engine.averageF(60000), 98)); // The oldest sample expires at exactly 60s.

  engine.apply(Profile{});
  CHECK(engine.sample(0, NAN, 81, celsius(101)));
  CHECK(near(engine.averageF(0), 101)); // Invalid inputs cannot override a valid D2.
  CHECK(engine.sample(1000, NAN, 81, NAN));
  CHECK(!engine.bodyAvailable(1000)); // Historical good data cannot conceal current loss.
  CHECK(!engine.evaluate(1000, celsius(72), 7).ready);

  Profile longAverage;
  longAverage.averageSeconds = 300;
  engine.apply(longAverage);
  for (uint32_t now = 0; now <= 605000; now += 1000) feed(engine, now, 99, 71);
  CHECK(near(engine.averageF(605000), 99, .01f)); // Full ring wrap preserves its time window.

  engine.apply(direct());
  const uint32_t beforeWrap = UINT32_MAX - 499;
  CHECK(engine.sample(beforeWrap, celsius(98), NAN));
  CHECK(!engine.sample(beforeWrap + 995, celsius(120), NAN));
  CHECK(engine.sample(beforeWrap + 1000, celsius(100), NAN));
  CHECK(near(engine.averageF(beforeWrap + 1000), 100));
}

void profile_validation_and_preservation() {
  Profile original;
  Profile metadata = original;
  metadata.revision += 1;
  metadata.checksum = 0x12345678;
  CHECK(sameSettings(original, metadata)); // Save bookkeeping cannot reset an unchanged engine.
  #define DIFFERS(statement) do { Profile changed = original; statement; CHECK(valid(changed) && !sameSettings(original, changed)); } while (0)
  DIFFERS(changed.offRoomCentiF = 6700);
  DIFFERS(changed.offBodyCentiF = 9750);
  DIFFERS(changed.fullRoomCentiF = 7500);
  DIFFERS(changed.fullBodyCentiF = 9800);
  DIFFERS(changed.bodyWeightCenti = 50);
  DIFFERS(changed.steadinessCenti = 0);
  DIFFERS(changed.averageSeconds = 60);
  DIFFERS(changed.riseSeconds = 20);
  DIFFERS(changed.fallSeconds = 20);
  DIFFERS(changed.boost.enabled = 1);
  DIFFERS(changed.boost.triggerCentiF = 40);
  DIFFERS(changed.boost.curve = 0);
  #undef DIFFERS

  Profile bounds = original;
  bounds.offRoomCentiF = -5000; bounds.fullRoomCentiF = 20000;
  bounds.offBodyCentiF = bounds.fullBodyCentiF = -400;
  bounds.bodyWeightCenti = 1000; bounds.steadinessCenti = 100;
  bounds.averageSeconds = 1; bounds.riseSeconds = bounds.fallSeconds = 1;
  bounds.boost.windowSeconds = 1; bounds.boost.riseSeconds = bounds.boost.returnSeconds = 1;
  bounds.boost.extra = 9; bounds.boost.holdSeconds = 3600;
  CHECK(valid(bounds));
  bounds.offBodyCentiF = bounds.fullBodyCentiF = 17600;
  bounds.averageSeconds = 300; bounds.riseSeconds = bounds.fallSeconds = 120;
  bounds.boost.windowSeconds = 300; bounds.boost.riseSeconds = bounds.boost.returnSeconds = 120;
  CHECK(valid(bounds));
  #define REJECT(statement) do { Profile invalid = original; statement; CHECK(!valid(invalid)); } while (0)
  REJECT(invalid.schema = 2);
  REJECT(invalid.magic = 0);
  REJECT(invalid.offRoomCentiF = -5001);
  REJECT(invalid.fullRoomCentiF = 20001);
  REJECT(invalid.fullRoomCentiF = 6899); // The two points need at least one room degree between them.
  REJECT(invalid.fullRoomCentiF = 6800);
  REJECT(invalid.offBodyCentiF = -401);
  REJECT(invalid.fullBodyCentiF = 17601);
  REJECT(invalid.bodyWeightCenti = 1001);
  REJECT(invalid.steadinessCenti = 101);
  // A Full body reading above the Off one is allowed only while the room still adds levels.
  REJECT(invalid.fullBodyCentiF = 10800; invalid.bodyWeightCenti = 100);
  REJECT(invalid.fullBodyCentiF = 10300; invalid.bodyWeightCenti = 200);
  REJECT(invalid.averageSeconds = 0);
  REJECT(invalid.averageSeconds = 301);
  REJECT(invalid.riseSeconds = 121);
  REJECT(invalid.fallSeconds = 0);
  REJECT(invalid.boost.enabled = 2);
  REJECT(invalid.boost.extra = 0);
  REJECT(invalid.boost.extra = 10);
  REJECT(invalid.boost.triggerCentiF = 0);
  REJECT(invalid.boost.windowSeconds = 301);
  REJECT(invalid.boost.riseSeconds = 0);
  REJECT(invalid.boost.holdSeconds = 3601);
  REJECT(invalid.boost.returnSeconds = 121);
  REJECT(invalid.boost.curve = 2);
  #undef REJECT
  Profile warmerFull = original;
  warmerFull.fullBodyCentiF = 10300;
  CHECK(valid(warmerFull) && near(roomWeight(warmerFull), 5.0f / 6));
  CHECK(near(score(warmerFull, 68, 98), 0) && near(score(warmerFull, 74, 103), 10));

  Engine engine;
  CHECK(engine.sample(0, celsius(98), NAN));
  CHECK(engine.sample(1000, celsius(100), NAN));
  Profile changed = original;
  changed.bodyWeightCenti = 50;
  engine.apply(changed, true);
  CHECK(near(engine.averageF(1000), 99) && engine.bodyAvailable(1000));
  CHECK(!engine.sample(1005, celsius(120), NAN)); // Applying must preserve admission timing too.
  engine.apply(changed, false);
  CHECK(std::isnan(engine.averageF(1000)) && !engine.bodyAvailable(1000));
}

Profile boosted() { Profile profile = direct(); profile.boost.enabled = 1; return profile; }
// Thirty-one settled seconds in a 72 F room: the range is steady and the boost becomes armed.
void settle(Engine& engine, uint32_t from = 0, float bodyF = 97) {
  for (uint32_t now = from; now <= from + 30000; now += 1000) CHECK(!feed(engine, now, bodyF, 72).boost);
}

void boost_cycle_and_current_baseline() {
  Engine engine;
  engine.apply(boosted());
  settle(engine);
  Decision d = feed(engine, 31000, 97.9f, 72);
  CHECK(!d.boost && d.baseline == 8); // A rise short of the full trigger only moves the range.
  engine.apply(boosted());
  settle(engine);
  d = feed(engine, 31000, 98.2f, 72);
  CHECK(d.boost && d.baseline == 8 && d.target == 8 && d.boostSeconds == 128);
  d = feed(engine, 32000, 98.2f, 72);
  CHECK(d.boost && d.target == 9 && d.boostSeconds == 127);
  feed(engine, 33000, 98.2f, 72);
  d = feed(engine, 34000, 98.2f, 72);
  CHECK(d.boost && d.target == 10 && d.boostSeconds == 125);
  for (uint32_t now = 35000; now <= 154000; now += 1000) {
    d = feed(engine, now, 97.5f, 72);
    CHECK(d.boost && d.baseline == 7 && d.target == 9);
    CHECK(d.boostSeconds == (159000 - now) / 1000);
  }
  feed(engine, 155000, 97.5f, 72);
  feed(engine, 156000, 97.5f, 72);
  d = engine.evaluate(156500, celsius(72));
  CHECK(d.boost && d.target == 8 && d.baseline == 7);
  feed(engine, 157000, 97.5f, 72);
  feed(engine, 158000, 97.5f, 72);
  d = feed(engine, 159000, 97.5f, 72);
  CHECK(!d.boost && d.target == 7 && d.baseline == 7); // Return to the current range level, not 8.

  engine.apply(boosted());
  settle(engine);
  CHECK(feed(engine, 31000, 98.2f, 72).boost);
  for (uint32_t now = 32000; now <= 158000; now += 1000) {
    d = feed(engine, now, now < 130000 ? 98.2f : 99.6f, 72);
    CHECK(d.boost && d.boostSeconds == (159000 - now) / 1000);
  }
  d = feed(engine, 159000, 99.6f, 72);
  CHECK(!d.boost); // A qualifying rise at expiry cannot immediately extend the cycle.
  d = feed(engine, 160000, 99.6f, 72);
  CHECK(!d.boost); // A level reading rearms but does not itself trigger.
  d = feed(engine, 161000, 100.7f, 72);
  CHECK(d.boost && d.boostSeconds == 128 && d.baseline == 10 && d.target == 10); // Capped at H.

  Profile off = direct();
  engine.apply(off);
  settle(engine);
  CHECK(!feed(engine, 31000, 99, 72).boost); // Disabled boost never starts.
}

void boost_ignores_rewarming_probes() {
  Engine engine;
  engine.apply(boosted());
  settle(engine);
  Decision d;
  // Out of bed: the probes cool to room temperature and the range reaches Off.
  for (uint32_t now = 31000; now <= 100000; now += 1000) {
    d = feed(engine, now, 75, 72);
    CHECK(!d.boost && d.target == 0);
  }
  // Back in bed: half a degree per second is far above the trigger the whole way up.
  bool followedRange = false;
  for (uint32_t now = 101000; now <= 144000; now += 1000) {
    const float body = 75 + (now - 100000) / 2000.0f;
    d = feed(engine, now, body, 72);
    CHECK(!d.boost && d.target == table(engine.profile, 72, body));
    if (d.target > 0 && d.target < 7) followedRange = true;
  }
  CHECK(followedRange && d.target == 7);
  // Settling at the top still must not look like an event.
  for (uint32_t now = 145000; now <= 176000; now += 1000) CHECK(!feed(engine, now, 97, 72).boost);
  d = feed(engine, 177000, 98.2f, 72);
  CHECK(d.boost && d.boostSeconds == 128); // A real rise from a settled reading still boosts.

  // A reading that fell while the fan stayed on has to level out before it can arm again.
  engine.apply(boosted());
  settle(engine, 0, 99);
  for (uint32_t now = 31000; now <= 40000; now += 1000) CHECK(!feed(engine, now, 96, 72).boost);
  CHECK(!feed(engine, 41000, 97.3f, 72).boost);

  // Turning on from Off in a cool room ramps through the range without a boost.
  engine.apply(boosted());
  for (uint32_t now = 0; now <= 30000; now += 1000) CHECK(feed(engine, now, 97, 68).target == 0);
  d = feed(engine, 31000, 99.5f, 68);
  CHECK(!d.boost && d.target == 2);

  // Losing either input ends an active boost instead of resuming it later.
  engine.apply(boosted());
  settle(engine);
  CHECK(feed(engine, 31000, 98.2f, 72).boost);
  CHECK(!engine.evaluate(31500, NAN, 8).ready);
  CHECK(!feed(engine, 32000, 98.2f, 72).boost);
}

void curve_choices() {
  CHECK(interpolate(-1, 0) == 0 && interpolate(2, 1) == 1);
  CHECK(near(interpolate(.25f, 0), .25f));
  CHECK(near(interpolate(.25f, 1), .15625f));
  Profile profile = boosted();
  profile.boost.extra = 4;
  profile.boost.curve = 0;
  Engine linear;
  linear.apply(profile);
  settle(linear, 0, 94);
  CHECK(feed(linear, 31000, 95.1f, 72).boost);
  CHECK(linear.evaluate(31750, celsius(72)).target == 6); // 5 + 4 x 0.25
  profile.boost.curve = 1;
  Engine smooth;
  smooth.apply(profile);
  settle(smooth, 0, 94);
  CHECK(feed(smooth, 31000, 95.1f, 72).boost);
  CHECK(smooth.evaluate(31750, celsius(72)).target == 6); // 5 + 4 x 0.156 rounds up from 5.6
  CHECK(smooth.evaluate(31500, celsius(72)).target == 5 && linear.evaluate(31500, celsius(72)).target == 6);
}

void boost_stays_at_or_below_h_through_every_phase() {
  for (int baseline = 7; baseline <= 10; ++baseline)
    for (int extra = 1; extra <= 9; ++extra)
      for (int curve = 0; curve <= 1; ++curve) {
        Profile profile = boosted();
        // Room alone fixes the ordinary level, while a genuine body rise triggers boost.
        profile.bodyWeightCenti = 0;
        profile.boost.extra = extra;
        profile.boost.curve = curve;
        profile.boost.riseSeconds = 3;
        profile.boost.holdSeconds = 4;
        profile.boost.returnSeconds = 5;
        CHECK(valid(profile));
        const float room = 68 + (baseline - .5f) * .6f;
        Engine engine;
        engine.apply(profile);
        for (uint32_t now = 0; now <= 30000; now += 1000) {
          const Decision d = feed(engine, now, 97, room);
          CHECK(d.baseline == baseline && d.target == baseline && !d.boost);
        }
        int previous = baseline;
        const int peak = baseline + extra > 10 ? 10 : baseline + extra;
        for (uint32_t elapsed = 0; elapsed <= 12000; elapsed += 100) {
          const uint32_t now = 31000 + elapsed;
          engine.sample(now, celsius(98.2f), NAN);
          const Decision d = engine.evaluate(now, celsius(room));
          CHECK(d.ready && d.baseline == baseline);
          CHECK(d.target >= baseline && d.target <= 10);
          CHECK(d.boost == (elapsed < 12000));
          if (elapsed <= 3000) CHECK(d.target >= previous);
          if (elapsed >= 3000 && elapsed <= 7000) CHECK(d.target == peak);
          if (elapsed >= 7000) CHECK(d.target <= previous);
          if (elapsed == 12000) CHECK(d.target == baseline);
          previous = d.target;
        }
      }
}

int main() {
  defaults_and_range_table();
  steadiness_margin();
  inputs_and_average();
  profile_validation_and_preservation();
  boost_cycle_and_current_baseline();
  boost_ignores_rewarming_probes();
  curve_choices();
  boost_stays_at_or_below_h_through_every_phase();
  std::puts("PASS: actual LogicControl.h continuous room/body range with the default Off/Full points and table, steadiness margin, highest-probe averaging, 1Hz admission, missing-input hold, profile validation/preservation, full-range timing, boost cycle/expiry/rearming, rewarming-probe suppression and curves; all boost extras and both curves stay at or below H through rise/hold/return from levels 7-H");
}
'''


def main():
    compiler_setup = Path(
        r"C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat"
    )
    if not compiler_setup.exists():
        raise SystemExit(f"Visual Studio compiler setup not found: {compiler_setup}")
    with tempfile.TemporaryDirectory(prefix="breeze-logic-test-") as directory:
        path = Path(directory)
        (path / "test.cpp").write_text(HARNESS, encoding="utf-8")
        (path / "run.cmd").write_text(
            '@echo off\n'
            f'call "{compiler_setup}" -arch=x64 -host_arch=x64 >nul\n'
            f'cl /nologo /EHsc /std:c++17 /I "{ROOT / "firmware/BreezeController"}" '
            'test.cpp /Fe:test.exe\n'
            'if errorlevel 1 exit /b 1\n'
            'test.exe\n',
            encoding="utf-8",
        )
        subprocess.run([str(path / "run.cmd")], cwd=path, check=True)


if __name__ == "__main__":
    main()

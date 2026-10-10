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
void seed(Engine& engine, float bodyF = 98, float roomF = 71) {
  for (uint32_t now = 0; now <= 30000; now += 1000) feed(engine, now, bodyF, roomF);
}

void defaults_and_boundaries() {
  Profile profile;
  CHECK(valid(profile));
  CHECK(profile.schema == 2);
  CHECK(profile.probeMask == 7 && profile.maximum == 9);
  CHECK(profile.roomCentiF == 7100 && profile.coolRoomCentiF == 7100 && profile.averageSeconds == 60);
  CHECK(profile.riseSeconds == 10 && profile.fallSeconds == 5);
  CHECK(profile.rules[0].minimum == 3 && !profile.rules[0].offBelow);
  CHECK(profile.rules[1].minimum == 1 && profile.rules[1].offBelow);
  for (const Rule& rule : profile.rules) {
    CHECK(rule.bodyCentiF == 9800 && rule.stepCentiF == 40);
    CHECK(!rule.boost.enabled && rule.boost.extra == 2);
    CHECK(rule.boost.triggerCentiF == 40 && rule.boost.windowSeconds == 30);
    CHECK(rule.boost.riseSeconds == 3 && rule.boost.holdSeconds == 120);
    CHECK(rule.boost.returnSeconds == 5 && rule.boost.curve == 1);
  }
  CHECK(normalTarget(profile, 0, 97, 0) == 3);
  CHECK(normalTarget(profile, 0, 98, 0) == 3);
  CHECK(normalTarget(profile, 0, 98.39f, 0) == 3);
  CHECK(normalTarget(profile, 0, 98.399f, 0) == 3);
  CHECK(normalTarget(profile, 0, 98.40f, 0) == 4);
  CHECK(normalTarget(profile, 0, 98.79f, 0) == 4);
  CHECK(normalTarget(profile, 0, 98.80f, 0) == 5);
  CHECK(normalTarget(profile, 0, 100.40f, 0) == 9);
  CHECK(normalTarget(profile, 0, 110, 0) == 9);
  CHECK(normalTarget(profile, 1, 97.99f, 7) == 0);
  CHECK(normalTarget(profile, 1, 98, 0) == 0);
  CHECK(normalTarget(profile, 1, 98, 7) == 7);
  CHECK(normalTarget(profile, 1, 98, 255) == 9);
  CHECK(normalTarget(profile, 1, 98.01f, 0) == 1);
  CHECK(normalTarget(profile, 1, 98.39f, 0) == 1);
  CHECK(normalTarget(profile, 1, 98.399f, 0) == 1);
  CHECK(normalTarget(profile, 1, 98.40f, 0) == 2);
  CHECK(normalTarget(profile, 1, 101.20f, 0) == 9);
  Engine engine;
  Decision missing = engine.evaluate(0, celsius(71));
  CHECK(missing.ready && missing.rule == 0 && missing.target == 3);
  CHECK(std::isnan(missing.bodyF) && !missing.boost);
  CHECK(!engine.evaluate(0, celsius(70), 7).ready);
  CHECK(engine.sample(0, celsius(97), NAN));
  Decision warm = engine.evaluate(0, celsius(71));
  Decision cold = engine.evaluate(0, celsius(70.99f));
  CHECK(warm.ready && warm.rule == 0 && warm.target == 3);
  CHECK(cold.ready && cold.rule == 1 && cold.target == 0);
  CHECK(engine.evaluate(0, celsius(70.999f)).rule == 1);
  CHECK(!engine.evaluate(0, NAN).ready);
  CHECK(!engine.evaluate(0, -41).ready);
  CHECK(!engine.evaluate(0, 126).ready);
  CHECK(engine.stepIntervalMs(true) == 1250);
  CHECK(engine.stepIntervalMs(false) == 625);
  CHECK(engine.stepIntervalMs(true) * 8 == 10000);
  CHECK(engine.stepIntervalMs(false) * 8 == 5000);
}

void sample_admission_and_average() {
  Engine engine;
  CHECK(engine.sample(0, celsius(98), celsius(97), celsius(120)));
  CHECK(near(engine.averageF(0), 120)); // All three connected probes are enabled by default.
  Profile twoProbes;
  twoProbes.probeMask = 3;
  engine.apply(twoProbes);
  CHECK(engine.sample(0, celsius(98), celsius(97), celsius(120)));
  CHECK(near(engine.averageF(0), 98)); // Explicitly disabling D2 removes it from control.
  for (uint32_t now = 5; now < 1000; now += 5)
    CHECK(!engine.sample(now, celsius(120), celsius(120), celsius(120)));
  CHECK(near(engine.averageF(995), 98)); // Repeated loop ticks do not weight the average.
  CHECK(engine.sample(1000, celsius(100), celsius(97)));
  CHECK(near(engine.averageF(1000), 99));
  CHECK(engine.bodyAvailable(3500));
  CHECK(!engine.bodyAvailable(3501));
  Decision stale = engine.evaluate(3501, celsius(71));
  CHECK(stale.ready && stale.target == 3 && std::isnan(stale.bodyF) && !stale.boost);
  CHECK(!engine.evaluate(3501, celsius(70), 7).ready);

  engine.apply(Profile{});
  for (uint32_t second = 0; second < 60; ++second) {
    // The hottest probe changes halfway through. Averaging probes separately
    // then taking their maximum would incorrectly produce 98 rather than 100.
    CHECK(engine.sample(second * 1000,
      celsius(second < 30 ? 100 : 96), celsius(second < 30 ? 96 : 100)));
  }
  CHECK(near(engine.averageF(59000), 100));
  CHECK(near(engine.evaluate(59000, celsius(71)).bodyF, 100));

  engine.apply(Profile{});
  CHECK(engine.sample(0, celsius(110), NAN));
  for (uint32_t now = 1000; now < 60000; now += 1000) feed(engine, now, 98, 71);
  CHECK(near(engine.averageF(59000), 98.2f));
  feed(engine, 60000, 98, 71);
  CHECK(near(engine.averageF(60000), 98)); // The oldest sample expires at exactly 60s.

  Profile threeProbes;
  threeProbes.probeMask = 7;
  engine.apply(threeProbes);
  CHECK(engine.sample(0, celsius(98), celsius(99), celsius(104)));
  CHECK(near(engine.averageF(0), 104));
  engine.apply(threeProbes);
  CHECK(engine.sample(0, NAN, 81, celsius(101)));
  CHECK(near(engine.averageF(0), 101)); // Invalid inputs cannot override a valid D2.
  CHECK(engine.sample(1000, NAN, 81, NAN));
  CHECK(!engine.bodyAvailable(1000)); // Historical good data cannot conceal current loss.
  Decision missing = engine.evaluate(1000, celsius(71));
  CHECK(missing.ready && missing.target == 3 && std::isnan(missing.bodyF) && !missing.boost);
  CHECK(!engine.evaluate(1000, celsius(70), 7).ready);
  engine.apply(threeProbes);
  CHECK(!engine.bodyAvailable(0) && std::isnan(engine.averageF(0)));

  Profile longAverage;
  longAverage.averageSeconds = 300;
  engine.apply(longAverage);
  for (uint32_t now = 0; now <= 605000; now += 1000) feed(engine, now, 99, 71);
  CHECK(near(engine.averageF(605000), 99, .01f)); // Full ring wrap preserves its time window.

  engine.apply(Profile{});
  const uint32_t beforeWrap = UINT32_MAX - 499;
  CHECK(engine.sample(beforeWrap, celsius(98), NAN));
  CHECK(!engine.sample(beforeWrap + 995, celsius(120), NAN));
  CHECK(engine.sample(beforeWrap + 1000, celsius(100), NAN));
  CHECK(near(engine.averageF(beforeWrap + 1000), 99));
}

void profile_validation_and_preservation() {
  Profile original;
  Profile metadata = original;
  metadata.revision += 1;
  metadata.checksum = 0x12345678;
  CHECK(sameSettings(original, metadata)); // Save bookkeeping cannot reset an unchanged engine.
  Profile changed = original;
  changed.rules[1].boost.enabled = 1;
  CHECK(!sameSettings(original, changed));
  changed = original;
  changed.probeMask = 3;
  CHECK(!sameSettings(original, changed));
  changed = original;
  changed.rules[0].bodyCentiF = 9810;
  CHECK(!sameSettings(original, changed));
  changed = original;
  changed.averageSeconds = 30;
  CHECK(!sameSettings(original, changed));
  changed = original;
  changed.coolRoomCentiF = 7000;
  CHECK(valid(changed) && !sameSettings(original, changed));

  Profile bounds = original;
  bounds.roomCentiF = bounds.coolRoomCentiF = -5000;
  bounds.rules[0].bodyCentiF = -400;
  bounds.averageSeconds = 1;
  bounds.riseSeconds = bounds.fallSeconds = 1;
  bounds.rules[0].boost.windowSeconds = 1;
  bounds.rules[0].boost.riseSeconds = bounds.rules[0].boost.returnSeconds = 1;
  CHECK(valid(bounds));
  bounds.roomCentiF = bounds.coolRoomCentiF = 20000;
  bounds.rules[0].bodyCentiF = 17600;
  bounds.averageSeconds = 300;
  bounds.riseSeconds = bounds.fallSeconds = 120;
  bounds.rules[0].boost.windowSeconds = 300;
  bounds.rules[0].boost.riseSeconds = bounds.rules[0].boost.returnSeconds = 120;
  CHECK(valid(bounds));
  #define REJECT(statement) do { Profile invalid = original; statement; CHECK(!valid(invalid)); } while (0)
  REJECT(invalid.roomCentiF = -5001);
  REJECT(invalid.roomCentiF = 20001);
  REJECT(invalid.coolRoomCentiF = -5001);
  REJECT(invalid.coolRoomCentiF = 7101);
  REJECT(invalid.schema = 1);
  REJECT(invalid.rules[0].bodyCentiF = -401);
  REJECT(invalid.rules[1].bodyCentiF = 17601);
  REJECT(invalid.averageSeconds = 0);
  REJECT(invalid.averageSeconds = 301);
  REJECT(invalid.riseSeconds = 121);
  REJECT(invalid.fallSeconds = 0);
  REJECT(invalid.rules[0].boost.windowSeconds = 301);
  REJECT(invalid.rules[1].boost.riseSeconds = 0);
  REJECT(invalid.rules[0].boost.returnSeconds = 121);
  REJECT(invalid.probeMask = 0);
  REJECT(invalid.probeMask = 8);
  REJECT(invalid.rules[0].minimum = 10);
  REJECT(invalid.rules[1].stepCentiF = 0);
  REJECT(invalid.maximum = 11);
  REJECT(invalid.rules[0].boost.extra = 10);
  REJECT(invalid.rules[1].boost.curve = 2);
  #undef REJECT

  // H is level 10 without changing the saved/default cap of 9 or its timing.
  Profile highest = original;highest.maximum=10;highest.rules[0].minimum=10;highest.rules[0].boost.extra=9;
  CHECK(valid(highest));CHECK(normalTarget(highest,0,110,0)==10);
  Engine highestEngine;highestEngine.apply(highest);
  CHECK(highestEngine.stepIntervalMs(true)==1112&&highestEngine.stepIntervalMs(false)==556);
  CHECK(sizeof(highest)==sizeof(original));

  Engine engine;
  CHECK(engine.sample(0, celsius(98), NAN));
  CHECK(engine.sample(1000, celsius(100), NAN));
  changed = original;
  changed.rules[0].minimum = 4;
  changed.rules[0].boost.enabled = 1;
  engine.apply(changed, true);
  CHECK(near(engine.averageF(1000), 99));
  CHECK(engine.bodyAvailable(1000));
  CHECK(!engine.sample(1005, celsius(120), NAN)); // Save must preserve admission timing too.
  Decision preserved = engine.evaluate(1000, celsius(71));
  CHECK(preserved.ready && preserved.target == 6 && !preserved.boost);
  engine.apply(changed, false);
  CHECK(std::isnan(engine.averageF(1000)) && !engine.bodyAvailable(1000));

  // Applying changed settings retains measured values but discards an old boost
  // and its comparison history; the previous rise must not retrigger instantly.
  changed.averageSeconds = 1;
  engine.apply(changed);
  seed(engine);
  CHECK(feed(engine, 31000, 98.4f, 71).boost);
  Profile replacement = changed;
  replacement.rules[0].minimum = 3;
  engine.apply(replacement, true);
  preserved = engine.evaluate(31000, celsius(71));
  CHECK(preserved.ready && near(preserved.bodyF, 98.4f) && !preserved.boost);
  CHECK(!feed(engine, 32000, 98.4f, 71).boost);
}

void boost_filter_and_independent_rules() {
  Profile profile;
  profile.rules[0].boost.enabled = 1;
  Engine filtered;
  filtered.apply(profile);
  for (uint32_t now = 0; now < 60000; now += 1000) feed(filtered, now, 98, 71);
  Decision d = feed(filtered, 60000, 98.8f, 71);
  CHECK(!d.boost); // A raw spike must first move the 60-second average.
  bool triggered = false;
  for (uint32_t now = 61000; now <= 90000; now += 1000) {
    d = feed(filtered, now, 98.8f, 71);
    if (d.boost) { triggered = true; break; }
  }
  CHECK(triggered); // Sustained warming eventually exceeds +0.4F over 30 seconds.

  profile.averageSeconds = 1;
  Engine warmOnly;
  warmOnly.apply(profile);
  seed(warmOnly, 98.1f, 70);
  d = feed(warmOnly, 31000, 98.6f, 70);
  CHECK(d.rule == 1 && !d.boost); // Enabling the warm rule does not enable the cold rule.
  d = feed(warmOnly, 32000, 98.6f, 71);
  CHECK(d.rule == 0 && d.boost);

  profile.rules[0].boost.enabled = 0;
  profile.rules[1].boost.enabled = 1;
  Engine coldOnly;
  coldOnly.apply(profile);
  seed(coldOnly, 98.1f, 71);
  d = feed(coldOnly, 31000, 98.6f, 71);
  CHECK(d.rule == 0 && !d.boost);
  d = feed(coldOnly, 32000, 98.6f, 70);
  CHECK(d.rule == 1 && d.boost);
  d = feed(coldOnly, 33000, 97, 70);
  CHECK(d.target == 0 && !d.boost); // The cold off threshold immediately wins.

  profile.rules[0].boost.enabled = 1;
  Engine both;
  both.apply(profile);
  seed(both, 98.1f, 71);
  CHECK(feed(both, 31000, 98.6f, 71).boostSeconds == 128);
  d = feed(both, 32000, 98.6f, 70);
  CHECK(d.boost && d.boostSeconds == 128);
  d = feed(both, 33000, 98.6f, 71);
  CHECK(!d.boost); // Leaving and reentering a rule cancels its previous boost.
}

void independent_room_thresholds_and_deadband() {
  Profile profile;
  profile.roomCentiF = 7200;
  profile.coolRoomCentiF = 7000;
  CHECK(valid(profile));
  Engine engine;
  engine.apply(profile);
  CHECK(engine.sample(0, celsius(98.1f), NAN));
  Decision d = engine.evaluate(0, celsius(69.9f), 6);
  CHECK(d.ready && d.rule == 1 && !d.hold && d.target == 1);
  const float roomCases[] = {70.0f, 71.0f, 71.9f, 71.999f};
  for (float roomF : roomCases) {
    d = engine.evaluate(0, celsius(roomF), 6);
    CHECK(d.ready && d.rule == 255 && d.hold && !d.boost);
    CHECK(d.target == 6 && d.baseline == 6);
  }
  d = engine.evaluate(0, celsius(72), 6);
  CHECK(d.ready && d.rule == 0 && !d.hold && d.target == 3);
  CHECK(!engine.evaluate(0, NAN, 6).ready);

  engine.apply(profile);
  d = engine.evaluate(0, celsius(71), 6);
  CHECK(d.ready && d.hold && d.rule == 255 && d.target == 6);
  d = engine.evaluate(0, celsius(72), 6);
  CHECK(d.ready && !d.hold && d.target == 3 && std::isnan(d.bodyF));
  CHECK(!engine.evaluate(0, celsius(69.9f), 6).ready);

  // Entering the gap does not clear/reseed measured temperatures or let the
  // same millisecond loop overweight the rolling body average.
  engine.apply(profile);
  CHECK(engine.sample(0, celsius(98), NAN));
  d = feed(engine, 1000, 100, 71, 6);
  CHECK(d.hold && near(d.bodyF, 99));
  CHECK(!engine.sample(1005, celsius(120), NAN));
  d = feed(engine, 2000, 100, 72, 6);
  CHECK(!d.hold && near(d.bodyF, 99.333333f));

  profile.averageSeconds = 1;
  profile.rules[0].boost.enabled = 1;
  engine.apply(profile);
  seed(engine, 98, 72);
  d = feed(engine, 31000, 98.4f, 72);
  CHECK(d.boost && d.boostSeconds == 128);
  d = feed(engine, 32000, 98.4f, 71, 6);
  CHECK(d.hold && d.target == 6 && !d.boost);
  d = feed(engine, 33000, 98.4f, 72, 6);
  CHECK(!d.hold && !d.boost); // An ongoing rise cannot resume the canceled boost.
  for (uint32_t now = 34000; now <= 61000; now += 1000) {
    d = feed(engine, now, 98.4f, 71, 6);
    CHECK(d.hold && !d.boost && d.target == 6);
  }
  d = feed(engine, 62000, 98.4f, 72, 6);
  CHECK(!d.boost); // History continued in the gap; flat temperature can now rearm.
  d = feed(engine, 63000, 98.8f, 72, 6);
  CHECK(d.boost && d.boostSeconds == 128); // A genuinely new rise starts a new boost.
}

void boost_cycle_and_current_baseline() {
  Profile profile;
  profile.averageSeconds = 1;
  profile.rules[0].boost.enabled = 1;
  Engine engine;
  engine.apply(profile);
  seed(engine);
  Decision d = feed(engine, 31000, 98.4f, 71);
  CHECK(d.boost && d.baseline == 4 && d.target == 4 && d.boostSeconds == 128);
  d = feed(engine, 32000, 98.4f, 71);
  CHECK(d.boost && d.target == 5 && d.boostSeconds == 127);
  feed(engine, 33000, 98.4f, 71);
  d = feed(engine, 34000, 98.4f, 71);
  CHECK(d.boost && d.target == 6 && d.boostSeconds == 125);
  for (uint32_t now = 35000; now <= 154000; now += 1000) {
    d = feed(engine, now, 98.8f, 71);
    CHECK(d.boost && d.baseline == 5 && d.target == 7);
    CHECK(d.boostSeconds == (159000 - now) / 1000);
  }
  feed(engine, 155000, 98.8f, 71);
  feed(engine, 156000, 98.8f, 71);
  d = engine.evaluate(156500, celsius(71));
  CHECK(d.boost && d.target == 6 && d.baseline == 5);
  feed(engine, 157000, 98.8f, 71);
  feed(engine, 158000, 98.8f, 71);
  d = feed(engine, 159000, 98.8f, 71);
  CHECK(!d.boost && d.target == 5 && d.baseline == 5); // Return to today's baseline, not 4.

  engine.apply(profile);
  seed(engine);
  CHECK(feed(engine, 31000, 98.4f, 71).boost);
  for (uint32_t now = 32000; now <= 158000; now += 1000) {
    d = feed(engine, now, now < 130000 ? 98.4f : 99.6f, 71);
    CHECK(d.boost && d.boostSeconds == (159000 - now) / 1000);
  }
  d = feed(engine, 159000, 99.6f, 71);
  CHECK(!d.boost); // A qualifying rise at expiry cannot immediately extend the cycle.
  d = feed(engine, 160000, 99.6f, 71);
  CHECK(!d.boost); // Flat temperature rearms but does not itself trigger.
  d = feed(engine, 161000, 100, 71);
  CHECK(d.boost && d.boostSeconds == 128); // A new warming event can trigger after rearming.
  for (uint32_t now = 162000; now <= 164000; now += 1000) d = feed(engine, now, 100, 71);
  CHECK(d.target == 9); // Baseline + boost is capped at the configured maximum.
}

void curve_choices() {
  CHECK(interpolate(-1, 0) == 0 && interpolate(2, 1) == 1);
  CHECK(near(interpolate(.25f, 0), .25f));
  CHECK(near(interpolate(.25f, 1), .15625f));
  Profile profile;
  profile.averageSeconds = 1;
  profile.rules[0].stepCentiF = 2000;
  profile.rules[0].boost.enabled = 1;
  profile.rules[0].boost.curve = 0;
  Engine linear;
  linear.apply(profile);
  seed(linear);
  CHECK(feed(linear, 31000, 98.4f, 71).boost);
  CHECK(linear.evaluate(31750, celsius(71)).target == 4);
  profile.rules[0].boost.curve = 1;
  Engine smooth;
  smooth.apply(profile);
  seed(smooth);
  CHECK(feed(smooth, 31000, 98.4f, 71).boost);
  CHECK(smooth.evaluate(31750, celsius(71)).target == 3);
}

int main() {
  defaults_and_boundaries();
  sample_admission_and_average();
  profile_validation_and_preservation();
  boost_filter_and_independent_rules();
  independent_room_thresholds_and_deadband();
  boost_cycle_and_current_baseline();
  curve_choices();
  std::puts("PASS: actual LogicControl.h independent room thresholds/deadband/canceled boosts, strict body thresholds, warmest-probe averaging, D2 selection, 1Hz admission, missing-body warm floor/cold hold, profile validation/preservation, full-range timing, boost expiry/rearming, current-baseline return and curves");
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

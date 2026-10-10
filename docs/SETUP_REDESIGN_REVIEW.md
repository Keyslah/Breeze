# Setup redesign and fan Logic investigation

Status: **investigation and proposals only; no implementation authorized.**
Recorded October 9, 2026. This is the current handoff; older validation and
troubleshooting sections describe earlier builds and observations.

## What the user wants

The user wants the entire Setup page reconsidered, not another small patch to
the existing long form. They find it difficult to understand and use, and the
fan's behavior does not match what they believe their settings request.

Investigate the implementation and propose a coherent replacement. Do not edit
application or firmware behavior, change live settings, send IR, install builds,
flash hardware, erase learned commands, or migrate user data during this review.
Produce a diagnosis, design alternatives, a recommendation, and the decisions
that need the user's approval. Do not start implementing the recommendation.

## Confirmed incident: checked body shutoff did not turn the fan off

The user enabled Logic on Home and expected the fan to turn off because they
were no longer lying on the probes and the readings were far below their body
threshold. The fan remained on at level 4.

Read-only observations from the connected phone/controller:

| Item | Observed value |
| --- | --- |
| Running firmware | 1.2.14 |
| Logic | Enabled; phone `homeLogicPaused=false` |
| Current fan estimate | 4, synchronized; user had last confirmed physical level 4 |
| Room temperature | About 72.6°F |
| Highest body rolling average | About 74.98°F |
| Warm room threshold | 73°F; Warm applies at or above this value |
| Cool room threshold | 72°F; Cool applies strictly below this value |
| Warm body threshold / off-below | 96°F / unchecked |
| Cool body threshold / off-below | 97.5°F / checked |
| Body averaging window | 30 seconds |
| Requested full-range rise / fall | 10 seconds / 5 seconds |
| Maximum speed / boost | 9 / disabled for both rules |
| Controller status | Between room rules; holding current setting; target 4 |
| IR transmissions since reboot | 0; no fan or command error reported |

### Confirmed cause

`BreezeLogic::Engine::evaluate` in `firmware/BreezeController/LogicControl.h`
chooses a room rule before evaluating its body threshold. A room temperature
between Cool and Warm returns `hold=true` immediately. `normalTarget`, which
evaluates `offBelow`, is never called in that gap.

`FanRemote::automaticTick` then holds the existing estimate and returns without
transmitting. The observed target 4 and zero transmissions match this path.
This incident is explained by rule precedence, not by missed IR or the new
one-second transmission spacing.

The gap behavior was previously requested explicitly and is covered by tests:
hold the current fan setting and cancel unfinished ramp/boost work. Setup also
states that the gap holds the current setting. The current complaint exposes a
conflict between that behavior and the user's interpretation of the body-off
checkbox. **The user has not decided which rule should take priority.** Do not
silently turn a per-room checkbox into a global override or remove the gap.

Being off the probes is the user's physical observation. The firmware does not
have a contact/presence detector; it has temperatures and an averaged signal.

## Current Setup inventory

`MainActivity.setup()` builds one scrolling page. Its first, large Logic card
contains:

- Save profile and Load profile at both top and bottom; profile name; save
  feedback; live Logic status; synchronization of the current fan speed.
- Highest-body explanation, optional D2 inclusion, averaging duration, rise and
  fall durations, and maximum fan setting.
- Separate Warm and Cool sections, each containing room/body thresholds,
  minimum or starting speed, degrees per speed step, the off-below checkbox,
  and boost configuration: extra levels, warming trigger, lookback window,
  rise/hold/return durations, and curve.
- The room-gap hold explanation after both sets of rules.

Below Logic are controller connection/disconnection and download/storage
controls, voice-during-recording settings, weather location, and sensor wiring.
Inspect those sections too when proposing the whole-page structure. Their
presence is not authorization to change their functionality.

## Other questions the investigation must resolve

1. **Rule precedence:** Should body-based Off override the room gap? Should it
   be global or remain per-room? With only Cool off-below checked, which body
   threshold applies in the gap? What happens at exact equality, with missing
   readings, during a boost, or when moving between room rules? Compare options
   explicitly rather than selecting a new policy silently.
2. **Explain the current decision:** Make it understandable why the fan is on,
   holding, ramping, off, paused, or waiting for synchronization. Distinguish raw
   probes, the highest-body rolling average, room temperature, active rule,
   target setting, and estimated current setting. Identify what can be explained
   with the existing protocol and what would require a later protocol change.
3. **Saving versus applying:** Autosave changes the active phone settings after
   about 600 ms or a flush. Named profiles are separate snapshots. Explicit
   Save/Load can apply settings and resume Logic; ordinary edits while paused
   can remain deferred on the phone. A local `Saved` label is not controller
   readback or proof of successful application. Propose clear names and states
   for phone-saved, controller-applied, pending, paused, and failed operations.
4. **Timing:** Full-range durations are requested schedules. Firmware 1.2.14
   enforces at least 1,000 ms after the latest completed IR transmission before
   another automatic action, and the learned burst itself takes time. A nominal
   five-second decrease cannot traverse eight or nine steps in five seconds.
   Propose an honest and understandable control for this constraint.
5. **Structure and progressive disclosure:** Which controls belong together,
   which are everyday controls, and which should be advanced? Propose a complete
   page hierarchy and plain-language labels, not just spacing/color adjustments.
   Avoid hiding essential behavior behind a large form or unexplained jargon.
6. **State and data preservation:** Define how a later redesign would preserve
   active and named profiles, controller rules, learned IR slots, recordings,
   history, graph choices, microphone-disable preference, and current estimates.
   Separate UI changes from deliberate rule changes and necessary migrations.

## Fan behavior that must be understood before proposing changes

- Settings are Off (0), 1–9, and H (internally 10). Manual `+` wraps H to 1;
  manual `-` wraps 1 to H. Automatic targets step toward their target rather
  than treating Off as another speed in the manual cycle.
- To reach Off from level 4, existing automatic control requests
  `4 → 3 → 2 → 1 → Off`, with the last action being Power. It is not an immediate
  Power toggle from every level. Describe alternatives only as proposals.
- After more than 60 seconds since the latest completed transmission, firmware
  sends the requested command twice, with a 300 ms gap. The second transmission
  updates the timestamp, but the estimate advances only once. There is no
  reliable mode tracking to select the five-second Sleep timeout.
- Firmware 1.2.13 restored the complete learned Symphony raw bursts instead of
  reconstructing shorter generic protocol bursts. Learned timings and carrier
  settings are retained. Firmware 1.2.14 added the automatic one-second floor
  for normal ramps, boost, Power transitions, and TARGET sequences. Manual
  direct commands and the 300 ms wake repeat are unchanged by that pacing fix.
- The user reported manual operation works with roughly one second between
  presses. Faster operation had intermittent misses. This is limited physical
  evidence, not proof that all IR reception problems are solved.
- There is no fan-state feedback. The displayed setting and display-sleep
  decision are estimates. ACKs/send counters prove software transmission
  attempts, not that the physical fan executed the command. Do not relabel the
  estimate as a measured setting or claim the display is known to be asleep.

## Source map

| File | What to investigate |
| --- | --- |
| `app/src/main/java/com/aaron/breeze/MainActivity.java` | `setup`, `logicCard`, `logicRule`, autosave, profile actions, current status text, Home Logic control |
| `app/src/main/java/com/aaron/breeze/BreezeService.java` | Home pause/resume, deferred changes, profile upload sequence/ACKs, received Logic status |
| `app/src/main/java/com/aaron/breeze/LogicProfile.java` | Phone schema, defaults, validation, serialization |
| `app/src/main/java/com/aaron/breeze/Settings.java` | Active profile versus named profile collection |
| `app/src/main/java/com/aaron/breeze/Protocol.java` | Profile writes and type-9 Logic status decoding |
| `app/src/main/java/com/aaron/breeze/FanSetting.java` | Off, 1–9, H representation |
| `firmware/BreezeController/LogicControl.h` | Room selection, body averaging, off-below, equality, boost, requested intervals |
| `firmware/BreezeController/FanRemote.cpp` | Staged profile commit, automatic actions, hold handling, estimates, actual transmission pacing |

Profile uploads consist of globals, two rules, two boosts, then commit. The
controller stages all parts before applying the profile; commit enables
automation. Type-9 status reports state/rule/target/averages/revision/errors,
but does not return the full profile or its phone-side name. Account for that
limit when proposing synchronization and saved/applied feedback.

Relevant tests include `LogicProfileTest`, `LogicProfileChecks`,
`NamedLogicProfileChecks`, `HomeMotionChecks`, `BleRecoveryChecks`,
`tests/logic_control_test.py`, and `tests/fan_automatic_interval_test.py`.

## Validation and reproduction boundaries

Firmware 1.2.14 compiled and was installed via the existing Wi-Fi updater.
Startup health confirmation passed (`rollbackPending=false`), all six learned
slots remained present (mask 63), and the estimate was synchronized to the
user-confirmed level 4 without emitting IR. Logic was left paused after that
flash; the user subsequently enabled it before the incident documented above.

The eight firmware host suites passed locally before deployment: IR, 432-case
first-send, NVS migration, Logic, third-probe records, telemetry cadence,
Symphony replay, and automatic-interval boundaries. These results do not
establish physical fan reception. The new interval suite checks 999/1000 ms
boundaries, nonzero transmission duration, wake pairs, timer rollover, power,
boost, longer profiles, and deadband cancellation.

Some existing tests require local evidence fixtures that are not published:
`ir_test.py` loads an earlier generated Logic header; `ir_nvs_migration_test.py`
uses a private NVS backup and a generated parser; `ir_symphony_replay_test.py`
shares those NVS dependencies. A fresh clone cannot run those three suites
without the fixtures. Do not publish the private NVS backup to solve that
problem: it also contains device/radio state. Propose sanitized fixtures as
separate future work if needed. Generated build directories and local logs are
not part of the source sync.

The host firmware tests also assume Windows and Visual Studio 2022 Community
C++ tools. Arduino configuration contains machine-specific tool paths; the
existing setup instructions explain adapting them. Source/build-script version
defaults are older than the deployed build: explicitly pass `-VersionPatch 14`
to `firmware/build-ota.ps1` when a later task authorizes rebuilding 1.2.14.

## Required review output

Deliver a grounded problem list with source references; a plain-language table
of current rule behavior and its surprising cases; two or three complete Setup
design alternatives with text wireframes; a recommended design and explicit
behavioral tradeoffs; and a staged implementation/validation plan for later
approval. Call out unresolved decisions rather than guessing. Keep the review
focused on Setup and its Logic interaction with Home. **Stop at the proposal.**

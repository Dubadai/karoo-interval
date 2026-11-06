# Indoor ERG: research and plan

**Status:** research only. No app code changed. UI below is a design note; mockups on the Karoo 3
frame come next, before any build.

**Goal (rider's words):** "If I loaded an interval session and I have a profile with GPS turned
off, and a trainer hooked up, that I can load a workout here, have it run ERG."

## What we found

### 1. The Karoo already controls the trainer

The Karoo itself runs smart trainers over **ANT+ FE-C**. Once a trainer is paired it adds a
trainer page to the Control Center (device shot: `device/01-karoo-trainer-control.png`):

- Header "Trainer 28436", mode card (**ERG** selected, 4 modes), target **120 W**, − / + buttons,
  Spin Down.
- Modes without a Karoo workout: **ERG** (target watts in 5 W steps), **Resistance** (% in 5 %
  steps), **Grade** (sim, 1 % steps), **Read-Only** (record power, don't control the trainer).
- With a Karoo workout loaded: **ERG** (follows the workout, ± 1 % trim) or **Read-Only**.
- Hammerhead's notes: control is **ANT+ FE-C only** (no Bluetooth FTMS control documented), and
  structured workouts only come from **TrainingPeaks** or **.FIT / .ZWO uploaded to the
  Dashboard**. Read-Only exists precisely "while another device or platform controls your
  trainer".

So the Karoo is not passive: in ERG mode with no workout it holds the trainer at its own target
(120 W in the shot). Anything we do has to coexist with that.

### 2. The rider's setup (from the Karoo's logs)

- Trainer: **Wahoo Fitness**, ANT+ device number **28436**, seen as two sensors: FE-C
  (`28436-17-5`, device type 17 = fitness equipment) and power (`28436-11-5`, type 11). Model
  number 40, software 0.7. The rider confirmed it is a **Wahoo KICKR CORE**. It does ANT+ FE-C
  and Bluetooth (FTMS on current firmware), so option C stays possible on the same trainer.
- Paired over ANT+, so the trainer's Bluetooth side is free.
- Installed on the Karoo: `com.dsi.ant.plugins.antplus` **3.7.00** (ANT+ Plugin Service),
  `com.dsi.ant.service.socket` 4.15.20 (ANT Radio Service). The Karoo's own sensor service uses
  its own ANT channels ("CustomAnt"), not the plugin service.

### 3. The SDK (karoo-ext 1.1.9)

- **No trainer, ERG or target-power API.** Nothing lets an extension tell the Karoo "set ERG to
  X W" or switch its trainer mode.
- `ActiveRideProfile` → `RideProfile.indoor` ("designated as indoor with no GPS"): our switch
  for "indoors only".
- `SavedDevices` event: the rider's paired sensors with `connectionType` (ANT_PLUS / BLE),
  `id`, `supportedDataTypes`. We can find the FE-C trainer and its ANT device number here, so
  **no scanning or pairing UI is needed** in our app.
- `RequestAnt` / `ReleaseAnt`, `RequestBluetooth` / `ReleaseBluetooth`: keep the radio on while
  we use it.
- Workout data types (`WORKOUT_POWER_TARGET`, `WORKOUT_PRIMARY_TARGET`, `WORKOUT_INTERVAL_COUNT`,
  `WORKOUT_REMAINING_INTERVAL_DURATION`, …): these describe **Karoo's own workout player**. We
  could read them, but there is no effect to skip, go back or pause Karoo's player.
- Extensions can also be **sensor providers** (`scansDevices`, `startScan`, `connectDevice`,
  emit `OnDataPoint`). Not needed for the recommended path; noted as a fallback (see option C).

### 4. Other extensions

No public Karoo extension controls a trainer (checked the awesome-karoo list and GitHub topics).
Workout+ shows targets only; karoo-wattspeed derives speed from power indoors. Extensions that
talk to hardware themselves use their own stack, not karoo-ext:

- Karoo-KRemote (ANT+ remotes): **ANT+ Plugin Service** (`AntPlusGenericControllableDevicePcc`),
  works on the Karoo without extra manifest permissions. Proves third-party ANT+ plugin access
  works on this device.
- karoo-wahoo-headwind (fan), veloVigil (Polar H10): plain Android BLE / vendor SDK, need
  `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` granted at runtime. veloVigil had to unpair the sensor
  from the Karoo first and fight bonding drops: BLE to a device the Karoo also uses is fragile.

## Options

| | How | Keeps our jumps / pause / screen | Effort | Risk |
| --- | --- | --- | --- | --- |
| **A. Karoo's player** | Put the workout on the Karoo (FIT/ZWO via Dashboard or TrainingPeaks), Karoo runs ERG, our screen reads `WORKOUT_*` | **No.** Karoo's player drives; we can't jump back, restart, or pause it | Low | Low. Built-in, Hammerhead's safety |
| **B. ANT+ FE-C from our app** (recommended) | Our app opens the trainer through the ANT+ Plugin Service (`AntPlusFitnessEquipmentPcc`) and sends target power; the Karoo stays paired for data, in **Read-Only** | Yes, all of it | Medium | Medium: two controllers if the Karoo isn't Read-Only |
| **C. BLE FTMS from our app** | Our app connects to the trainer's Bluetooth side (Wahoo supports FTMS on current firmware) and writes "Set Target Power" | Yes | Medium–high (permissions, bonding, reconnects) | Medium; same two-controller issue |

Option A is the zero-code fallback and worth knowing about, but it gives up exactly what
Interval Screen exists for (jumps both ways, restart, pause with time kept).

## Recommendation: B, ANT+ FE-C through the plugin service

Why:

- The trainer is already on ANT+, the plugin service is installed, and KRemote shows third-party
  plugin access works. ANT+ is broadcast: the trainer is the master, several displays can listen
  on their own channels and any of them can send control pages. The Karoo keeps recording power
  and cadence from its own channel, untouched.
- The trainer's ANT device number comes from `SavedDevices`, so the rider pairs once (in Karoo
  Sensors, as today) and our app just follows. No scan screen.
- FE-C page 49 "Target Power" has 0.25 W resolution, so ramps can be smooth (send ≥ 1 s apart,
  only when the target moves ≥ 2 W).

The one hard rule: **the Karoo must not also drive ERG.** In ERG mode with no workout the Karoo
holds its own target (e.g. 120 W). If it re-sends that target, the trainer flips between the two.
Rider sets the Karoo's trainer card to **Read-Only** (one tap in the Control Center), and the
app **tells them on the screen when it isn't** (see "Detecting that the Karoo isn't in
Read-Only" below). Open questions to test: does Read-Only stick between rides, and does the Karoo re-send its ERG target
periodically or only on change? If it only sends on change, our commands simply win after the
Karoo's last one, but we still ask for Read-Only.

Option C stays as the plan B if the FE PCC can't open the trainer while the Karoo holds its
channel, or for riders whose trainer is Bluetooth-only.

## Behaviour: what ERG does in each situation

ERG is **on** only when all are true: ride profile `indoor`, ERG enabled in Settings, a workout
loaded, trainer found in `SavedDevices` and the FE-C connection is up, ride recording.

| Situation | Trainer target |
| --- | --- |
| Interval running | `currentStep.targetAt(elapsed)` (ramps update each second) |
| Range step (e.g. 80–85 %) | Middle of the range (= `targetAt`, already the avg) |
| Jump countdown (5 s) | Stay on the current interval; switch when the jump lands |
| Jump lands / restart interval | New step's target right away |
| Workout paused (our ⏸) | Drop to a **recovery target** (setting, default 50 % FTP, ≥ 80 W) so the rider can spin easy |
| Ride paused (Karoo pause) | Same recovery target. Most trainers ease off anyway when cadence stops |
| **Resume** (our ▶ or the Karoo's) | **Soft start:** ramp from the recovery target up to the interval's target over **10 s** (linear, one step a second: 120 → 300 W goes up 18 W a second). If the target itself moves meanwhile (a ramp step), the soft start aims at the moving target. A jump or ⏸ during the soft start cancels it |
| Workout finished | Recovery target, then hand back: switch to Basic Resistance 0 % |
| Ride ends / workout unloaded / ERG switched off in Settings | Send Basic Resistance 0 %, close the channel |
| Outdoor profile | Never open the trainer |
| Target above a cap | Clamp to `min(plan max × 1.05, FTP × 2)`; never send > 1500 W |

Soft start is only after a pause (rider's request). Big steps inside the workout (e.g. 120 →
400 W at an interval change or a jump) are sent at once, as the workout says; Wahoo trainers
ramp the brake themselves. The soft start doesn't change the interval timer: it runs as normal
from the moment of resume.

## Detecting that the Karoo isn't in Read-Only

The SDK can't read the Karoo's trainer mode, so we ask the trainer instead. FE-C **page 71
(Command Status)** reports the *last control command the trainer received from anyone*: its
command ID, a sequence number and its data (e.g. target power). The ANT+ plugin exposes it
(`AntPlusFitnessEquipmentPcc` command-status request/event).

- **Before we send anything** (connected, ERG about to start): read page 71 twice, ~5 s apart.
  If the sequence number moved, or the last command is a target power / resistance / sim command
  we didn't send, another display is driving the trainer → the Karoo is in ERG, Resistance or
  Grade.
- **While we run ERG:** after each of our sends, page 71 should echo our sequence number and
  watts. If it shows a command that isn't ours (e.g. 120 W when we sent 250 W), the Karoo is
  fighting us.
- **Backup signal:** power settles at a steady value that isn't our target for 20 s+ with normal
  cadence.

What happens then: we **don't send targets** (no tug-of-war on the trainer) and the screen shows
the Read-Only notice (below) until page 71 goes quiet for ~10 s, then ERG starts by itself. We
recheck every 30 s during the ride, so switching the Karoo back to ERG mid-ride shows the notice
again.

Needs proving in test 3: that the KICKR CORE really reports other displays' commands on page 71,
and how often the Karoo sends when it's in ERG. If the Karoo only sends on change, the "before"
check can miss it, and the "while" check (echo mismatch) catches it at its next send.

## Safety and failure modes

ERG on FE-C is "last command wins and stays": if our app dies mid-interval, the trainer keeps
the last target. Mitigations, in order of importance:

1. **Rider can always take over:** stop pedalling (the brake releases as the flywheel stops), or
   open the Karoo Control Center and switch from Read-Only to Resistance or ERG. The Karoo then
   controls it. Document this in the guide.
2. **Hand back on every exit path:** ride end, unload, Settings off, outdoor profile, extension
   `onDestroy`, and a default uncaught-exception handler all send Basic Resistance 0 %.
3. **Hard cap** on any target sent (above).
4. **Link watchdog:** if the plugin reports the device dead/searching, show "ERG lost" on the
   screen and keep retrying with backoff; never queue stale targets, send the *current* one when
   the link returns.
5. **Command check:** request FE-C page 71 (command status) after each send; if the trainer
   hasn't accepted it within ~3 s, resend once, then show "ERG ✕".
6. **Conflict detection:** see "Detecting that the Karoo isn't in Read-Only". On a conflict we
   stop sending and tell the rider, rather than fighting the Karoo.
7. No high targets while not recording or in the first 3 s after start; the step's own target
   is used (warm-up is usually low anyway).

## What the rider sees (design note, to be mocked up on Karoo 3 frames)

**Interval screen** (all watts modes, light and dark):

- A small **ERG pill** next to the target tile label, e.g. `ERG 250 W`, in the tonal style of the
  existing tiles. States:
  - `ERG` green-tonal: trainer is following the target.
  - `ERG …` grey: connecting / waiting for the trainer.
  - `ERG ✕` amber: lost the trainer or commands not accepted; tap for a one-line reason
    ("Trainer not found").
  - `ERG ⚠` amber + notice: **the Karoo is controlling the trainer** (not in Read-Only).
  - Hidden outdoors and when ERG is off.
- **Read-Only notice** (when the Karoo isn't in Read-Only): a yellow box in the same place and
  style as "Workout paused", e.g. **"Karoo is controlling the trainer"** / "Swipe down → trainer
  → Read-Only". It stays until the conflict clears, then ERG starts and the box goes. Also an
  `InRideAlert` the first time it happens in a ride, so the rider sees it even on another page.
  Wording to settle in the mockup.
- When paused: the yellow "Workout paused" box gets a second line "ERG at 120 W" (the recovery
  target).
- Jump countdown: no change (ERG switches when the jump lands, like the timer).

**Settings page**, new section **Trainer (indoors)** under Karoo style:

- **ERG on indoor rides**: On / Off (default Off until tested).
- **Trainer**: read-only line showing the FE-C trainer from Karoo's sensors ("Wahoo · 28436"),
  or "No ANT+ trainer paired. Pair it in Karoo Sensors".
- **Recovery watts** (paused / finished): 50 % FTP (stepper).
- A one-line hint: "Set the Karoo's trainer card to Read-Only so only this app sets ERG."

Mockups (Karoo 3 frame, light + dark): ERG pill states on the main screen and gauge layout,
paused box with ERG line, Settings section. Need an OK before building.

## What to test on the rider's trainer

Trainer: **Wahoo KICKR CORE** (ANT+ 28436). Check its firmware in the Wahoo app before testing.

1. Plugin access: `AntPlusFitnessEquipmentPcc.requestNewPluginAccess` with device 28436 while
   the Karoo is connected. Does it open? Does the Karoo's power/cadence keep flowing?
2. Set 150 W from a debug broadcast (`DEBUG_REMOTE`-style) with the Karoo in **Read-Only**: does
   the trainer hold 150 W?
3. Same with the Karoo in **ERG 120 W**: who wins, does it flip back (= does the Karoo re-send)?
   Read page 71 with the Karoo in each mode (ERG, Resistance, Grade, Read-Only): can we tell
   Read-Only from the others? This decides how the Read-Only notice works.
4. Does Read-Only survive leaving the Control Center, a ride restart, a Karoo reboot?
5. Ramp: 150 → 300 W over 60 s at 1 Hz updates. Smooth?
   Soft start: pause at 300 W (drops to 120 W), resume: 120 → 300 W over 10 s, no jolt?
6. Kill the app mid-interval (`adb shell am force-stop`): trainer stays at last target? Can the
   Control Center take over right away?
7. Pull the trainer's power for 10 s: plugin states, reconnect time, ERG resumes?
8. Basic Resistance 0 % hand-back: does the trainer free up, does the Karoo still record?
9. Outdoor profile with trainer powered on: app never opens the channel.

All tests on a pedalling rider at low targets first (≤ 200 W), never install mid-ride.

## Build plan

1. **Spike (no UI):** add the ANT+ plugin library (`antpluginlib` 3.x, `com.dsi.ant:antplus`), a
   `TrainerLink` class: find the FE-C device in `SavedDevices`, `RequestAnt`, open the FE PCC,
   `setTargetPower(w)`, `releaseToResistance0()`, connection state flow. Debug receiver to set
   watts by `adb`. Run tests 1–4, 6–8. Go / no-go for option B (else option C spike with FTMS).
2. **ErgController:** combines `ActiveRideProfile.indoor`, `RideState`, the Settings switch and
   `WorkoutSession.state` into one desired target (table above), rate-limits sends (≥ 1 s, ≥ 2 W
   change, resend every 10 s as a keep-alive), clamps, and owns all hand-back paths. Unit tests
   for the target table (pause, soft start after resume, jumps, ramps, finish, outdoor).
3. **Safety:** uncaught-exception hand-back, watchdog, page-71 command check, Read-Only
   detection (don't send while the Karoo drives; recheck every 30 s).
4. **Design:** mockups of the ERG pill states, the Read-Only notice and the Settings section on Karoo 3 frames, A/B where
   there's a choice. Wait for OK.
5. **UI build:** `ScreenState` gets `erg: ErgStatus?`; ERG pill in `ScreenRenderer` (both
   layouts, Karoo style on/off); Settings section in `SettingsActivity` + `PlanStore`.
6. **Device test:** full workout on the trainer: ramps, jumps back and forward, restart, pause,
   finish, force-stop. Device screenshots (`device/NN-what-light|dark.png`) here.
7. **Guide:** `docs/guide/` page "Indoor ERG": set Karoo trainer to Read-Only, turn on ERG,
   what the pill means, how to take over if something goes wrong.

## Spike results (2026-11-06, branch `indoor-erg-spike`)

Code (removed again after this result, kept in the branch history): `TrainerLink.kt` (ANT+ plugin, FE-C) and the debug-only `DebugTrainerReceiver`
(`adb shell am broadcast -a com.daviddokakis.karoointerval.DEBUG_TRAINER -p com.daviddokakis.karoointerval --es cmd …`,
log tag `ErgSpike`). ANT+ Plugin library 3.9.0 from ant-wireless/ANT-Android-SDKs in `app/libs/`
(ANT+ Shared Source License, `app/libs/ANT-LICENSE.txt`). No watts were ever sent.

1. **Finding the trainer works.** `SavedDevices` lists it as `id=28436-17-5`, `ANT_PLUS`,
   "Trainer 28436", Wahoo Fitness, `TYPE_TRAINER_ID`. The device number is the first part of the
   id, so the app can pick the trainer by itself. (There's also an older, disabled
   "Trainer 30332".)
2. **The ANT+ Plugin Service can't receive on this Karoo.** `requestNewOpenAccess` for FE-C 28436
   → `SEARCH_TIMEOUT` (twice). A plain power probe for the same trainer (device type 11)
   → `SEARCH_TIMEOUT` too. The radio log shows the plugin opening its channel correctly (device
   0x6F14, type 0x11, 57 MHz, 4 Hz, network 1) but it never receives a message, while the Karoo's
   own channels 4 and 7 receive the trainer at 4 Hz the whole time. The plugin also logs
   "Could not set event buffer". Most likely Hammerhead's own ANT stack (`com.dsi.ant.server`,
   "CustomAnt") sets up the radio's networks its own way, so the plugin's ANT+ network doesn't
   work. **Option B as planned (ANT+ plugin) is a no-go on this Karoo.**

Next candidates:
- **C, Bluetooth FTMS** (recommended): the KICKR CORE's Bluetooth side is free (the Karoo uses
  ANT+). Needs `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` granted once ("Nearby devices"), scan for
  the trainer's FTMS service, Request Control, Set Target Power. Read-Only detection then comes
  from the trainer's FTMS status notifications (whether a KICKR CORE reports ANT-side target
  changes there is the thing to test) plus the power-vs-target check.
- B2, raw ANT channel through AntLib on the ANT+ network: likely hits the same network problem;
  only worth a quick try if FTMS fails.

### Bluetooth FTMS (option C), first results

Code: `FtmsLink.kt` (plain Android BLE, FTMS 0x1826), driven by the same debug receiver
(`--es cmd blescan | bleconnect --es addr … | blecontrol | blewatts --ei w … | bleramp … | blereset | bleclose`).
Permissions `BLUETOOTH_SCAN` (neverForLocation) + `BLUETOOTH_CONNECT` in the manifest; granted
with `adb shell pm grant` for the test (the real app asks once).

1. **Scan:** "KICKR CORE 6F14" (E9:04:D3:0C:93:5D) advertises FTMS. 0x6F14 = 28436, the ANT+
   device number in the Karoo's saved sensors, so the app can match the Bluetooth trainer to the
   one paired to the Karoo without a picker.
2. **Connect:** FTMS characteristics 2ACC, 2AD3, 2AD9, 2ADA, 2AD2, 2AD6, 2AD8. Feature: target
   power supported. Power range 0–2000 W, 1 W steps. Indoor Bike Data streams power and cadence.
3. **Request Control:** `SUCCESS`, while the Karoo stayed connected over ANT+ (its trainer
   and power channels kept receiving). Reset (release control): `SUCCESS`, then disconnected.

Not tested yet (needs the rider pedalling): who wins with the Karoo in ERG, whether the
trainer reports the Karoo's ANT-side target changes as FTMS status "target power changed"
(this decides the Read-Only warning), holding 150 W with the Karoo in Read-Only, the 10 s soft
start (120 → 200 W), and hand-back.

## Mockups, round 1

**Decided:** ERG pill goes in the **top row, next to the gear (A)**, on both the classic and the
gauge layout. Read-Only notice (in the Next up box), "PAUSED · ERG 120 W" paused box and the Settings
"Trainer (indoors)" section: **approved as shown**. Soft start after a pause added (no UI change).

`preview.html` (built from `preview.src.html` by `build.py`, on real Karoo 3 screenshots in
`base-*.png`, Karoo's floating back / play buttons removed):

- **Where the ERG pill goes:** A, top row next to the gear; B, interval row after
  "INTERVAL 1 / 16". Classic and gauge.
- **Pill states** (light + dark): `● ERG` green (following), `ERG …` grey (connecting),
  `ERG ✕` amber (trainer lost), `ERG !` amber (Karoo is controlling).
- **Karoo isn't in Read-Only:** the Next up box turns into a yellow notice, "KAROO IS
  CONTROLLING THE TRAINER / Swipe down → Trainer → Read-Only", until the conflict clears. Jumps
  still work from the remote meanwhile. Light, dark with a route, gauge.
- **Workout paused:** the yellow box reads "PAUSED · ERG 120 W" (the recovery watts).
- **Settings:** new "Trainer (indoors)" section under Karoo style: Off / ERG on, the trainer found
  in the Karoo's sensors, "When paused" stepper (50 % FTP · 120 W), Read-Only hint.

## Files

- `device/01-karoo-trainer-control.png`: Karoo's own trainer card in the Control Center (ERG,
  120 W, − / +, Spin Down), trainer connected over ANT+.

## Sources

- Hammerhead: [Karoo OS – Smart Trainer Integration](https://support.hammerhead.io/hc/en-us/articles/25559624673179-Karoo-OS-Smart-Trainer-Integration)
- [awesome-karoo](https://github.com/timklge/awesome-karoo) extension list
- [Karoo-KRemote](https://github.com/lockevod/Karoo-KRemote) (ANT+ plugin use on Karoo),
  [karoo-wahoo-headwind](https://github.com/vvrnv/karoo-wahoo-headwind),
  [velovigil-karoo](https://github.com/velovigil/velovigil-karoo) (BLE on Karoo)
- karoo-ext 1.1.9 source (vendored): `models/KarooEvent.kt` (`SavedDevices`, `ActiveRideProfile`),
  `models/KarooEffect.kt` (`RequestAnt`), `models/DataType.kt` (workout types),
  `extension/KarooExtension.kt` (sensor provider hooks)
- Bluetooth SIG FTMS spec (Request Control / Set Target Power) for option C

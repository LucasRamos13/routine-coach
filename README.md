# Routine Coach

Offline-first Android app that guides home workouts, logs sets in seconds and reschedules your training plan
automatically — always explaining why, and always undoable.

- **Guided sessions**: warm-up, supersets with automatic advance, rest/tempo/stretch timers with sound and vibration
  that keep running with the screen off, exercise cards with steps, tips and illustrations.
- **Fast logging**: reps (or seconds), variation, reps in reserve and pain, pre-filled from last time — one tap when
  nothing changed.
- **Automation with limits**: missed sessions move to the next valid slot, poor sleep switches to the short
  version, pain swaps in recovery work, loads progress with double progression, deloads trigger on fatigue.
  Every change shows its reason and data, can be undone for 24 h and is kept in a history. Pause mode turns it off.
- **Your data stays on the phone**: Room database, CSV/JSON export and an AES-encrypted backup you can restore on
  another device.
- **Reminders on three channels**: exact local notifications on the phone, then a Telegram bot and desktop
  notifications (Linux and Windows) through an [ntfy](https://ntfy.sh) relay — one shared state, so answering on one
  channel clears the others.

> Routine Coach is general information, not medical advice. Warning signs (chest pain, dizziness, sharp pain) open a
> stop screen instead of rescheduling anything.

## Two layers: public foundation, private pack

```
routine-coach (this repo)              your machine only
├─ core/      rules engine, pack       ┌─────────────────────────────┐
│             schema + validator,      │ private-pack/               │
│             illustration renderer    │   pack.json  plan.json      │
├─ app/       Android app (Compose)    │   exercises/*.json          │
├─ tools/     CLI: validate, render    └──────────────┬──────────────┘
├─ content/                                           │ build injection
│  ├─ library/    generic exercises + SVGs            ▼
│  └─ demo-pack/  demo routine           app/build → assets/pack + assets/library
└─ companion/ desktop notifier + Telegram bridge
```

The foundation runs on its own with the demo routine. Your personal routine is a **content pack** kept outside the
repository and injected at build time; training data lives only in the app's local database. A pre-commit hook and
CI block commits with tokens or personal data (see [docs/privacy.md](docs/privacy.md)).

## Build

Requirements: JDK 17 and the Android SDK (platform 35).

```bash
# Demo build
./gradlew :app:assembleDebug

# Build with your private pack (path outside the repo), via local.properties (git-ignored):
echo "routineCoach.packDir=/home/me/routine/private-pack" >> local.properties
./gradlew :app:installDebug
```

The build validates the pack first and fails with `file:line [field] message` for any error. You can run the
validator yourself:

```bash
./gradlew :tools:installDist
tools/build/install/routine-coach-tools/bin/routine-coach-tools validate --pack /path/to/pack --library content/library
```

minSdk is 26 (Android 8.0); target is 35.

## Content packs

A pack is a folder with `pack.json`, `plan.json` and optional `exercises/` and `illustrations/`. Pack exercises can
`extend` a library exercise to translate it or change details while reusing its illustrations. Texts inside a pack
are data and can be in any language. See [docs/pack-format.md](docs/pack-format.md).

## Automation

The rules, their thresholds and safety limits are documented in [docs/automation-rules.md](docs/automation-rules.md).
Every rule has normal and edge-case tests in `core/src/test`.

```bash
./gradlew :core:test
```

## Reminders, Telegram and desktop

See [docs/notifications.md](docs/notifications.md) to set up the ntfy relay, the desktop notifier on Linux
(systemd user service) or Windows, and the Telegram bridge.

## Illustrations

Exercise illustrations are generated from joint angles so they share one style, stay under 20 KB and work offline.
See [docs/illustrations.md](docs/illustrations.md). Every illustration needs a posture review before being marked
`"review": "approved"`; the validator warns about pending ones.

## Requirements coverage (MVP + notification channels)

| ID | Where |
|---|---|
| PL-01 plan from pack, errors with file/line/field | `core/.../pack/PackLoader.kt`, `PackErrorScreen` |
| PL-02 full/short version, toggle before or during | Today card, session menu |
| PL-03 phases and programmable deload | `PlanTimeline`, `Prescriber`, calendar "deload week" |
| PL-04 manual calendar edits never undone | Calendar screen, `manual` flag respected by `Rescheduler` |
| EX-01 exercise card offline | `ExerciseScreen`, bundled SVGs |
| EX-02 rest, tempo and stretch timers with screen off | `TimerService` (foreground + wake lock) |
| EX-03 supersets with automatic advance | `SessionViewModel.buildSteps` |
| EX-04 skip/substitute with reason | session screen, `exercise_event` table |
| RG-01/02/03 set logging, edit/delete, body data | session screen, history, `BodyLogDialog` |
| MT-01 weekly sets per muscle vs target | Progress |
| MT-03 7-day moving average weight | Progress, `Metrics.movingAverage` |
| DT-01 CSV/JSON export, encrypted backup | Settings › Your data, `BackupManager` |
| DT-02 source on every data point | `source` columns (`manual`, `health_connect`) |
| Notifications: phone → bot/PC, single state | `ReminderScheduler`, `Relay`, `companion/` |

Planned for v2: exercise trend charts (MT-02), flexibility tests (MT-04) and Health Connect import.

## License

MIT — see [LICENSE](LICENSE) and [ATTRIBUTIONS.md](ATTRIBUTIONS.md).

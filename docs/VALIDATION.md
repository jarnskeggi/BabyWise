# Validation record

Validated on 21 September 2026 using disposable Android emulators on Windows.

| Check | Result |
| --- | --- |
| JVM domain and CSV tests | 25 passed, zero skipped, including reduced per-profile Nara headers, nearest-minute rounding and minute-only completed-duration display |
| Android 8 / API 26 device suite | 8 passed before the timer-screen regression test was added |
| Android 15 / API 35 device suite | 10 passed after the family-flow update; staged reboot recovery skipped by design |
| Airplane mode, 150% font scale, notifications denied on API 35 | All 8 device tests passed together under these conditions; home screenshot visually checked |
| Timers with notifications denied | Simultaneous feeding and mom sleep started successfully on API 35 |
| Android 17 / API 37.0 | App launched, but suite blocked by emulator surfaceflinger/system-server crashes; compatibility not verified |
| Supplied private CSV | All 8,370 rows, 14 types, 70 columns and 410 shared pumping rows preserved |
| Room import/export of supplied CSV | Every field compared; repeated import added no duplicates |
| Backup | Photos, settings and paused timer recovery passed; corrupt ZIP left database intact |
| Recovery on API 35 | Simultaneous baby feeding and mom sleep survived screen lock, force-stop and reboot; side switching worked after recovery |
| Android lint | Zero errors; warnings report newer dependency versions |
| Release | APK signature verified for minimum API 26; signed APK installed and launched on API 35 in airplane mode |

The source tests cover malformed CSV, quotes, commas, multiline notes, Unicode, missing and unfamiliar fields, conflicting IDs, timestamp formats, monotonic/reboot clocks, midnight and daylight-saving splits, pause segments, unit conversion, age alignment, and WHO LMS calculations. Device tests exercise actual Compose navigation and diaper creation as well as storage and timers.

Entry-screen regression tests confirm a left breastfeeding timer remains visible and can be saved, and that an existing completed sleep entry saves a changed duration.

Private exports were reviewed locally during development and are excluded from the source archive and APK. Automated tests use synthetic families. Test instrumentation clears records and must only run on disposable test devices.

Final visual review at 150% text size found crowded multiline welcome text. Its line height was corrected before the final release build.

Nara's own importer has not been tested. Adult CSV representation is a BabyWise convention. No physical phone or manufacturer-specific battery manager has been tested.

Android 17 emulator 37.1.11 on this host repeatedly aborted in `mapper.ranchu.so` / `surfaceflinger` and later `system_server`, including outside app tests. Software renderer and DMA/navigation workarounds did not produce a stable test run. The app launched, but Android 17 compatibility remains unverified. AndroidX test libraries were updated to avoid their obsolete InputManager reflection path.

## Reproduce recovery test

Install the debug and instrumentation APKs on a disposable emulator. Run `adb shell am instrument -w -e class family.babywise.RecoveryTest -e recoveryPhase seed family.babywise.test/androidx.test.runner.AndroidJUnitRunner`. Lock the screen, force-stop `family.babywise`, then reboot the emulator. Once booted, rerun with `-e recoveryPhase verify`. Normal suite runs skip this staged test.

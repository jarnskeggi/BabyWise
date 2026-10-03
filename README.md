# BabyWise

An offline Android family journal, built with Kotlin, Jetpack Compose, Room and DataStore. Android 8.0 (API 26) or newer. There are no accounts, analytics, Internet permission or automatic cloud backups.

## Install

Download the current APK from the project's GitHub Releases page, copy it to your phone, open it, and allow installation from that file manager when Android asks. Start by adding profiles or choosing **Family → Import Nara CSV**. Your supplied family data is **not** bundled into the APK.

Updates must use the same signing key and application ID. Keep the entire ignored `.signing` folder in a safe private location. It contains the release key and its password; losing it prevents in-place updates signed with that identity. Never commit or share it with the APK.

## Using the app

- Tap the profile name to switch between children and mom. Each has an independent history. Pumping records are shared family records.
- Use the plus button on an activity card to start a timer or enter a completed activity. Breastfeeding and combination feeds support switching sides. Bottle sessions can be timed, then edited to add milk quantities.
- Active sessions survive profile changes and process termination. Use their cards or notifications to pause, resume, switch sides, or stop and save. Android force-stop prevents notifications until the app is reopened; elapsed state is recovered then.
- History provides lists, a weekly timeline, searchable notes, photo history, and calendar-date or same-age comparison. Use **Jump to date** to reach older imported records. Search and note/photo filters search across dates.
- Trends break feeding, sleep, diapers and pumping into individual measures. Choose 1, 7, 14 or 30 days to compare each measure with the immediately preceding period, then tap a card for its graph, calendar and matching entries. Durations are split at midnight and profile-specific nighttime boundaries. Growth references are available for supported ages and known sex/birth date.
- Family contains profiles, caregiver name, reminders and data transfer. An archived profile remains available for editing/export; unarchive it to resume tracking.
- Reminders are inexact, optional Android notifications. Battery restrictions and force-stop can delay or suppress delivery. They are logging conveniences, not medical alarms.

## Data portability

**Nara CSV:** exports the supplied 70-column schema, plus any extra columns encountered on import. Unchanged imported rows preserve all field values. Unknown activity types remain visible in History and survive export. The sample's 69-cell Profile footer is accepted as a missing final empty activity ID. CSV previews show new records, duplicates and conflicts before import. Existing records win conflicts unless replacement is selected. Shared pumping rows are exported once.

CSV compatibility means matching the supplied dataset, not verified acceptance by Nara's own importer. Nara's format has no photo or bottle-duration fields. Adult profiles use the BabyWise `ADULT` convention. Photos cannot be reconstructed from a CSV without photo references.

**Full backup:** `.babywise` files are ZIP archives with a versioned JSON manifest, photos, original CSV values, records, preferences, reminders and timer state. They are not encrypted; save them in a location you trust. Restore validates the archive before changing records, saves a pre-restore copy in app-private `files/backups`, and restores timers paused. Use **Restore previous local backup** to undo a restore. Backups are manual. Uninstalling the app removes its private data, including pre-restore copies.

## Build on Windows

1. Run `scripts/setup.ps1` in PowerShell and review/accept Android SDK licenses. It installs pinned build tools inside ignored `.tools`, without changing system Java.
2. Run `./build.ps1` for debug APK, JVM tests and Android lint.
3. Run `./scripts/release.ps1` for a signed family APK. The script creates a private signing key only on the first run and reuses it thereafter.
4. For Android Studio, choose the bundled JDK and `.tools/sdk` SDK directory. Build with AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20 and SDK 35.

If local script execution is disabled, launch an approved PowerShell session with an execution policy that allows these checked-in scripts. The app build does not require Android Studio.

## Tests

`./build.ps1 -Tasks testDebugUnitTest,lintDebug,assembleDebugAndroidTest`

JVM tests cover synthetic CSV edge cases, field preservation, adult postpartum classification, timer clocks/reboots, midnight/DST splits, paused segments, unit conversions, same-age comparisons and growth calculations.

Device tests cover Room import/conflicts, concurrent timers, ZIP backup/restore and corruption, WHO assets, and Compose navigation. Run `./scripts/emulator-setup.ps1 -Api 35`, `./scripts/emulator-start.ps1 -Api 35`, then `./build.ps1 -Tasks connectedDebugAndroidTest`. Use API 26 with a separate emulator port for the minimum supported version. Test only on disposable emulators: instrumentation clears app records before each case.

Detailed results and the staged reboot test are in [docs/VALIDATION.md](docs/VALIDATION.md). API 37 uses the SDK package name `37.0` and requires a 4 GB AVD; the checked-in setup and launcher scripts apply that setting automatically. The emulator launcher accepts `-Gpu software` (default) or another supported renderer. Add `-WipeData` when restarting a disposable test AVD from a clean state.

Run `./scripts/package-source.ps1` to create `artifacts/BabyWise-source.zip` from an explicit source-file allowlist. It excludes private data, signing keys, build caches and downloaded tools.

## License

BabyWise is available under the [MIT License](LICENSE). The app is an independent project; Nara is a third-party product name used only to describe CSV import/export compatibility.

## Maintenance

Room's version 1 schema is checked in under `app/schemas`. Future schema changes must increment the version, add explicit migrations and migration tests, and must not enable destructive fallback. External CSV keys remain separate from user-visible names. Stable imported IDs provide duplicate detection.

WHO reference sources and conversion details are in [docs/WHO.md](docs/WHO.md). Personal CSVs, screenshots, signing material, toolchains and generated APKs are ignored by version control.

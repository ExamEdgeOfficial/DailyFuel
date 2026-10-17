# Daily Fuel Diary — Android source project

An offline-first native Android wrapper around a local HTML food diary. Targets Android 11+ compatible devices (min SDK 23; compile SDK 35). Food diary and estimates stay in WebView local storage. No login, cloud service, analytics, or paid nutrition API.

## Features
- Natural-language-ish food entry with an offline built-in nutrition list for common foods.
- Estimates calories, protein, carbs, fat, and fibre; supports quantities such as `2 rotis, 1 bowl dal and 1 glass milk`.
- Edit servings and each macro; delete individual foods.
- Date-specific logs and history summary.
- JSON export/import backup.
- Daily Android notification reminder.
- GitHub Actions workflow builds a debug APK and publishes it as a downloadable workflow artifact.

## Build an APK
1. Create a GitHub repository and upload this project folder's contents (including `.github/workflows/build-apk.yml`).
2. Open the repository's **Actions** tab.
3. Run **Build Android APK** (or push to `main` / `master`).
4. When the run finishes, open the run and download the `DailyFuelDiary-debug-apk` artifact ZIP.
5. Extract it to get `app-debug.apk`; transfer it to the phone and install it. Android may ask you to allow installs from the browser/file manager you used.

## Important
- This source ZIP is **not itself an APK**. The APK is produced by GitHub Actions after you upload it to a repository and the workflow succeeds.
- Debug APKs are unsigned for Play Store distribution but are normally installable directly after downloading.
- Exact-alarm and notification permission behavior varies by Android version and device settings. On Android 12+, Android may show an exact-alarm settings screen. Enable notifications for dependable reminders.
- Estimates are approximate and only work for foods in the included offline dictionary. Unrecognised foods are reported instead of silently assigned made-up values.
- Backup before uninstalling: app data is stored locally and is removed if the app is uninstalled.

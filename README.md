# Daily Fuel Diary

A lightweight Android food diary with offline logging and optional AI-assisted food recognition.

## Features

- Log meals and review daily nutrition totals with the included offline food dictionary.
- Add an optional OpenAI API key in Settings to estimate nutrition from a description or photo. Results are estimates and are shown for review before saving.
- Keep diary entries on the device and export or restore a JSON backup.
- Set meal reminders and adjust the display size.

AI recognition requires internet access. A photo and food description are sent to OpenAI only when you request recognition. The API key is stored encrypted on the device. Diary entries and backups stay local unless you export them.

## Build and checks

Run the offline JavaScript checks with Node.js:

    node --test tests/*.test.cjs

Build a debug APK in an Android SDK configured environment:

    gradle --no-daemon assembleDebug

The APK is written to app/build/outputs/apk/debug/app-debug.apk. The debug APK is unsigned for Play Store distribution.

Nutrition values are estimates. Export a backup before uninstalling because Android removes local app data on uninstall.

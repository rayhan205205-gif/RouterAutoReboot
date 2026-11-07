# Router Auto Reboot v2

Build the APK (no installs needed):
1. Create a new GitHub repo, upload ALL files of this folder (keep the folder structure, including `.github/workflows/build.yml`).
2. Open the repo > Actions > "Build APK" > Run workflow (it also runs automatically on every push).
3. When it turns green, open the run > Artifacts > download `RouterAutoReboot-apk` > unzip > install `app-debug.apk`.

Uninstall the old app first (different signing key).

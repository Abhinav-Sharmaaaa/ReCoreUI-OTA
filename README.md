# ReCore Updater

Updater app for the ReCore ROM. It reads the feed of its channel (test, beta or release) from this repo's `feed` release, downloads the zip, checks the sha256 and restarts into TWRP with an OpenRecoveryScript that flashes it.

1. Put this project in the public OTA repo (the one that holds the releases).
2. Actions > Build updater app > Run workflow. It publishes ReCoreOTA.apk to the release tagged `app`.
3. The ROM build downloads that APK (script `ota_app.sh`) and installs it as a system app.

Needs root (KernelSU) for the final step. The first update asks for a one-time root grant.

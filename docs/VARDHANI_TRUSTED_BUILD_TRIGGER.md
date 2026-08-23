# VARDHANI trusted Android build trigger

This source-neutral file exists only to trigger the default-branch trusted Android workflow. The workflow checks out `develop/ema-android-foundation`, runs unit tests, assembles and verifies the APK, uploads `VARDHANI-1.0.0-full`, and writes the development SHA build status back to GitHub.

Requested development head: `062b06e154317757366454f84d6550cc86ffb0a1`.

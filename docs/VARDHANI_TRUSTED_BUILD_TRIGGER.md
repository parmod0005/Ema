# VARDHANI trusted Android build trigger

This source-neutral file exists only to trigger the default-branch trusted Android workflow. The workflow checks out `develop/ema-android-foundation`, runs unit tests, assembles and verifies the APK, uploads `VARDHANI-1.0.0-full`, and writes the development SHA build status back to GitHub.

Requested development head: `a72faf441302ca774e1088bd413ace43217cf2c3`.

Fresh retry requested 2026-08-23 after the completed full-app safety/recovery audit.
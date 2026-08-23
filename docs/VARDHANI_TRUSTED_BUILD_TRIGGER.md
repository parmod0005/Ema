# VARDHANI trusted Android build trigger

This source-neutral file exists only to trigger the default-branch trusted Android workflow. The workflow checks out `develop/ema-android-foundation`, runs unit tests, assembles and verifies the APK, uploads `VARDHANI-1.0.0-full`, and writes the development SHA build status back to GitHub.

Requested development head: `aa963e25ec63d7b31932bc62ceac22612eee3a1b`.

Fresh retry requested 2026-08-23 after the full-app safety/recovery audit, immediate crash-ledger persistence hardening, PAPER/LIVE restart risk-baseline restoration, and fail-closed protection for unpriced recovered LIVE P&L.
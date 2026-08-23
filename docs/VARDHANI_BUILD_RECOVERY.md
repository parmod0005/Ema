# VARDHANI build recovery checkpoint

Current development builds are intentionally triggered from direct pushes to `develop/ema-android-foundation` while GitHub pull-request checks are being isolated from a pre-step Actions hold.

Release discipline:

- Run `testDebugUnitTest` before APK assembly.
- Assemble `app-debug.apk` only after tests pass.
- Preserve the established paper-trading SL/T1/runner/D30 execution logic during historical-data work.
- Official raw historical AI samples require native NIFTY/SENSEX underlying context; legacy option-premium proxy direction is not Candidate-qualifying.
- Historical BSE/NSE instrument keys must come from verified metadata and are never invented.
- A new APK is not labelled verified until the complete build actually executes and its artifact can be inspected.

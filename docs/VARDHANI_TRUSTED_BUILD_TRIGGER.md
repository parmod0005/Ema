# VARDHANI trusted Android build trigger

This source-neutral file exists only to trigger the default-branch trusted Android workflow. The workflow checks out `develop/ema-android-foundation`, runs unit tests, assembles and verifies the APK, uploads `VARDHANI-1.0.0-full`, and writes the development SHA build status back to GitHub.

Requested development head: `36afa09c4f5e8bc10493d13a818f0fc72a544357`.

Fresh retry requested 2026-08-23 after the full-app safety/recovery audit, crash-ledger and restart risk hardening, fail-closed handling for unpriced recovered LIVE P&L, broker-quantity-aware LIVE SELL reconciliation, exact residual protective-stop sizing, emergency residual flatten protection, and deterministic sell-reconciliation regression tests.
# VARDHANI trusted Android build trigger

This source-neutral file exists only to trigger the default-branch trusted Android workflow. The workflow checks out `develop/ema-android-foundation`, runs unit tests, assembles and verifies the APK, uploads `VARDHANI-1.0.0-full`, and writes the development SHA build status back to GitHub.

Requested development head: `b4d8dca299539536446eb22c529e93d4448f7529`.

Fresh retry requested 2026-08-23 after resolving the PR workflow conflict, preserving development CI in a separate workflow, and retaining all latest safety hardening: crash-ledger and restart risk protection, fail-closed handling for unpriced recovered LIVE P&L, broker-quantity-aware LIVE SELL reconciliation, exact residual protective-stop sizing, emergency residual flatten protection, and deterministic sell-reconciliation regression tests.
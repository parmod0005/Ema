# VARDHANI 1.0.0 FULL APK — acceptance checklist

This document defines the required product boundary for the full VARDHANI APK. A binary must not be labelled `FULL` or `READY` until Android unit tests, `assembleDebug`, APK inspection and SHA-256 verification all pass for the source commit that contains this checklist.

## Primary dashboard

- Launcher: `VardhaniFullActivity`.
- Original `MainActivity` remains installed as Backtest / Legacy Tools.
- NIFTY, SENSEX or BOTH market selection.
- MANUAL or AUTO trading mode.
- PAPER or LIVE execution mode.
- LIVE defaults to DISARMED.
- Explicit MANUAL_ONLY or AUTO_ARMED live authority with confirmation dialog.
- Emergency kill / flatten control.
- Independent NIFTY and SENSEX lots.
- Independent NIFTY and SENSEX expiry selection.
- Live option chain ATM ±5 strikes with CE/PE LTP, bid/ask, OI, delta and broker lot size.
- Independent per-index engine states, positions, P&L, risk locks and trade logs.
- Combined dual-market realized/open P&L.

## Engine controls

- E1 TREND / BREAKOUT enable/disable.
- E2 AVWAP / LIQUIDITY + D30 enable/disable.
- E3 V7.6 REVERSAL RUNNER enable/disable.
- E1 and E2 expose real completed-candle Trigger / Setup / Bias configurations from 1m, 3m, 5m and 15m with causal ordering.
- E3 timing is locked to the exact current V7.6 source: 1m trigger / 3m setup / 5m directional bias.
- E1/E2 retain their existing tick-native cores; configurable multi-timeframe confirmation can veto but cannot invent an opposite signal.
- BOTH mode keeps NIFTY/SENSEX D30/order-flow histories isolated before V7.6 execution-quality evaluation.

## Upstox and credentials

- API key, API secret and redirect URI remain stored through the encrypted Android credential vault UI.
- Access token can be saved locally and used for live market data.
- Upstox Market Data Feed V3 remains the live tick source.
- Upstox option contract discovery supplies the actual instrument key and broker-reported lot size; live quantity must never be guessed from a hard-coded lot size.

## PAPER execution

- Manual PAPER CE/PE entries.
- Automatic PAPER entries from enabled engines.
- Bid/ask-aware paper fills when available.
- Adaptive stop / T1 partial / runner / time / flow / invalidation exits retained.
- Multi-lot T1 partial and runner behavior retained.
- Trade log records engine, market, side, strike, quantity, lots, entry/exit and P&L.

## LIVE execution — fail closed

- Direct broker-order code is isolated in `UpstoxOrderClient`.
- Current Upstox Order API V3 market orders are used only after `LiveExecutionGuard` passes.
- LIVE BUY requires: connected selected market, access token, verified instrument key and broker lot size, valid quantity, verified per-trade risk, no daily risk lock, no emergency kill, market open, entry window open, fresh market data, trade limit not reached and acceptable bid/ask spread.
- AUTO LIVE additionally requires `AUTO_ARMED` and minimum configured signal confidence.
- MANUAL LIVE requires at least `MANUAL_ONLY` arm.
- Upstox auto-slicing order IDs are all reconciled.
- Partial fills are never assumed to be complete.
- Unexpected partial BUY fills are flattened; any residual is tracked and forces emergency-kill/disarm state.
- Partial SELL fills leave a tracked residual position and force emergency-kill/disarm state.
- Changing market selection, expiry, demo/live connection or disconnecting is blocked when doing so could strand an open live broker position or pending broker operation.
- Existing LIVE positions retain LIVE exit behavior even if new-entry mode is switched back to PAPER.

## Risk controls

- Daily loss lock per index.
- Maximum trades per index per day.
- Maximum LIVE risk per trade.
- Maximum lots per order.
- Minimum AUTO LIVE confidence.
- Maximum live spread.
- Maximum live tick age.
- Duplicate automatic-entry suppression.
- Same-direction cooldown; V7.6 uses its existing longer same-direction cooldown and reversal score requirement.
- V7.6 consecutive-loss kill retained.
- Emergency kill disarms LIVE immediately and requests position flatten/close.
- Global new-entry window ends at 15:10 IST; E3 still obeys any stricter rule inside its exact V7.6 core.

## AI / historical / research tools retained

- AI Training Center / Meta Brain Lab.
- Historical Data activity.
- Research Archive activity.
- Legacy three-month backtest tools.
- Large CSV/TXT/XLSX/JSON/ZIP historical import and streaming corpus path.
- Pre-labelled train / validation / test NDJSON support.
- Candidate / Production separation, locked holdout, fresh live validation and manual promotion / rollback governance.
- Cost-aware calibration and strict TAKE / REJECT governance.
- Corrected raw historical training requires native NIFTY/SENSEX underlying context for official Candidate-qualifying samples.
- Historical contract metadata is imported only when genuine; SENSEX/BSE instrument keys are never invented.

## Binary certification gate

The full APK is certified only when all of the following pass for the same source SHA:

1. `testDebugUnitTest`
2. `assembleDebug`
3. APK exists and is non-empty
4. APK contains `classes.dex`
5. Binary/source inspection confirms `VardhaniFullActivity`, `VardhaniFullViewModel`, `UpstoxOrderClient`, `LiveExecutionGuard`, `MarketSelection.BOTH`, AI training and adaptive exit components
6. SHA-256 is calculated and recorded
7. The build is not substituted with CI704 or another older APK

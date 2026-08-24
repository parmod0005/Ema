# EMA Signal Specification

## Timeframes

- 15m: directional bias.
- 5m: trend quality and structure.
- 3m: setup and confirmation.
- 1m: optional execution refinement only.

## Indicators

- EMA20 and EMA50.
- EMA slope over three completed candles.
- ATR14.
- ADX14.
- Relative volume against a 20-candle average.
- Confirmed swing highs and lows.
- EMA separation normalized by ATR.
- EMA-cross and price-cross counts for chop detection.

All decisions use completed candles except live position management, which may use current bid/ask and risk triggers.

## State machine

### NEUTRAL

No valid directional condition. Move to `TREND_DETECTED` only when the higher-timeframe direction, EMA alignment, slopes, separation, and market quality pass.

### TREND_DETECTED

A valid trend exists. Wait for a controlled pullback toward EMA20, EMA50, a broken structure level, or a defined entry zone. Cancel when the trend weakens or chop activates.

### PULLBACK_ARMED

The pullback remains structurally valid. Require a rejection candle, momentum resumption, acceptable spread/liquidity, and a break of the trigger candle.

### ENTRY_CONFIRMED

Generate one order intent with a unique setup identifier. Prevent repeated orders from the same setup. Move to position management after a fill or back to neutral after timeout/invalidation.

### POSITION_MANAGEMENT

Manage stop, target, partial exit, trailing stop, reversal invalidation, and time-based exit. A position may not reverse directly without first closing and passing a cooldown.

## Direction rules

### CE candidate

- 15m EMA20 above EMA50 with positive slopes.
- 5m EMA20 above EMA50 and bullish structure.
- 3m pullback holds the valid support zone.
- Confirmation candle closes strongly and its trigger high is broken.

### PE candidate

- 15m EMA20 below EMA50 with negative slopes, or a separately configured confirmed reversal regime.
- 5m EMA20 below EMA50 and bearish structure.
- 3m pullback rejects the valid resistance zone.
- Confirmation candle closes strongly and its trigger low is broken.

A reversal against the prior 15m trend requires stricter scoring and a confirmed 5m structure break plus retest.

## Score

- 15m trend alignment: 20
- 5m trend alignment: 15
- 3m EMA alignment: 15
- EMA slopes: 10
- Market structure: 10
- ADX: 10
- Volume: 5
- ATR/tradability: 5
- Option liquidity/spread: 5
- OI/chain confirmation: 5

Thresholds:

- 0-64: reject.
- 65-74: watch.
- 75-84: confirmed.
- 85-100: strong.

Automatic modes require at least 75. Live automatic mode may use a higher configurable threshold, default 82.

## Anti-chop gate

Reject new entries when any configured hard condition is true:

- Three or more EMA20/EMA50 crosses in the last ten completed 3m candles.
- EMA separation below 0.15 ATR.
- ADX below 18.
- Price crosses EMA20 more than four times in ten candles.
- Current ATR cannot reasonably cover spread, slippage, fees, and the minimum target.

## Option selection

- Select the nearest liquid expiry permitted by configuration.
- Find ATM from the current underlying and strike interval.
- Display five strikes below ATM, ATM, and five above ATM.
- Prefer ATM or one-step ITM contracts for execution unless a tested configuration specifies otherwise.
- Reject contracts with stale quotes, abnormal spreads, insufficient depth/volume, missing Greeks, or inconsistent OI.

## Exits

Initial stop uses structure with an ATR safety buffer and a maximum-risk cap. Targets are expressed in R multiples. Default management supports:

- Partial profit at 1R.
- Move stop only according to configured rules, never immediately after entry without justification.
- Trail the remainder behind confirmed structure or an ATR-based trail.
- Exit on hard invalidation, daily risk lock, stale feed, session cutoff, or emergency stop.

## Risk defaults

Initial values are conservative placeholders and must be user-configurable:

- One open directional position per underlying.
- No averaging down.
- No martingale.
- Fixed maximum rupee risk per trade.
- Maximum daily loss.
- Maximum consecutive losses.
- Maximum trades per session.
- Cooldown after a loss or forced exit.
- No new entries close to configured market close.

## Audit record

Every decision stores timestamp, data freshness, timeframe snapshots, indicator values, score components, rejection reasons, selected contract, spread, intended risk, mode, and eventual outcome. This is required for debugging and strategy improvement.

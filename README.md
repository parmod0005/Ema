# EMA

EMA is a focused Android application for NIFTY 50 and SENSEX options monitoring, signal generation, paper trading, backtesting, and guarded order execution through Upstox.

## Product goal

Build a simple, elegant, responsive trading dashboard that presents only decision-critical information and avoids noisy or weak signals.

## Initial scope

- Native Android app using Kotlin and Jetpack Compose.
- Upstox OAuth authentication.
- Upstox Market Data Feed V3 for real-time NSE and BSE data.
- NIFTY 50 and SENSEX options with selectable expiry.
- Compact option-chain ladder centered on ATM, showing five strikes above and five below ATM for both CE and PE.
- LTP, open interest, change in open interest, delta, gamma, bid/ask spread, and liquidity state.
- Manual live trading.
- Guarded automatic live trading, disabled by default.
- Manual paper trading.
- Automatic paper trading.
- Three-month strategy backtesting using Upstox Historical Candle Data V3.
- Multi-timeframe EMA trend, slope, structure, ADX, ATR, volume, and anti-chop filters.
- Risk controls including daily loss lock, maximum trades, per-trade risk, stop loss, target, trailing stop, duplicate-signal suppression, and emergency kill switch.

## Signal design

The strategy uses 15-minute trend bias, 5-minute trend quality, and 3-minute entry confirmation. One-minute data may refine entry timing but cannot override the higher-timeframe direction.

Signals progress through a state machine:

`NEUTRAL -> TREND_DETECTED -> PULLBACK_ARMED -> ENTRY_CONFIRMED -> POSITION_MANAGEMENT`

A score below 75 never triggers an automatic order. Live automatic execution additionally requires explicit user enablement, valid session state, risk-gate approval, acceptable spread and liquidity, and no active safety lock.

## Safety

The application must never claim guaranteed profitability. Live automatic trading remains opt-in and guarded. Paper trading and backtesting are the default validation modes.

## Development branch

Initial foundation work is being developed on `develop/ema-android-foundation` before review and merge into `main`.

# EMA Android Architecture

## Principles

1. Keep the dashboard minimal and latency-aware.
2. Separate broker transport, market state, strategy decisions, risk approval, and execution.
3. Use the same strategy engine for live signals, paper trading, and backtesting.
4. Persist an auditable explanation for every accepted or rejected signal.
5. Default to paper mode and fail closed when authentication, data freshness, or risk state is uncertain.

## Modules

### app

Jetpack Compose navigation, dependency wiring, lifecycle handling, and application-level state.

### core-model

Immutable domain models for instruments, ticks, candles, option quotes, Greeks, signals, positions, orders, fills, P&L, and risk state.

### broker-upstox

- OAuth login and token handling.
- Instrument discovery and expiry lookup.
- Market Data Feed V3 WebSocket authorization, redirect handling, Protobuf decoding, subscription management, reconnection, heartbeat, and stale-feed detection.
- Option-chain and option-Greeks requests.
- Historical Candle Data V3 pagination and local caching.
- Order placement, modification, cancellation, positions, and order updates.

### market-engine

- Tick normalization.
- 1m candle construction from live ticks.
- 3m, 5m, and 15m aggregation.
- ATM calculation and 5+ATM+5 strike selection.
- OI-change calculation from current and previous OI.
- Data-quality and freshness state.

### indicator-engine

EMA20, EMA50, EMA slope, ATR, ADX, volume ratio, swing structure, breakout quality, and anti-chop measurements.

### strategy-engine

Deterministic state machine and 100-point confirmation score. Produces an explainable `SignalDecision`; it never places orders directly.

### risk-engine

Approves or rejects intended orders using mode, account state, market hours, feed freshness, spread, liquidity, quantity, per-trade risk, stop distance, daily loss, trade count, cooldown, open exposure, duplicate-signal checks, and emergency lock state.

### execution-engine

Routes approved intents to live or paper execution. Live automatic trading requires an explicit armed state and a visible kill switch.

### paper-engine

Simulated fills using bid/ask-aware pricing, configurable slippage, fees, latency, partial-fill policy, stop/target processing, and complete trade journaling.

### backtest-engine

Runs the same candle, indicator, strategy, risk, and paper-fill logic over up to three months of Upstox historical data. Reports net P&L after costs, drawdown, win rate, profit factor, expectancy, average R, trade count, exposure time, and results by market regime.

### persistence

Room database for encrypted session metadata, instruments, cached candles, strategy configuration, signals, orders, fills, positions, risk locks, and backtest runs. Secrets must not be committed or logged.

## Dashboard

The default screen contains:

- Connection and data-freshness indicator.
- NIFTY/SENSEX selector, spot LTP, expiry, and market state.
- Mode selector: Observe, Manual Paper, Auto Paper, Manual Live, Auto Live.
- Signal card: CE/PE/WAIT, score, entry zone, stop, target, and concise reasons.
- Option ladder centered on ATM with five strikes on each side, showing CE and PE LTP, OI, OI change, delta, gamma, and spread state.
- Active position card with quantity, average price, live P&L, stop, target, and exit action.
- Daily P&L, risk remaining, trade count, and emergency stop.

Advanced diagnostics stay behind a details screen rather than cluttering the main dashboard.

## Mode rules

- Observe: data and signals only.
- Manual Paper: user initiates simulated orders.
- Auto Paper: strategy and risk engines initiate simulated orders.
- Manual Live: user initiates Upstox orders after an order-ticket confirmation.
- Auto Live: strategy initiates live orders only while explicitly armed; automatically disarms on app restart, token failure, stale data, reconnect, daily loss lock, abnormal spread, or inconsistent position state.

## Backtesting limitation

Underlying candles are sufficient for strategy-direction validation, but realistic options P&L requires historical option-contract candles and correct contract selection for each historical date. The implementation must label any fallback or synthetic option pricing clearly and must not mix it with verified contract-level results.

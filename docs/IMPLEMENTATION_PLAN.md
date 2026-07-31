# Implementation Plan

## Phase 1: Android foundation

- Kotlin and Jetpack Compose project scaffold.
- Navigation, theme, dependency injection, Room, encrypted preferences, networking, logging, and test setup.
- Minimal dashboard shell and mode state.

## Phase 2: Upstox integration

- OAuth flow and session handling.
- Instrument master and expiry discovery.
- NSE/BSE underlying and derivatives mapping.
- Market Data Feed V3 WebSocket with Protobuf decoding.
- Option chain, Greeks, OI, previous OI, and OI-change calculation.
- Reconnect, stale-feed detection, and subscription switching.

## Phase 3: Market and strategy engines

- Tick-to-candle builder.
- 1m/3m/5m/15m aggregation.
- EMA, slope, ATR, ADX, volume, swings, breakout quality, and anti-chop logic.
- State machine, signal score, explanations, and duplicate suppression.

## Phase 4: Paper trading

- Manual paper order ticket.
- Automatic paper execution.
- Bid/ask-aware fills, slippage, fees, stops, targets, trailing, journal, and daily risk controls.

## Phase 5: Backtesting

- Three-month historical downloader and cache.
- Contract-aware option data selection where Upstox historical contracts are available.
- Shared strategy and fill engine.
- Results screen with net P&L, drawdown, profit factor, expectancy, win rate, and trade list.

## Phase 6: Live trading

- Manual Upstox order placement and order updates.
- Guarded automatic execution.
- Explicit arming, startup disarm, emergency stop, daily lock, stale-data lock, spread/liquidity checks, position reconciliation, and complete audit trail.

## Phase 7: Validation and release

- Unit tests for indicators, scoring, state transitions, risk gates, and P&L.
- Replay tests using captured Upstox streams.
- Paper forward test across trending, sideways, volatile, and expiry sessions.
- Android release build, signing instructions, and installation package.

## Acceptance criteria

The app is not considered ready for live automatic trading until:

1. Reconnect and stale-data tests pass.
2. Position and order reconciliation pass.
3. Backtest costs and slippage are included.
4. Paper forward testing demonstrates stable behaviour across multiple market regimes.
5. The emergency stop and all daily risk locks are verified.
6. Automatic live trading is disabled by default and requires explicit arming each session.

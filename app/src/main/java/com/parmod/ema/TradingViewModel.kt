package com.parmod.ema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.model.ConnectionMode
import com.parmod.ema.model.DashboardState
import com.parmod.ema.model.ExecutionMode
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PaperPosition
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TradingMode
import com.parmod.ema.model.TrendDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class TradingViewModel : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var feedJob: Job? = null
    private var tick = 0
    private var autoTradeTakenForSignal = false

    fun connectDemo() {
        feedJob?.cancel()
        _state.value = _state.value.copy(
            connectionMode = ConnectionMode.DEMO,
            isConnected = true,
            executionMode = ExecutionMode.PAPER,
            message = "Demo feed connected · paper execution only",
        )
        feedJob = viewModelScope.launch {
            while (true) {
                publishDemoTick()
                delay(1_000)
            }
        }
    }

    fun disconnect() {
        feedJob?.cancel()
        feedJob = null
        _state.value = _state.value.copy(isConnected = false, message = "Disconnected")
    }

    fun selectIndex(index: MarketIndex) {
        closePosition("Index changed")
        tick = 0
        _state.value = _state.value.copy(index = index)
        if (_state.value.isConnected) publishDemoTick()
    }

    fun setTradingMode(mode: TradingMode) {
        autoTradeTakenForSignal = false
        _state.value = _state.value.copy(tradingMode = mode, message = "$mode mode selected")
    }

    fun setExecutionMode(mode: ExecutionMode) {
        if (mode == ExecutionMode.LIVE && !_state.value.liveExecutionUnlocked) {
            _state.value = _state.value.copy(
                executionMode = ExecutionMode.PAPER,
                message = "Live execution locked until Upstox validation and risk approval",
            )
            return
        }
        _state.value = _state.value.copy(executionMode = mode)
    }

    fun buyCe() = openPaperPosition(PositionSide.CE)
    fun buyPe() = openPaperPosition(PositionSide.PE)
    fun exitPosition() = closePosition("Position exited")

    private fun publishDemoTick() {
        val current = _state.value
        val base = if (current.index == MarketIndex.NIFTY) 24_550.0 else 80_200.0
        val step = if (current.index == MarketIndex.NIFTY) 50 else 100
        val wave = when {
            tick < 25 -> tick * 2.4
            tick < 45 -> 60.0 - (tick - 25) * 0.5
            tick < 70 -> 50.0 - (tick - 45) * 2.1
            else -> -2.5 + (tick - 70) * 0.7
        }
        val spot = base + wave + Random.nextDouble(-2.0, 2.0)
        val atm = (spot / step).toInt() * step
        val trend = when {
            tick < 37 -> TrendDirection.BULLISH
            tick < 49 -> TrendDirection.NEUTRAL
            tick < 78 -> TrendDirection.BEARISH
            else -> TrendDirection.NEUTRAL
        }
        val action = when (trend) {
            TrendDirection.BULLISH -> SignalAction.BUY_CE
            TrendDirection.BEARISH -> SignalAction.BUY_PE
            TrendDirection.NEUTRAL -> SignalAction.WAIT
        }
        val confidence = when (trend) {
            TrendDirection.NEUTRAL -> 48
            else -> 84
        }
        val chain = buildChain(spot, atm, step)
        val signal = SignalSnapshot(
            action = action,
            confidence = confidence,
            trend = trend,
            entry = if (action == SignalAction.WAIT) null else spot,
            stopLoss = when (action) {
                SignalAction.BUY_CE -> spot - step * 0.45
                SignalAction.BUY_PE -> spot + step * 0.45
                SignalAction.WAIT -> null
            },
            target = when (action) {
                SignalAction.BUY_CE -> spot + step * 0.9
                SignalAction.BUY_PE -> spot - step * 0.9
                SignalAction.WAIT -> null
            },
            reasons = when (trend) {
                TrendDirection.BULLISH -> listOf("EMA20 above EMA50", "EMA slopes rising", "ADX and structure confirm")
                TrendDirection.BEARISH -> listOf("EMA20 below EMA50", "Lower-high rejection", "ADX and structure confirm")
                TrendDirection.NEUTRAL -> listOf("EMA compression detected", "Anti-chop filter active", "Waiting for expansion")
            },
        )
        val updatedPosition = current.position?.let { position ->
            val quote = chain.firstOrNull { it.strike == position.strike && it.type == position.side.name }
            quote?.let { position.copy(currentPrice = it.ltp) } ?: position
        }
        _state.value = current.copy(
            spotPrice = spot,
            optionChain = chain,
            signal = signal,
            position = updatedPosition,
            pnl = updatedPosition?.pnl ?: 0.0,
        )
        runAutoPaperIfEligible()
        tick = (tick + 1) % 90
    }

    private fun buildChain(spot: Double, atm: Int, step: Int): List<OptionQuote> {
        return (-5..5).flatMap { offset ->
            val strike = atm + offset * step
            val distance = spot - strike
            val intrinsicCe = max(0.0, distance)
            val intrinsicPe = max(0.0, -distance)
            val timeValue = max(18.0, 105.0 - abs(offset) * 12.0)
            val gamma = max(0.0002, 0.0022 - abs(offset) * 0.00025)
            val ceDelta = (0.50 + distance / (step * 10.0)).coerceIn(0.08, 0.92)
            val peDelta = ceDelta - 1.0
            listOf(
                OptionQuote(strike.toDouble(), "CE", intrinsicCe + timeValue, 90_000L + offset * 2_300L, 1_500L - offset * 170L, ceDelta, gamma, offset == 0),
                OptionQuote(strike.toDouble(), "PE", intrinsicPe + timeValue, 94_000L - offset * 2_000L, -700L + offset * 190L, peDelta, gamma, offset == 0),
            )
        }
    }

    private fun openPaperPosition(side: PositionSide) {
        val current = _state.value
        if (!current.isConnected) {
            _state.value = current.copy(message = "Connect Demo or Upstox first")
            return
        }
        if (current.executionMode != ExecutionMode.PAPER) {
            _state.value = current.copy(message = "Live orders remain safety-locked")
            return
        }
        if (current.position != null) {
            _state.value = current.copy(message = "Exit the current position first")
            return
        }
        val quote = current.optionChain.firstOrNull { it.isAtm && it.type == side.name } ?: return
        val lotSize = if (current.index == MarketIndex.NIFTY) 65 else 20
        _state.value = current.copy(
            position = PaperPosition(side, quote.strike, lotSize, quote.ltp, quote.ltp),
            pnl = 0.0,
            message = "Paper BUY ${quote.strike.toInt()} ${side.name} × $lotSize",
        )
    }

    private fun closePosition(reason: String) {
        val current = _state.value
        val realized = current.position?.pnl ?: 0.0
        _state.value = current.copy(position = null, pnl = 0.0, message = "$reason · P&L ₹${"%.2f".format(realized)}")
        autoTradeTakenForSignal = false
    }

    private fun runAutoPaperIfEligible() {
        val current = _state.value
        if (current.tradingMode != TradingMode.AUTO || current.executionMode != ExecutionMode.PAPER) return
        if (current.position == null && !autoTradeTakenForSignal && current.signal.confidence >= 80) {
            when (current.signal.action) {
                SignalAction.BUY_CE -> openPaperPosition(PositionSide.CE)
                SignalAction.BUY_PE -> openPaperPosition(PositionSide.PE)
                SignalAction.WAIT -> Unit
            }
            autoTradeTakenForSignal = true
        }
        val position = _state.value.position ?: return
        val pnlPercent = if (position.entryPrice == 0.0) 0.0 else (position.currentPrice - position.entryPrice) / position.entryPrice
        val signalReversed = (position.side == PositionSide.CE && current.signal.action == SignalAction.BUY_PE) ||
            (position.side == PositionSide.PE && current.signal.action == SignalAction.BUY_CE)
        if (pnlPercent <= -0.15 || pnlPercent >= 0.30 || signalReversed) closePosition("Auto exit")
    }
}

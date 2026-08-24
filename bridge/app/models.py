from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SignalAction(str, Enum):
    BUY_CE = "BUY_CE"
    BUY_PE = "BUY_PE"
    WAIT = "WAIT"


class MarketRegime(str, Enum):
    TRENDING_BULLISH = "TRENDING_BULLISH"
    TRENDING_BEARISH = "TRENDING_BEARISH"
    RANGE = "RANGE"
    BREAKOUT = "BREAKOUT"
    REVERSAL = "REVERSAL"
    HIGH_VOLATILITY = "HIGH_VOLATILITY"
    UNKNOWN = "UNKNOWN"


class CompactBar(StrictModel):
    epochMillis: int
    open: float
    high: float
    low: float
    close: float
    volume: int = Field(ge=0)

    @field_validator("high")
    @classmethod
    def high_must_be_valid(cls, value: float, info):
        return value


class OptionQuote(StrictModel):
    instrumentKey: str
    strike: float
    type: Literal["CE", "PE"]
    ltp: float = Field(gt=0)
    openInterest: int = Field(ge=0)
    changeInOpenInterest: int
    delta: float = Field(ge=-1.0, le=1.0)
    gamma: float = Field(ge=0)
    lastTickMillis: int = Field(ge=0)


class RiskContext(StrictModel):
    capital: float = Field(gt=0)
    realizedPnl: float
    openSide: Literal["CE", "PE"] | None = None
    openEntryPrice: float | None = Field(default=None, gt=0)
    dailyTrades: int = Field(default=0, ge=0)
    dailyLossLocked: bool = False


class NewsContext(StrictModel):
    headline: str = Field(min_length=1, max_length=500)
    source: str = Field(min_length=1, max_length=120)
    publishedAtMillis: int
    sentimentScore: float = Field(ge=-1, le=1)
    relevanceScore: float = Field(ge=0, le=1)


class MarketSnapshot(StrictModel):
    schemaVersion: Literal[1]
    snapshotId: str = Field(min_length=8, max_length=128)
    generatedAtMillis: int
    index: Literal["NIFTY", "SENSEX"]
    expiry: str
    spot: float = Field(gt=0)
    nativeAction: SignalAction
    nativeConfidence: int = Field(ge=0, le=100)
    bars1m: list[CompactBar] = Field(max_length=240)
    bars5m: list[CompactBar] = Field(max_length=180)
    bars15m: list[CompactBar] = Field(max_length=120)
    optionChain: list[OptionQuote] = Field(min_length=2, max_length=200)
    risk: RiskContext
    news: list[NewsContext] = Field(default_factory=list, max_length=30)


class ConditionalTrigger(StrictModel):
    spotAbove: float | None = None
    spotBelow: float | None = None
    minimumVolumeRatio: float | None = Field(default=None, ge=0)
    maximumSpreadPct: float | None = Field(default=None, ge=0, le=10)


class TradeDecision(StrictModel):
    schemaVersion: Literal[1] = 1
    decisionId: str
    snapshotId: str
    decidedAtMillis: int
    validForMillis: int = Field(ge=1_000, le=300_000)
    action: SignalAction
    confidence: int = Field(ge=0, le=100)
    regime: MarketRegime
    instrumentKey: str | None = None
    strike: float | None = None
    optionType: Literal["CE", "PE"] | None = None
    entryMin: float | None = None
    entryMax: float | None = None
    stopLoss: float | None = None
    target: float | None = None
    trigger: ConditionalTrigger | None = None
    maximumSpotMovePct: float = Field(default=0.20, ge=0.01, le=2.0)
    reasons: list[str] = Field(default_factory=list, max_length=8)
    riskFlags: list[str] = Field(default_factory=list, max_length=8)
    modelVersion: str
    promptVersion: str


class AnalyzeResponse(StrictModel):
    decision: TradeDecision

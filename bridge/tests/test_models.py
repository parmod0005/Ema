import time

import pytest
from pydantic import ValidationError

from app.brain import OpenAiBrain
from app.models import MarketSnapshot, TradeDecision


def snapshot_payload() -> dict:
    now = int(time.time() * 1000)
    return {
        "schemaVersion": 1,
        "snapshotId": "snapshot-12345678",
        "generatedAtMillis": now,
        "index": "NIFTY",
        "expiry": "2026-08-06",
        "spot": 25000.0,
        "nativeAction": "WAIT",
        "nativeConfidence": 55,
        "bars1m": [{"epochMillis": now, "open": 24995, "high": 25005, "low": 24990, "close": 25000, "volume": 1000}],
        "bars5m": [],
        "bars15m": [],
        "optionChain": [
            {"instrumentKey": "ce", "strike": 25000, "type": "CE", "ltp": 100, "openInterest": 10000, "changeInOpenInterest": 500, "delta": 0.5, "gamma": 0.002, "lastTickMillis": now},
            {"instrumentKey": "pe", "strike": 25000, "type": "PE", "ltp": 95, "openInterest": 11000, "changeInOpenInterest": 600, "delta": -0.5, "gamma": 0.002, "lastTickMillis": now},
        ],
        "risk": {"capital": 100000, "realizedPnl": 0, "dailyTrades": 0, "dailyLossLocked": False},
        "news": [],
    }


def test_snapshot_accepts_valid_payload() -> None:
    snapshot = MarketSnapshot.model_validate(snapshot_payload())
    assert snapshot.index == "NIFTY"


def test_snapshot_rejects_unknown_fields() -> None:
    payload = snapshot_payload()
    payload["unexpected"] = True
    with pytest.raises(ValidationError):
        MarketSnapshot.model_validate(payload)


def test_decision_rejects_invalid_confidence() -> None:
    with pytest.raises(ValidationError):
        TradeDecision(
            decisionId="d",
            snapshotId="s",
            decidedAtMillis=1,
            validForMillis=15000,
            action="WAIT",
            confidence=101,
            regime="UNKNOWN",
            modelVersion="test",
            promptVersion="test",
        )


def test_unconfigured_brain_fails_closed_to_wait(monkeypatch) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    snapshot = MarketSnapshot.model_validate(snapshot_payload())
    decision = OpenAiBrain()._wait(snapshot, "not configured", "fallback")
    assert decision.action == "WAIT"
    assert decision.riskFlags == ["AI_UNAVAILABLE"]

from __future__ import annotations

import time

from fastapi.testclient import TestClient

from app import main
from app.models import MarketSnapshot


EXPECTED_DECISION_KEYS = {
    "schemaVersion",
    "decisionId",
    "snapshotId",
    "decidedAtMillis",
    "validForMillis",
    "action",
    "confidence",
    "regime",
    "instrumentKey",
    "strike",
    "optionType",
    "entryMin",
    "entryMax",
    "stopLoss",
    "target",
    "trigger",
    "maximumSpotMovePct",
    "reasons",
    "riskFlags",
    "modelVersion",
    "promptVersion",
}


def android_snapshot_payload() -> dict:
    now = int(time.time() * 1000)
    bar = {
        "epochMillis": now - 60_000,
        "open": 24990.0,
        "high": 25010.0,
        "low": 24980.0,
        "close": 25000.0,
        "volume": 125000,
    }
    return {
        "schemaVersion": 1,
        "snapshotId": "android-contract-snapshot-0001",
        "generatedAtMillis": now,
        "index": "NIFTY",
        "expiry": "2026-08-06",
        "spot": 25000.0,
        "bars1m": [bar],
        "bars5m": [bar],
        "bars15m": [bar],
        "optionChain": [
            {
                "instrumentKey": "NSE_FO|25000CE",
                "strike": 25000.0,
                "type": "CE",
                "ltp": 105.5,
                "openInterest": 120000,
                "changeInOpenInterest": 8000,
                "delta": 0.52,
                "gamma": 0.0021,
                "lastTickMillis": now,
            },
            {
                "instrumentKey": "NSE_FO|25000PE",
                "strike": 25000.0,
                "type": "PE",
                "ltp": 98.0,
                "openInterest": 132000,
                "changeInOpenInterest": 10500,
                "delta": -0.48,
                "gamma": 0.0020,
                "lastTickMillis": now,
            },
        ],
        "nativeAction": "WAIT",
        "nativeConfidence": 62,
        "risk": {
            "capital": 100000.0,
            "realizedPnl": 0.0,
            "openSide": None,
            "openEntryPrice": None,
            "dailyTrades": 0,
            "dailyLossLocked": False,
        },
        "news": [],
    }


def configured_client(monkeypatch) -> TestClient:
    monkeypatch.setattr(main, "DEVICE_TOKEN", "contract-test-token")
    monkeypatch.setattr(main.brain, "api_key", "")
    main.request_windows.clear()
    return TestClient(main.app)


def headers() -> dict[str, str]:
    return {
        "Authorization": "Bearer contract-test-token",
        "X-Vardhani-Schema": "1",
    }


def test_android_snapshot_is_accepted_by_bridge_models() -> None:
    snapshot = MarketSnapshot.model_validate(android_snapshot_payload())
    assert snapshot.snapshotId == "android-contract-snapshot-0001"
    assert snapshot.optionChain[0].instrumentKey == "NSE_FO|25000CE"


def test_analyze_returns_android_parser_compatible_wait(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(main, "AUDIT_PATH", tmp_path / "audit.jsonl")
    client = configured_client(monkeypatch)

    response = client.post("/v1/analyze", headers=headers(), json=android_snapshot_payload())

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"decision"}
    decision = body["decision"]
    assert set(decision) == EXPECTED_DECISION_KEYS
    assert decision["snapshotId"] == "android-contract-snapshot-0001"
    assert decision["action"] == "WAIT"
    assert 1_000 <= decision["validForMillis"] <= 300_000
    assert 0 <= decision["confidence"] <= 100
    assert decision["modelVersion"] == "bridge-fallback"
    assert decision["riskFlags"] == ["AI_UNAVAILABLE"]


def test_analyze_rejects_wrong_schema_header(monkeypatch) -> None:
    client = configured_client(monkeypatch)
    bad_headers = headers() | {"X-Vardhani-Schema": "2"}

    response = client.post("/v1/analyze", headers=bad_headers, json=android_snapshot_payload())

    assert response.status_code == 400


def test_analyze_rejects_daily_loss_locked_snapshot(monkeypatch) -> None:
    client = configured_client(monkeypatch)
    payload = android_snapshot_payload()
    payload["risk"]["dailyLossLocked"] = True

    response = client.post("/v1/analyze", headers=headers(), json=payload)

    assert response.status_code == 423

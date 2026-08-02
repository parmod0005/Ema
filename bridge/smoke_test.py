from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request


def request_json(url: str, method: str = "GET", token: str = "", payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if payload is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
        request.add_header("X-Vardhani-Schema", "1")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {body[:400]}") from error


def snapshot() -> dict:
    now = int(time.time() * 1000)
    bar = {"epochMillis": now, "open": 24995.0, "high": 25005.0, "low": 24990.0, "close": 25000.0, "volume": 1000}
    return {
        "schemaVersion": 1,
        "snapshotId": f"smoke-{now}",
        "generatedAtMillis": now,
        "index": "NIFTY",
        "expiry": "2026-08-06",
        "spot": 25000.0,
        "bars1m": [bar],
        "bars5m": [bar],
        "bars15m": [bar],
        "optionChain": [],
        "nativeAction": "WAIT",
        "nativeConfidence": 50,
        "risk": {"capital": 100000.0, "realizedPnl": 0.0, "dailyTrades": 0, "dailyLossLocked": False},
        "news": [],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Smoke-test a deployed VARDHANI AI Bridge")
    parser.add_argument("base_url")
    parser.add_argument("device_token")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    health = request_json(f"{base}/v1/health")
    if not health.get("paperOnly", False):
        raise RuntimeError("Bridge did not report paperOnly=true")
    print("health:", json.dumps(health, indent=2))

    response = request_json(f"{base}/v1/analyze", "POST", args.device_token, snapshot())
    decision = response.get("decision") or {}
    if decision.get("action") not in {"BUY_CE", "BUY_PE", "WAIT"}:
        raise RuntimeError("Unexpected decision action")
    if decision.get("snapshotId", "").startswith("smoke-") is False:
        raise RuntimeError("Snapshot ID mismatch")
    print("decision:", json.dumps(decision, indent=2))


if __name__ == "__main__":
    main()

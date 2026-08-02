from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import time
from collections import defaultdict, deque
from pathlib import Path

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status

from .brain import OpenAiBrain
from .models import AnalyzeResponse, MarketSnapshot, TradeDecision

load_dotenv()

APP_VERSION = "0.1.0"
DEVICE_TOKEN = os.environ.get("VARDHANI_DEVICE_TOKEN", "").strip()
MAX_SNAPSHOT_AGE_MS = int(os.environ.get("MAX_SNAPSHOT_AGE_MS", "10000"))
RATE_LIMIT_PER_MINUTE = int(os.environ.get("RATE_LIMIT_PER_MINUTE", "12"))
AUDIT_PATH = Path(os.environ.get("AUDIT_PATH", "bridge-data/audit.jsonl"))

app = FastAPI(title="VARDHANI AI Bridge", version=APP_VERSION, docs_url=None, redoc_url=None)
brain = OpenAiBrain()
request_windows: dict[str, deque[float]] = defaultdict(deque)
audit_lock = asyncio.Lock()


def _bearer_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Bearer token required")
    return authorization[7:].strip()


async def authenticate(
    authorization: str | None = Header(default=None),
    x_vardhani_schema: str | None = Header(default=None),
) -> str:
    if not DEVICE_TOKEN:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Bridge device token is not configured")
    supplied = _bearer_token(authorization)
    if not hmac.compare_digest(supplied, DEVICE_TOKEN):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device token")
    if x_vardhani_schema != "1":
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported VARDHANI schema")
    return hashlib.sha256(supplied.encode()).hexdigest()[:16]


def enforce_rate_limit(client_id: str) -> None:
    now = time.monotonic()
    window = request_windows[client_id]
    while window and now - window[0] >= 60:
        window.popleft()
    if len(window) >= RATE_LIMIT_PER_MINUTE:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Bridge rate limit exceeded")
    window.append(now)


def validate_snapshot(snapshot: MarketSnapshot) -> None:
    now_ms = int(time.time() * 1000)
    age_ms = now_ms - snapshot.generatedAtMillis
    if age_ms < -5_000:
        raise HTTPException(status_code=400, detail="Snapshot timestamp is in the future")
    if age_ms > MAX_SNAPSHOT_AGE_MS:
        raise HTTPException(status_code=409, detail=f"Snapshot is stale by {age_ms} ms")
    if snapshot.risk.dailyLossLocked:
        raise HTTPException(status_code=423, detail="Daily loss lock is active")
    if not snapshot.bars1m:
        raise HTTPException(status_code=422, detail="At least one 1-minute bar is required")


async def write_audit(snapshot: MarketSnapshot, decision: TradeDecision, latency_ms: int) -> None:
    record = {
        "recordedAtMillis": int(time.time() * 1000),
        "snapshotId": snapshot.snapshotId,
        "index": snapshot.index,
        "spot": snapshot.spot,
        "nativeAction": snapshot.nativeAction,
        "nativeConfidence": snapshot.nativeConfidence,
        "decision": decision.model_dump(mode="json"),
        "latencyMillis": latency_ms,
    }
    async with audit_lock:
        AUDIT_PATH.parent.mkdir(parents=True, exist_ok=True)
        with AUDIT_PATH.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, separators=(",", ":")) + "\n")


@app.get("/v1/health")
async def health() -> dict:
    return {
        "ok": bool(DEVICE_TOKEN) and brain.configured,
        "message": "VARDHANI AI bridge available" if DEVICE_TOKEN and brain.configured else "Bridge requires server configuration",
        "version": APP_VERSION,
        "openaiConfigured": brain.configured,
        "paperOnly": True,
    }


@app.post("/v1/analyze", response_model=AnalyzeResponse)
async def analyze(
    snapshot: MarketSnapshot,
    request: Request,
    client_id: str = Depends(authenticate),
) -> AnalyzeResponse:
    enforce_rate_limit(client_id)
    validate_snapshot(snapshot)
    started = time.monotonic()
    try:
        decision = await brain.analyze(snapshot)
    except Exception as exc:
        # Fail closed: never convert an AI/API error into a directional trade.
        decision = brain._wait(snapshot, f"AI analysis unavailable: {type(exc).__name__}", "bridge-fallback")
    latency_ms = int((time.monotonic() - started) * 1000)
    await write_audit(snapshot, decision, latency_ms)
    return AnalyzeResponse(decision=decision)

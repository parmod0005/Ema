from __future__ import annotations

import json
import os
import time
import uuid

import httpx

from .models import MarketSnapshot, TradeDecision


SYSTEM_PROMPT = """You are the VARDHANI market-analysis brain for Indian index options.
Analyze only the supplied snapshot. Return BUY_CE, BUY_PE, or WAIT.
Prioritize capital preservation, data freshness, liquidity, multi-timeframe structure,
volume, option-chain positioning, Greeks, expiry risk, and event risk.
Never invent missing data. When evidence conflicts, liquidity is poor, risk is high,
or confidence is below 80, return WAIT. Do not claim certainty or guaranteed profit.
The Android app independently enforces risk limits and execution safety.
"""


DECISION_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "schemaVersion", "decisionId", "snapshotId", "decidedAtMillis",
        "validForMillis", "action", "confidence", "regime", "instrumentKey",
        "strike", "optionType", "entryMin", "entryMax", "stopLoss", "target",
        "trigger", "maximumSpotMovePct", "reasons", "riskFlags",
        "modelVersion", "promptVersion",
    ],
    "properties": {
        "schemaVersion": {"type": "integer", "const": 1},
        "decisionId": {"type": "string"},
        "snapshotId": {"type": "string"},
        "decidedAtMillis": {"type": "integer"},
        "validForMillis": {"type": "integer", "minimum": 1000, "maximum": 300000},
        "action": {"type": "string", "enum": ["BUY_CE", "BUY_PE", "WAIT"]},
        "confidence": {"type": "integer", "minimum": 0, "maximum": 100},
        "regime": {"type": "string", "enum": [
            "TRENDING_BULLISH", "TRENDING_BEARISH", "RANGE", "BREAKOUT",
            "REVERSAL", "HIGH_VOLATILITY", "UNKNOWN"
        ]},
        "instrumentKey": {"type": ["string", "null"]},
        "strike": {"type": ["number", "null"]},
        "optionType": {"type": ["string", "null"], "enum": ["CE", "PE", None]},
        "entryMin": {"type": ["number", "null"]},
        "entryMax": {"type": ["number", "null"]},
        "stopLoss": {"type": ["number", "null"]},
        "target": {"type": ["number", "null"]},
        "trigger": {
            "type": ["object", "null"],
            "additionalProperties": False,
            "required": ["spotAbove", "spotBelow", "minimumVolumeRatio", "maximumSpreadPct"],
            "properties": {
                "spotAbove": {"type": ["number", "null"]},
                "spotBelow": {"type": ["number", "null"]},
                "minimumVolumeRatio": {"type": ["number", "null"]},
                "maximumSpreadPct": {"type": ["number", "null"]},
            },
        },
        "maximumSpotMovePct": {"type": "number", "minimum": 0.01, "maximum": 2.0},
        "reasons": {"type": "array", "items": {"type": "string"}, "maxItems": 8},
        "riskFlags": {"type": "array", "items": {"type": "string"}, "maxItems": 8},
        "modelVersion": {"type": "string"},
        "promptVersion": {"type": "string"},
    },
}


class OpenAiBrain:
    def __init__(self) -> None:
        self.api_key = os.environ.get("OPENAI_API_KEY", "").strip()
        self.model = os.environ.get("OPENAI_MODEL", "gpt-5").strip()
        self.timeout_seconds = float(os.environ.get("OPENAI_TIMEOUT_SECONDS", "12"))

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    async def analyze(self, snapshot: MarketSnapshot) -> TradeDecision:
        if not self.configured:
            return self._wait(snapshot, "OpenAI API key is not configured", "bridge-fallback")

        payload = {
            "model": self.model,
            "store": False,
            "instructions": SYSTEM_PROMPT,
            "input": json.dumps(snapshot.model_dump(mode="json"), separators=(",", ":")),
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "vardhani_trade_decision",
                    "strict": True,
                    "schema": DECISION_SCHEMA,
                }
            },
        }
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            response = await client.post("https://api.openai.com/v1/responses", headers=headers, json=payload)
            response.raise_for_status()
            data = response.json()

        output_text = data.get("output_text")
        if not output_text:
            output_text = self._extract_output_text(data)
        if not output_text:
            raise RuntimeError("OpenAI response did not contain structured output")

        decision = TradeDecision.model_validate_json(output_text)
        if decision.snapshotId != snapshot.snapshotId:
            raise RuntimeError("OpenAI decision snapshot mismatch")
        return decision

    @staticmethod
    def _extract_output_text(data: dict) -> str | None:
        for item in data.get("output", []):
            for content in item.get("content", []):
                if content.get("type") == "output_text" and content.get("text"):
                    return content["text"]
        return None

    def _wait(self, snapshot: MarketSnapshot, reason: str, model_version: str) -> TradeDecision:
        now = int(time.time() * 1000)
        return TradeDecision(
            decisionId=str(uuid.uuid4()),
            snapshotId=snapshot.snapshotId,
            decidedAtMillis=now,
            validForMillis=15_000,
            action="WAIT",
            confidence=0,
            regime="UNKNOWN",
            maximumSpotMovePct=0.10,
            reasons=[reason],
            riskFlags=["AI_UNAVAILABLE"],
            modelVersion=model_version,
            promptVersion="vardhani-v1",
        )

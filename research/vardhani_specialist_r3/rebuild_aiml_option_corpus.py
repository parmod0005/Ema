#!/usr/bin/env python3
"""Deterministically rebuild the original AIML historical option corpus.

Port of the recovered AndroidHistoricalCorpusImporter contract from:
  parmod0005/AIML @ d7fc67e2b53bdca7a5b1237d7e685ad2d3ba37a1
  blob d64f3a7af8b6b63bf7ca4b1de5e82f1fbf305b04

This program has no model-training or order-execution authority.  It reconstructs
research data only.  Missing historical market information is never fabricated.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path, PurePosixPath
from statistics import median
from typing import Any, Iterable

RAW_SHA256 = "bd7df469f6e7d95bee62a7c51d794a9119478cbc3c95b1e68debcafb4adc5b20"
REPORT_FORMAT = "AIML_HISTORICAL_IMPORT_REPORT_V1"
ROW_SCHEMA = "aiml-historical-option-row-v1"
MINIMUM_CONTRACT_COVERAGE = 0.90
MAX_MONEYNESS_STEPS = 5.0
MINIMUM_PRICE = 0.05
FLOOR_PRICE = 0.05
HORIZONS = (1, 3, 5, 15)
SPLITS = ("train", "validation", "test")
OPTION_FILE = re.compile(r"^NSE_FO_(\d+)_\d{2}-\d{2}-\d{4}\.json$")
INTEGRITY_REJECTIONS = {"SIZE_MISMATCH", "SHA256_MISMATCH"}
REJECTED_DISPOSITIONS = {"EMPTY", "REJECT_INVALID", "REJECT_STALE"}

EXPECTED_COUNTS = {
    "train": 2_156_451,
    "validation": 475_501,
    "test": 466_114,
}


@dataclass(frozen=True)
class Spot:
    close: float
    session_date: str


@dataclass(frozen=True)
class Candle:
    epoch_second: int
    source_timestamp: str
    session_date: str
    open: float
    high: float
    low: float
    close: float
    volume: float
    oi: float


@dataclass(frozen=True)
class Contract:
    token: str
    key: str
    symbol: str
    kind: str
    strike: float
    expiry: str
    lot_size: int


@dataclass(frozen=True)
class ParsedCandles:
    rows: list[Candle]
    duplicates: int
    invalid: int


@dataclass(frozen=True)
class QualityAssessment:
    rows: int
    sessions: int
    duplicates: int
    invalid: int
    flat_ohlc_ratio: float
    nonzero_volume_ratio: float
    nonzero_oi_ratio: float
    floor_price_ratio: float
    longest_same_close_run: int
    minimum_close: float
    maximum_close: float
    disposition: str


def norm(path: str) -> str:
    return str(PurePosixPath(path.replace("\\", "/")))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def finite(value: Any) -> float:
    x = float(value)
    if not math.isfinite(x):
        raise ValueError("non-finite numeric value")
    return x


def parse_timestamp(value: str) -> tuple[int, str]:
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        raise ValueError("timestamp must be timezone-aware")
    return int(dt.timestamp()), dt.date().isoformat()


def read_candles_bytes(data: bytes) -> ParsedCandles:
    payload = json.loads(data)
    if payload.get("status") != "success":
        raise ValueError("invalid historical response")
    candles = (payload.get("data") or {}).get("candles")
    if candles is None:
        raise ValueError("historical candles are missing")
    unique: dict[int, Candle] = {}
    duplicates = 0
    invalid = 0
    for raw in candles:
        try:
            if len(raw) < 7:
                raise ValueError("seven fields required")
            timestamp = str(raw[0])
            epoch_second, session_date = parse_timestamp(timestamp)
            row = Candle(
                epoch_second=epoch_second,
                source_timestamp=timestamp,
                session_date=session_date,
                open=finite(raw[1]),
                high=finite(raw[2]),
                low=finite(raw[3]),
                close=finite(raw[4]),
                volume=finite(raw[5]),
                oi=finite(raw[6]),
            )
            if min(row.open, row.low, row.close, row.volume, row.oi) < 0.0:
                raise ValueError("negative value")
            if row.high < max(row.open, row.low, row.close):
                raise ValueError("invalid high")
            if row.low > min(row.open, row.high, row.close):
                raise ValueError("invalid low")
            if epoch_second in unique:
                duplicates += 1
            unique[epoch_second] = row
        except Exception:
            invalid += 1
    return ParsedCandles(sorted(unique.values(), key=lambda r: r.epoch_second), duplicates, invalid)


def read_contracts_bytes(data: bytes) -> list[Contract]:
    payload = json.loads(data)
    if payload.get("status") != "success":
        raise ValueError("invalid contracts response")
    rows = payload.get("data")
    if rows is None:
        raise ValueError("contracts data is missing")
    result: list[Contract] = []
    for row in rows:
        kind = str(row.get("instrument_type", "")).upper()
        if kind not in {"CE", "PE"}:
            continue
        result.append(
            Contract(
                token=str(row["exchange_token"]),
                key=str(row["instrument_key"]),
                symbol=str(row["trading_symbol"]),
                kind=kind,
                strike=finite(row["strike_price"]),
                expiry=str(row["expiry"]),
                lot_size=int(row["lot_size"] if "lot_size" in row else row.get("minimum_lot", 0)),
            )
        )
    return result


def strike_step(contracts: list[Contract]) -> float:
    strikes = sorted({c.strike for c in contracts})
    gaps = [strikes[i] - strikes[i - 1] for i in range(1, len(strikes)) if strikes[i] - strikes[i - 1] > 0.0]
    return float(median(gaps)) if gaps else 50.0


def moneyness(contract: Contract, spot: float, step: float) -> tuple[float, str]:
    signed = (contract.strike - spot) / step
    if abs(signed) <= 0.5:
        name = "ATM"
    elif contract.kind == "CE":
        name = "ITM" if contract.strike < spot else "OTM"
    else:
        name = "ITM" if contract.strike > spot else "OTM"
    return signed, name


def labels(current: Candle, lookup: dict[int, Candle]) -> dict[str, float] | None:
    if current.close <= 0.0:
        return None
    result: dict[str, float] = {}
    for horizon in HORIZONS:
        future: list[Candle] = []
        for minute in range(1, horizon + 1):
            item = lookup.get(current.epoch_second + minute * 60)
            if item is None or item.session_date != current.session_date:
                return None
            future.append(item)
        result[f"future_return_{horizon}m"] = future[-1].close / current.close - 1.0
        result[f"mfe_{horizon}m"] = max(r.high for r in future) / current.close - 1.0
        result[f"mae_{horizon}m"] = min(r.low for r in future) / current.close - 1.0
    return result


def longest_flat_run(rows: list[Candle]) -> int:
    best = 0
    run = 0
    previous: Candle | None = None
    for row in rows:
        if previous is not None and previous.close == row.close and previous.epoch_second + 60 == row.epoch_second:
            run += 1
        else:
            run = 1
        best = max(best, run)
        previous = row
    return best


def assess_quality(parsed: ParsedCandles) -> QualityAssessment:
    rows = parsed.rows
    if not rows:
        return QualityAssessment(0, 0, parsed.duplicates, parsed.invalid, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, "EMPTY")
    count = float(len(rows))
    flat_ratio = sum(r.open == r.high == r.low == r.close for r in rows) / count
    volume_ratio = sum(r.volume > 0.0 for r in rows) / count
    oi_ratio = sum(r.oi > 0.0 for r in rows) / count
    floor_ratio = sum(r.close <= FLOOR_PRICE for r in rows) / count
    if parsed.invalid > 0 or parsed.duplicates > 0:
        disposition = "REJECT_INVALID"
    elif flat_ratio >= 0.98 and volume_ratio < 0.05:
        disposition = "REJECT_STALE"
    elif floor_ratio >= 0.80 or volume_ratio < 0.05 or oi_ratio < 0.05:
        disposition = "WATCH_SPARSE"
    else:
        disposition = "ACCEPT"
    return QualityAssessment(
        rows=len(rows),
        sessions=len({r.session_date for r in rows}),
        duplicates=parsed.duplicates,
        invalid=parsed.invalid,
        flat_ohlc_ratio=flat_ratio,
        nonzero_volume_ratio=volume_ratio,
        nonzero_oi_ratio=oi_ratio,
        floor_price_ratio=floor_ratio,
        longest_same_close_run=longest_flat_run(rows),
        minimum_close=min(r.close for r in rows),
        maximum_close=max(r.close for r in rows),
        disposition=disposition,
    )


def expiry_split(expiries: Iterable[str]) -> dict[str, str]:
    ordered = sorted(set(expiries))
    if len(ordered) < 3:
        return {e: "train" for e in ordered}
    train_count = max(1, int(len(ordered) * 0.70))
    validation_count = max(1, int(len(ordered) * 0.15))
    if train_count + validation_count >= len(ordered):
        train_count = len(ordered) - 2
        validation_count = 1
    result: dict[str, str] = {}
    for i, expiry in enumerate(ordered):
        if i < train_count:
            result[expiry] = "train"
        elif i < train_count + validation_count:
            result[expiry] = "validation"
        else:
            result[expiry] = "test"
    return result


def dump_compact(obj: dict[str, Any]) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def quality_payload(path: str, expiry: str, contract: Contract, q: QualityAssessment) -> dict[str, Any]:
    return {
        "path": path,
        "expiry": expiry,
        "symbol": contract.symbol,
        "token": contract.token,
        "option_type": contract.kind,
        "strike": contract.strike,
        "rows": q.rows,
        "sessions": q.sessions,
        "duplicates": q.duplicates,
        "invalid": q.invalid,
        "flat_ohlc_ratio": q.flat_ohlc_ratio,
        "nonzero_volume_ratio": q.nonzero_volume_ratio,
        "nonzero_oi_ratio": q.nonzero_oi_ratio,
        "floor_price_ratio": q.floor_price_ratio,
        "longest_same_close_run": q.longest_same_close_run,
        "minimum_close": q.minimum_close,
        "maximum_close": q.maximum_close,
        "disposition": q.disposition,
    }


def row_payload(
    row: Candle,
    spot: Spot,
    contract: Contract,
    split: str,
    step: float,
    signed_steps: float,
    money_name: str,
    future_labels: dict[str, float],
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "schema": ROW_SCHEMA,
        "execution_authority": False,
        "captured_at": row.source_timestamp,
        "exchange_date": row.session_date,
        "market": "nifty-50",
        "expiry": contract.expiry,
        "split": split,
        "instrument_key": contract.key,
        "exchange_token": contract.token,
        "trading_symbol": contract.symbol,
        "option_type": contract.kind,
        "strike": contract.strike,
        "lot_size": contract.lot_size,
        "spot": spot.close,
        "strike_step": step,
        "signed_moneyness_steps": signed_steps,
        "absolute_moneyness_steps": abs(signed_steps),
        "moneyness": money_name,
        "open": row.open,
        "high": row.high,
        "low": row.low,
        "close": row.close,
        "volume": row.volume,
        "oi": row.oi,
    }
    result.update(future_labels)
    return result


class Archive:
    def __init__(self, path: Path):
        self.path = path
        self.zf = zipfile.ZipFile(path)
        self.names = [norm(n) for n in self.zf.namelist() if not n.endswith("/")]
        manifests = [n for n in self.names if n.endswith("manifest.ndjson")]
        if len(manifests) != 1:
            raise RuntimeError("archive must contain exactly one manifest.ndjson")
        self.manifest_member = manifests[0]
        self.prefix = self.manifest_member[: -len("manifest.ndjson")]
        self.rel_to_member = {}
        for n in self.names:
            if self.prefix and n.startswith(self.prefix):
                self.rel_to_member[n[len(self.prefix):]] = n
            elif not self.prefix:
                self.rel_to_member[n] = n
        self.manifest, self.manifest_duplicates, self.manifest_malformed = self._read_manifest()

    def close(self):
        self.zf.close()

    def _read_manifest(self):
        entries: dict[str, dict[str, Any]] = {}
        duplicates = 0
        malformed = 0
        with self.zf.open(self.manifest_member) as fh:
            for raw in fh:
                if not raw.strip():
                    continue
                try:
                    obj = json.loads(raw)
                    key = norm(str(obj["path"]))
                    if key in entries:
                        duplicates += 1
                    entries[key] = {"bytes": int(obj["bytes"]), "sha256": str(obj["sha256"]).lower()}
                except Exception:
                    malformed += 1
        return entries, duplicates, malformed

    def read(self, relative: str) -> bytes:
        member = self.rel_to_member.get(norm(relative))
        if member is None:
            raise FileNotFoundError(relative)
        return self.zf.read(member)

    def verify(self, relative: str) -> str:
        relative = norm(relative)
        expected = self.manifest.get(relative)
        if expected is None:
            return "MANIFEST_ENTRY_MISSING"
        member = self.rel_to_member.get(relative)
        if member is None:
            return "MANIFEST_ENTRY_MISSING"
        info = self.zf.getinfo(member)
        if info.file_size != expected["bytes"]:
            return "SIZE_MISMATCH"
        if sha256_bytes(self.zf.read(member)) != expected["sha256"]:
            return "SHA256_MISMATCH"
        return "VERIFIED"

    def relative_files(self, prefix: str, suffix: str = "") -> list[str]:
        return sorted(r for r in self.rel_to_member if r.startswith(prefix) and (not suffix or r.endswith(suffix)))


def build(raw_zip: Path, output: Path) -> dict[str, Any]:
    if sha256_file(raw_zip) != RAW_SHA256:
        raise RuntimeError("raw ZIP SHA-256 mismatch; refusing corpus reconstruction")
    output.mkdir(parents=True, exist_ok=True)
    archive = Archive(raw_zip)
    verification = Counter()
    counters = Counter()
    dispositions = Counter()

    try:
        underlying_files = archive.relative_files("underlying/nifty-50/minutes-1/", ".json")
        option_contract_files = archive.relative_files("expired-options/nifty-50/", "/contracts.json")
        expiry_dirs = sorted({str(PurePosixPath(p).parent) for p in option_contract_files})

        spots: dict[int, Spot] = {}
        for path in underlying_files:
            integrity = archive.verify(path)
            verification[integrity] += 1
            if integrity in INTEGRITY_REJECTIONS:
                continue
            try:
                parsed = read_candles_bytes(archive.read(path))
            except Exception:
                counters["underlying_parse_failed_files"] += 1
                continue
            counters["underlying_duplicates"] += parsed.duplicates
            counters["underlying_invalid"] += parsed.invalid
            for row in parsed.rows:
                if row.epoch_second in spots:
                    counters["underlying_cross_file_duplicates"] += 1
                spots[row.epoch_second] = Spot(row.close, row.session_date)

        split_by_expiry = expiry_split(PurePosixPath(d).name for d in expiry_dirs)
        writers = {s: (output / f"{s}.ndjson").open("w", encoding="utf-8", newline="\n") for s in SPLITS}
        quality = (output / "file-quality.ndjson").open("w", encoding="utf-8", newline="\n")
        try:
            for directory in expiry_dirs:
                expiry = PurePosixPath(directory).name
                split = split_by_expiry[expiry]
                contracts_path = f"{directory}/contracts.json"
                integrity = archive.verify(contracts_path)
                verification[integrity] += 1
                if integrity in INTEGRITY_REJECTIONS:
                    counters["contract_files_integrity_rejected"] += 1
                    continue
                try:
                    contracts = read_contracts_bytes(archive.read(contracts_path))
                except Exception:
                    counters["contract_files_failed"] += 1
                    continue

                candle_prefix = f"{directory}/candles/"
                candle_files = [
                    p for p in archive.relative_files(candle_prefix, ".json")
                    if OPTION_FILE.fullmatch(PurePosixPath(p).name)
                ]
                coverage = 0.0 if not contracts else len(candle_files) / len(contracts)
                if coverage < MINIMUM_CONTRACT_COVERAGE:
                    counters["incomplete_expiries_rejected"] += 1
                    counters["incomplete_expiry_missing_contract_files"] += max(0, len(contracts) - len(candle_files))
                    continue

                step = strike_step(contracts)
                contracts_by_token = {c.token: c for c in contracts}
                for path in candle_files:
                    match = OPTION_FILE.fullmatch(PurePosixPath(path).name)
                    token = match.group(1) if match else None
                    contract = contracts_by_token.get(token or "")
                    if contract is None:
                        counters["files_without_contract"] += 1
                        continue
                    integrity = archive.verify(path)
                    verification[integrity] += 1
                    if integrity in INTEGRITY_REJECTIONS:
                        counters["integrity_rejected_files"] += 1
                        continue
                    try:
                        parsed = read_candles_bytes(archive.read(path))
                    except Exception:
                        counters["parse_failed_files"] += 1
                        continue
                    q = assess_quality(parsed)
                    dispositions[q.disposition] += 1
                    quality.write(dump_compact(quality_payload(path, expiry, contract, q)) + "\n")
                    if q.disposition in REJECTED_DISPOSITIONS:
                        continue
                    lookup = {r.epoch_second: r for r in parsed.rows}
                    for row in parsed.rows:
                        spot = spots.get(row.epoch_second)
                        if spot is None:
                            counters["rows_without_exact_spot"] += 1
                            continue
                        signed_steps, money_name = moneyness(contract, spot.close, step)
                        if abs(signed_steps) > MAX_MONEYNESS_STEPS:
                            counters["rows_outside_moneyness"] += 1
                            continue
                        if row.close < MINIMUM_PRICE or row.volume <= 0.0 or row.oi <= 0.0:
                            counters["inactive_rows"] += 1
                            continue
                        future = labels(row, lookup)
                        if future is None:
                            counters["rows_without_complete_labels"] += 1
                            continue
                        writers[split].write(
                            dump_compact(row_payload(row, spot, contract, split, step, signed_steps, money_name, future)) + "\n"
                        )
                        counters[f"rows_written_{split}"] += 1
        finally:
            quality.close()
            for fh in writers.values():
                fh.close()

        failures = []
        if not spots:
            failures.append("no underlying one-minute candles loaded")
        if not expiry_dirs:
            failures.append("no expired-option directories found")
        if sum(counters[f"rows_written_{s}"] for s in SPLITS) == 0:
            failures.append("no leakage-safe rows produced")

        report = {
            "format": REPORT_FORMAT,
            "status": "READY_FOR_RESEARCH" if not failures else "INSUFFICIENT_DATA",
            "generated_at": datetime.now().astimezone().isoformat(),
            "source_root": str(raw_zip),
            "market": "nifty-50",
            "row_schema": ROW_SCHEMA,
            "horizons_minutes": list(HORIZONS),
            "max_moneyness_steps": MAX_MONEYNESS_STEPS,
            "minimum_contract_coverage": MINIMUM_CONTRACT_COVERAGE,
            "manifest": {
                "provided": True,
                "unique_entries": len(archive.manifest),
                "duplicate_records": archive.manifest_duplicates,
                "malformed_records": archive.manifest_malformed,
                "verification": dict(sorted(verification.items())),
            },
            "underlying": {
                "files": len(underlying_files),
                "rows": len(spots),
                "sessions": len({s.session_date for s in spots.values()}),
            },
            "expiry_split": {
                s: sorted(e for e, split in split_by_expiry.items() if split == s) for s in SPLITS
            },
            "file_dispositions": dict(sorted(dispositions.items())),
            "counters": dict(sorted(counters.items())),
            "limitations": ["one-minute bars only", "no historical bid/ask spread", "no five-level depth"],
            "execution_authority": False,
            "failures": failures,
        }
        (output / "import-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

        parity = {
            s: counters.get(f"rows_written_{s}", 0) == EXPECTED_COUNTS[s] for s in SPLITS
        }
        parity_report = {
            "format": "VARDHANI_SPECIALIST_R3_ORIGINAL_CORPUS_PARITY_V1",
            "raw_sha256": RAW_SHA256,
            "row_count_parity": parity,
            "all_row_counts_match": all(parity.values()),
            "expected_counts": EXPECTED_COUNTS,
            "actual_counts": {s: counters.get(f"rows_written_{s}", 0) for s in SPLITS},
            "execution_authority": False,
        }
        (output / "original-corpus-parity.json").write_text(json.dumps(parity_report, indent=2) + "\n", encoding="utf-8")
        return {"report": report, "parity": parity_report}
    finally:
        archive.close()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("raw_zip", type=Path)
    ap.add_argument("output_dir", type=Path)
    args = ap.parse_args()
    result = build(args.raw_zip, args.output_dir)
    print(json.dumps(result["parity"], indent=2))
    return 0 if result["parity"]["all_row_counts_match"] else 2


if __name__ == "__main__":
    sys.exit(main())

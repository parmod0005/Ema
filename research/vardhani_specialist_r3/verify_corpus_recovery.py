#!/usr/bin/env python3
"""Fail-closed recovery verifier for the VARDHANI R3 specialist corpus.

This utility has NO training or execution authority.  Its only purpose is to prove
that the exact historical raw source and the regenerated AIML corpus/report match
the frozen recovery contract before any R3 teacher optimizer can be enabled.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath
from typing import Any

RAW_NAME = "aiml-upstox-history-1785255358705.zip"
RAW_SIZE = 58_999_099
RAW_SHA256 = "bd7df469f6e7d95bee62a7c51d794a9119478cbc3c95b1e68debcafb4adc5b20"
IMPORTER_REPO = "parmod0005/AIML"
IMPORTER_COMMIT = "d7fc67e2b53bdca7a5b1237d7e685ad2d3ba37a1"
IMPORTER_BLOB = "d64f3a7af8b6b63bf7ca4b1de5e82f1fbf305b04"
ROW_SCHEMA = "aiml-historical-option-row-v1"
REPORT_FORMAT = "AIML_HISTORICAL_IMPORT_REPORT_V1"

EXPECTED = {
    "manifest_unique_entries": 6067,
    "manifest_duplicate_records": 16,
    "manifest_malformed_records": 0,
    "manifest_verified": 5859,
    "underlying_files": 55,
    "underlying_rows": 422542,
    "underlying_sessions": 1131,
    "rows_written_train": 2156451,
    "rows_written_validation": 475501,
    "rows_written_test": 466114,
}

EXPECTED_EXPIRY_SPLIT = {
    "train": [
        "2024-10-03", "2024-10-10", "2024-10-17", "2024-10-24", "2024-10-31",
        "2024-11-07", "2024-11-14", "2024-11-21", "2024-11-28", "2024-12-05",
        "2024-12-12", "2024-12-19", "2024-12-26", "2025-01-02", "2025-01-09",
        "2025-01-16", "2025-01-23", "2025-01-30", "2025-02-06", "2025-02-13",
    ],
    "validation": ["2025-02-20", "2025-02-27", "2025-03-06", "2025-03-13"],
    "test": ["2025-03-20", "2025-03-27", "2025-04-03", "2025-04-09", "2025-04-17"],
}

R3_TRAIN_CUTOFF = "2024-12-31"
R3_VALIDATION_START = "2025-01-01"
R3_VALIDATION_END = "2025-12-31"

FROZEN_R2 = {
    "NIFTY_STAGE1": "47312e180dc900f5d05711aa862b7ef82ece6d0ae32a36c607f8efae305bb8e5",
    "NIFTY_STAGE2": "55791e80e6c10c9c5476c719b97b053b960145bb10894ef6208658eb4121bf1d",
    "NIFTY_STAGE3": "6378c36f558ff6a3b292b8d7f74418f7eb1ce38142c235f4cd96d8d8cc49363d",
    "SENSEX_STAGE1": "71b34d978f0efaef7e3faaf0d2509c893deb44b291f8b5125b6150da3034ba63",
    "SENSEX_STAGE2": "a8c97ba5a8df86d246def3d0f1a741aa0adc8afab7be4507a2fdd546142ecd0c",
}


def sha256_stream(stream) -> str:
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        h.update(block)
    return h.hexdigest()


def sha256_file(path: Path) -> str:
    with path.open("rb") as fh:
        return sha256_stream(fh)


def norm(name: str) -> str:
    return str(PurePosixPath(name.replace("\\", "/")))


def find_suffix(names: list[str], suffix: str) -> str | None:
    matches = [n for n in names if norm(n).endswith(suffix)]
    return matches[0] if len(matches) == 1 else None


def load_manifest(zf: zipfile.ZipFile, manifest_member: str) -> tuple[dict[str, dict[str, Any]], int, int]:
    entries: dict[str, dict[str, Any]] = {}
    duplicate_records = 0
    malformed_records = 0
    with zf.open(manifest_member, "r") as fh:
        for raw in fh:
            if not raw.strip():
                continue
            try:
                payload = json.loads(raw)
                path = norm(str(payload["path"]))
                entry = {"bytes": int(payload["bytes"]), "sha256": str(payload["sha256"]).lower()}
                if path in entries:
                    duplicate_records += 1
                entries[path] = entry
            except Exception:
                malformed_records += 1
    return entries, duplicate_records, malformed_records


def detect_prefix(names: list[str], manifest_member: str) -> str:
    suffix = "manifest.ndjson"
    if not norm(manifest_member).endswith(suffix):
        raise RuntimeError("manifest path is not canonical")
    prefix = norm(manifest_member)[: -len(suffix)]
    return prefix


def verify_manifest_members(
    zf: zipfile.ZipFile,
    names: list[str],
    prefix: str,
    entries: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    zip_name_set = {norm(n) for n in names if not n.endswith("/")}
    missing: list[str] = []
    size_mismatch: list[str] = []
    sha_mismatch: list[str] = []
    verified = 0

    # Full manifest verification is intentionally stronger than the historical importer,
    # which only touched the NIFTY files needed for the corpus build.
    for relative, expected in entries.items():
        member = norm(prefix + relative)
        if member not in zip_name_set:
            missing.append(relative)
            continue
        info = zf.getinfo(member)
        if int(info.file_size) != int(expected["bytes"]):
            size_mismatch.append(relative)
            continue
        with zf.open(member, "r") as fh:
            actual = sha256_stream(fh)
        if actual != expected["sha256"]:
            sha_mismatch.append(relative)
            continue
        verified += 1

    return {
        "manifest_entries_verified_full": verified,
        "manifest_entries_missing": len(missing),
        "manifest_size_mismatches": len(size_mismatch),
        "manifest_sha256_mismatches": len(sha_mismatch),
        "missing_examples": missing[:20],
        "size_mismatch_examples": size_mismatch[:20],
        "sha_mismatch_examples": sha_mismatch[:20],
    }


def source_layout_counts(names: list[str], prefix: str) -> dict[str, int]:
    rels = []
    for n in names:
        nn = norm(n)
        if prefix and nn.startswith(prefix):
            nn = nn[len(prefix):]
        rels.append(nn)
    return {
        "nifty_underlying_json_files": sum(
            r.startswith("underlying/nifty-50/minutes-1/") and r.endswith(".json") for r in rels
        ),
        "sensex_underlying_json_files": sum(
            r.startswith("underlying/sensex/minutes-1/") and r.endswith(".json") for r in rels
        ),
        "vix_underlying_json_files": sum(
            r.startswith("underlying/india-vix/minutes-1/") and r.endswith(".json") for r in rels
        ),
        "nifty_contract_files": sum(
            r.startswith("expired-options/nifty-50/") and r.endswith("/contracts.json") for r in rels
        ),
        "nifty_option_candle_files": sum(
            r.startswith("expired-options/nifty-50/") and "/candles/NSE_FO_" in r and r.endswith(".json") for r in rels
        ),
    }


def compare_report(report: dict[str, Any]) -> list[str]:
    failures: list[str] = []

    def eq(label: str, actual: Any, expected: Any) -> None:
        if actual != expected:
            failures.append(f"{label}: expected {expected!r}, got {actual!r}")

    eq("format", report.get("format"), REPORT_FORMAT)
    eq("status", report.get("status"), "READY_FOR_RESEARCH")
    eq("market", report.get("market"), "nifty-50")
    eq("row_schema", report.get("row_schema"), ROW_SCHEMA)
    eq("horizons_minutes", report.get("horizons_minutes"), [1, 3, 5, 15])
    eq("max_moneyness_steps", float(report.get("max_moneyness_steps", -1)), 5.0)
    eq("minimum_contract_coverage", float(report.get("minimum_contract_coverage", -1)), 0.9)
    eq("execution_authority", report.get("execution_authority"), False)

    manifest = report.get("manifest") or {}
    eq("manifest.unique_entries", manifest.get("unique_entries"), EXPECTED["manifest_unique_entries"])
    eq("manifest.duplicate_records", manifest.get("duplicate_records"), EXPECTED["manifest_duplicate_records"])
    eq("manifest.malformed_records", manifest.get("malformed_records"), EXPECTED["manifest_malformed_records"])
    eq("manifest.verification.VERIFIED", (manifest.get("verification") or {}).get("VERIFIED"), EXPECTED["manifest_verified"])

    underlying = report.get("underlying") or {}
    eq("underlying.files", underlying.get("files"), EXPECTED["underlying_files"])
    eq("underlying.rows", underlying.get("rows"), EXPECTED["underlying_rows"])
    eq("underlying.sessions", underlying.get("sessions"), EXPECTED["underlying_sessions"])

    counters = report.get("counters") or {}
    eq("counters.rows_written_train", counters.get("rows_written_train"), EXPECTED["rows_written_train"])
    eq("counters.rows_written_validation", counters.get("rows_written_validation"), EXPECTED["rows_written_validation"])
    eq("counters.rows_written_test", counters.get("rows_written_test"), EXPECTED["rows_written_test"])

    split = report.get("expiry_split") or {}
    for name, expected_values in EXPECTED_EXPIRY_SPLIT.items():
        eq(f"expiry_split.{name}", split.get(name), expected_values)

    limitations = report.get("limitations") or []
    for required in ["one-minute bars only", "no historical bid/ask spread", "no five-level depth"]:
        if required not in limitations:
            failures.append(f"limitations missing {required!r}")

    if report.get("failures") not in ([], None):
        failures.append(f"import report contains failures: {report.get('failures')!r}")
    return failures


def compare_r3_split_report(report: dict[str, Any]) -> list[str]:
    """Validate the stricter R3 chronology; original AIML splits are parity-only."""
    failures: list[str] = []
    if report.get("format") != "VARDHANI_SPECIALIST_R3_SPLIT_REPORT_V1":
        failures.append("R3 split report format mismatch")
    if report.get("train_max_exchange_date") != R3_TRAIN_CUTOFF:
        failures.append(f"R3 train_max_exchange_date must be {R3_TRAIN_CUTOFF}")
    if report.get("validation_min_exchange_date") != R3_VALIDATION_START:
        failures.append(f"R3 validation_min_exchange_date must be {R3_VALIDATION_START}")
    validation_max = report.get("validation_max_exchange_date")
    if validation_max is None or validation_max > R3_VALIDATION_END:
        failures.append(f"R3 validation_max_exchange_date must be <= {R3_VALIDATION_END}")
    if report.get("contains_2026_training_rows") is not False:
        failures.append("R3 split contains 2026 training rows")
    if report.get("contains_2026_validation_rows") is not False:
        failures.append("R3 split contains 2026 validation rows")
    if report.get("sealed_2026") is not True:
        failures.append("R3 split does not declare 2026 sealed")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_zip", type=Path)
    parser.add_argument("--rebuilt-import-report", type=Path)
    parser.add_argument("--r3-split-report", type=Path)
    parser.add_argument("--output", type=Path, default=Path("SPECIALIST_CORPUS_RECOVERY_GATE_REPORT.json"))
    args = parser.parse_args()

    failures: list[str] = []
    raw = args.raw_zip
    raw_exists = raw.is_file()
    actual_size = raw.stat().st_size if raw_exists else None
    actual_sha = sha256_file(raw) if raw_exists else None
    if not raw_exists:
        failures.append("raw source ZIP missing")
    else:
        if raw.name != RAW_NAME:
            failures.append(f"raw source filename mismatch: {raw.name}")
        if actual_size != RAW_SIZE:
            failures.append(f"raw source size mismatch: expected {RAW_SIZE}, got {actual_size}")
        if actual_sha != RAW_SHA256:
            failures.append(f"raw source SHA-256 mismatch: expected {RAW_SHA256}, got {actual_sha}")

    archive_audit: dict[str, Any] = {}
    if raw_exists and actual_sha == RAW_SHA256:
        try:
            with zipfile.ZipFile(raw) as zf:
                names = zf.namelist()
                manifest_member = find_suffix(names, "manifest.ndjson")
                if manifest_member is None:
                    failures.append("archive must contain exactly one manifest.ndjson")
                else:
                    prefix = detect_prefix(names, manifest_member)
                    entries, duplicates, malformed = load_manifest(zf, manifest_member)
                    archive_audit.update(
                        {
                            "zip_entries": len(names),
                            "manifest_member": manifest_member,
                            "archive_prefix": prefix,
                            "manifest_unique_entries": len(entries),
                            "manifest_duplicate_records": duplicates,
                            "manifest_malformed_records": malformed,
                            "layout": source_layout_counts(names, prefix),
                        }
                    )
                    if len(entries) != EXPECTED["manifest_unique_entries"]:
                        failures.append("manifest unique-entry count mismatch")
                    if duplicates != EXPECTED["manifest_duplicate_records"]:
                        failures.append("manifest duplicate-record count mismatch")
                    if malformed != EXPECTED["manifest_malformed_records"]:
                        failures.append("manifest malformed-record count mismatch")
                    member_audit = verify_manifest_members(zf, names, prefix, entries)
                    archive_audit.update(member_audit)
                    if member_audit["manifest_entries_missing"]:
                        failures.append("one or more manifest members are missing")
                    if member_audit["manifest_size_mismatches"]:
                        failures.append("one or more manifest member sizes mismatch")
                    if member_audit["manifest_sha256_mismatches"]:
                        failures.append("one or more manifest member SHA-256 values mismatch")
        except Exception as exc:
            failures.append(f"archive verification exception: {exc}")

    report_failures: list[str] = []
    report_present = bool(args.rebuilt_import_report and args.rebuilt_import_report.is_file())
    if report_present:
        try:
            report_failures = compare_report(json.loads(args.rebuilt_import_report.read_text(encoding="utf-8")))
        except Exception as exc:
            report_failures = [f"could not parse rebuilt import report: {exc}"]
        failures.extend(report_failures)

    r3_failures: list[str] = []
    r3_present = bool(args.r3_split_report and args.r3_split_report.is_file())
    if r3_present:
        try:
            r3_failures = compare_r3_split_report(json.loads(args.r3_split_report.read_text(encoding="utf-8")))
        except Exception as exc:
            r3_failures = [f"could not parse R3 split report: {exc}"]
        failures.extend(r3_failures)

    source_gate = raw_exists and actual_sha == RAW_SHA256 and not [f for f in failures if f.startswith("raw source") or f.startswith("archive") or "manifest" in f]
    original_corpus_parity_gate = report_present and not report_failures
    r3_chronology_gate = r3_present and not r3_failures
    teacher_optimizer_unlocked = bool(source_gate and original_corpus_parity_gate and r3_chronology_gate and not failures)

    result = {
        "format": "VARDHANI_SPECIALIST_R3_CORPUS_RECOVERY_GATE_REPORT_V1",
        "raw_source": {
            "path": str(raw),
            "expected_name": RAW_NAME,
            "expected_size_bytes": RAW_SIZE,
            "actual_size_bytes": actual_size,
            "expected_sha256": RAW_SHA256,
            "actual_sha256": actual_sha,
        },
        "importer_anchor": {
            "repository": IMPORTER_REPO,
            "commit": IMPORTER_COMMIT,
            "blob_sha": IMPORTER_BLOB,
            "row_schema": ROW_SCHEMA,
        },
        "archive_audit": archive_audit,
        "original_corpus_report_present": report_present,
        "original_corpus_report_failures": report_failures,
        "r3_split_report_present": r3_present,
        "r3_split_failures": r3_failures,
        "frozen_r2_anchors": FROZEN_R2,
        "source_gate_pass": source_gate,
        "original_corpus_parity_gate_pass": original_corpus_parity_gate,
        "r3_chronology_gate_pass": r3_chronology_gate,
        "teacher_optimizer_unlocked": teacher_optimizer_unlocked,
        "real_orders": "DISABLED",
        "failures": failures,
    }
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if teacher_optimizer_unlocked else 2


if __name__ == "__main__":
    sys.exit(main())

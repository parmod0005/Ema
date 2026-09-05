#!/usr/bin/env python3
"""One-shot, fail-closed recovery pipeline for VARDHANI Specialist R3.

This script does not train a model. It reconstructs the original AIML corpus for parity,
projects the stricter R3 chronology, runs the frozen source/parity/chronology gate, and
emits a teacher-training unlock token only if every gate passes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

import build_r3_option_split
import rebuild_aiml_option_corpus
import verify_corpus_recovery as gate_v1
import verify_corpus_recovery_v2 as gate_v2


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def fail(message: str) -> None:
    raise RuntimeError(message)


def source_preflight(raw_zip: Path) -> dict:
    if not raw_zip.is_file():
        fail(f"raw source ZIP missing: {raw_zip}")
    actual_size = raw_zip.stat().st_size
    actual_sha = sha256(raw_zip)
    if raw_zip.name != gate_v1.RAW_NAME:
        fail(f"raw source filename mismatch: expected {gate_v1.RAW_NAME}, got {raw_zip.name}")
    if actual_size != gate_v1.RAW_SIZE:
        fail(f"raw source size mismatch: expected {gate_v1.RAW_SIZE}, got {actual_size}")
    if actual_sha != gate_v1.RAW_SHA256:
        fail(f"raw source SHA-256 mismatch: expected {gate_v1.RAW_SHA256}, got {actual_sha}")
    return {"filename": raw_zip.name, "bytes": actual_size, "sha256": actual_sha}


def verify_original_report(report: dict) -> None:
    failures = gate_v1.compare_report(report)
    if failures:
        fail("original corpus parity report failed: " + " | ".join(failures))


def verify_r3_report(report: dict) -> None:
    failures = gate_v2.compare_r3_split_report_v2(report)
    if failures:
        fail("R3 chronology report failed: " + " | ".join(failures))


def build_unlock_token(raw_zip: Path, corpus_dir: Path, r3_dir: Path, gate_report: dict) -> dict:
    split_report = json.loads((r3_dir / "R3_SPLIT_REPORT.json").read_text(encoding="utf-8"))
    import_report_path = corpus_dir / "import-report.json"
    parity_path = corpus_dir / "original-corpus-parity.json"
    split_report_path = r3_dir / "R3_SPLIT_REPORT.json"
    return {
        "format": "VARDHANI_SPECIALIST_R3_TEACHER_UNLOCK_TOKEN_V1",
        "status": "TEACHER_HISTORICAL_TRAINING_UNLOCKED_ONLY",
        "training_scope": {
            "train_end": gate_v1.R3_TRAIN_CUTOFF,
            "validation_start": gate_v1.R3_VALIDATION_START,
            "validation_end": gate_v1.R3_VALIDATION_END,
            "sealed_2026": True,
            "prospective_authority": False,
            "promotion_authority": False,
            "execution_authority": False,
        },
        "raw_source": {
            "filename": raw_zip.name,
            "bytes": raw_zip.stat().st_size,
            "sha256": sha256(raw_zip),
        },
        "original_corpus": {
            "import_report_sha256": sha256(import_report_path),
            "parity_report_sha256": sha256(parity_path),
            "train_ndjson_sha256": sha256(corpus_dir / "train.ndjson"),
            "validation_ndjson_sha256": sha256(corpus_dir / "validation.ndjson"),
            "test_ndjson_sha256": sha256(corpus_dir / "test.ndjson"),
        },
        "r3_projection": {
            "split_report_sha256": sha256(split_report_path),
            "source_canonical_row_stream_sha256": split_report["source_canonical_row_stream_sha256"],
            "train_file_sha256": split_report["outputs"]["options_train_through_2024.ndjson"]["sha256"],
            "train_canonical_row_stream_sha256": split_report["outputs"]["options_train_through_2024.ndjson"]["canonical_row_stream_sha256"],
            "validation_file_sha256": split_report["outputs"]["options_validation_2025.ndjson"]["sha256"],
            "validation_canonical_row_stream_sha256": split_report["outputs"]["options_validation_2025.ndjson"]["canonical_row_stream_sha256"],
            "measured_counters": split_report["counters"],
        },
        "importer_anchor": gate_report["importer_anchor"],
        "frozen_r2_anchors": gate_report["frozen_r2_anchors"],
        "hard_rules": [
            "Frozen R2 remains untouched.",
            "Teacher optimizer may consume only the R3 <=2024 training projection.",
            "2025 is validation-only.",
            "2026 remains sealed.",
            "No fabricated bid/ask, depth, Greeks, index volume/OI, SENSEX options or D30.",
            "This token does not authorize promotion or broker execution.",
        ],
        "real_orders": "DISABLED",
    }


def run(raw_zip: Path, work_dir: Path, *, clean: bool) -> dict:
    source = source_preflight(raw_zip)
    if clean and work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)

    corpus_dir = work_dir / "original_aiml_corpus_parity"
    r3_dir = work_dir / "r3_projection"
    corpus_dir.mkdir(parents=True, exist_ok=True)
    r3_dir.mkdir(parents=True, exist_ok=True)

    rebuilt = rebuild_aiml_option_corpus.build(raw_zip, corpus_dir)
    if not rebuilt["parity"].get("all_row_counts_match"):
        fail("original corpus row-count parity failed")
    verify_original_report(rebuilt["report"])

    r3_report = build_r3_option_split.build(corpus_dir, r3_dir)
    verify_r3_report(r3_report)

    gate_report_path = work_dir / "SPECIALIST_CORPUS_RECOVERY_GATE_REPORT.json"
    # Run the exact final gate in-process using the same functions as the CLI.
    raw_failures = []
    if source["sha256"] != gate_v1.RAW_SHA256:
        raw_failures.append("raw source SHA mismatch")
    report_failures = gate_v1.compare_report(rebuilt["report"])
    r3_failures = gate_v2.compare_r3_split_report_v2(r3_report)
    gate_report = {
        "format": "VARDHANI_SPECIALIST_R3_CORPUS_RECOVERY_GATE_REPORT_V2",
        "raw_source": source,
        "importer_anchor": {
            "repository": gate_v1.IMPORTER_REPO,
            "commit": gate_v1.IMPORTER_COMMIT,
            "blob_sha": gate_v1.IMPORTER_BLOB,
            "row_schema": gate_v1.ROW_SCHEMA,
        },
        "original_corpus_report_failures": report_failures,
        "r3_split_failures": r3_failures,
        "frozen_r2_anchors": gate_v1.FROZEN_R2,
        "source_gate_pass": not raw_failures,
        "original_corpus_parity_gate_pass": not report_failures,
        "r3_chronology_gate_pass": not r3_failures,
        "teacher_optimizer_unlocked": not raw_failures and not report_failures and not r3_failures,
        "real_orders": "DISABLED",
        "failures": raw_failures + report_failures + r3_failures,
    }
    gate_report_path.write_text(json.dumps(gate_report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if not gate_report["teacher_optimizer_unlocked"]:
        fail("final teacher gate did not unlock")

    token = build_unlock_token(raw_zip, corpus_dir, r3_dir, gate_report)
    token_path = work_dir / "R3_TEACHER_UNLOCK_TOKEN.json"
    token_path.write_text(json.dumps(token, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    result = {
        "status": "PASS_TEACHER_HISTORICAL_TRAINING_UNLOCKED_ONLY",
        "gate_report": str(gate_report_path),
        "gate_report_sha256": sha256(gate_report_path),
        "unlock_token": str(token_path),
        "unlock_token_sha256": sha256(token_path),
        "real_orders": "DISABLED",
    }
    (work_dir / "RECOVERY_RUN_RESULT.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_zip", type=Path)
    parser.add_argument("work_dir", type=Path)
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args.raw_zip, args.work_dir, clean=args.clean)
    except Exception as exc:
        print(json.dumps({"status": "FAIL_CLOSED", "error": str(exc), "real_orders": "DISABLED"}, indent=2))
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())

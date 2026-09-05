#!/usr/bin/env python3
"""Fail-closed recovery gate for the exact VARDHANI MTFN V0.2 package.

This utility has no training, promotion, prospective-evidence or order-execution authority.
It accepts package bytes only when the SHA-256 equals the preserved V0.2 checksum, rejects
unsafe ZIP members, extracts to a clean destination, and emits a complete per-file SHA ledger.
It deliberately does NOT infer an architecture or parameter count from filenames.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import stat
import sys
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

PACKAGE_NAME = "Vardhani-AI-Brain-MTFN-v0.2-research.zip"
PACKAGE_SHA256 = "961066969cce7a84f533a83316087ff7636d635dcfa4e1133b1d9d463b592211"
REPORT_FORMAT = "VARDHANI_SPECIALIST_R3_V02_PACKAGE_RECOVERY_REPORT_V1"

SOURCE_SUFFIXES = {".py", ".kt", ".java", ".cpp", ".cc", ".c", ".h", ".hpp", ".rs"}
CONFIG_SUFFIXES = {".json", ".yaml", ".yml", ".toml", ".ini", ".cfg"}
CHECKPOINT_SUFFIXES = {".pt", ".pth", ".onnx", ".tflite", ".safetensors", ".bin", ".npz"}
DOC_SUFFIXES = {".md", ".txt", ".rst"}
ARCH_HINTS = ("mtfn", "model", "brain", "architect", "network", "train", "teacher")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalized_member(name: str) -> PurePosixPath:
    p = PurePosixPath(name.replace("\\", "/"))
    if p.is_absolute() or not p.parts or any(part in ("", ".", "..") for part in p.parts):
        raise RuntimeError(f"UNSAFE_ZIP_PATH:{name}")
    return p


def member_is_symlink(info: zipfile.ZipInfo) -> bool:
    mode = (info.external_attr >> 16) & 0xFFFF
    return stat.S_ISLNK(mode)


def classify(path: str) -> str:
    suffix = PurePosixPath(path).suffix.lower()
    if suffix in SOURCE_SUFFIXES:
        return "SOURCE"
    if suffix in CONFIG_SUFFIXES:
        return "CONFIG"
    if suffix in CHECKPOINT_SUFFIXES:
        return "CHECKPOINT_OR_MODEL_ASSET"
    if suffix in DOC_SUFFIXES:
        return "DOCUMENTATION"
    return "OTHER"


def architecture_hint(path: str) -> bool:
    low = path.lower()
    return any(token in low for token in ARCH_HINTS)


def inspect_candidate(candidate: Path, destination: Path, expected_sha256: str = PACKAGE_SHA256) -> dict[str, Any]:
    candidate = candidate.resolve()
    destination = destination.resolve()
    actual_sha = sha256_file(candidate)
    base = {
        "format": REPORT_FORMAT,
        "target_package": PACKAGE_NAME,
        "expected_sha256": expected_sha256,
        "actual_sha256": actual_sha,
        "candidate_bytes": candidate.stat().st_size,
        "execution_authority": False,
        "teacher_optimizer_start_allowed": False,
        "model_construction_allowed": False,
    }
    if actual_sha != expected_sha256:
        return {
            **base,
            "status": "REJECT_SHA256_MISMATCH",
            "exact_package_identity_proven": False,
            "extracted": False,
            "files": [],
        }

    seen: set[str] = set()
    records: list[dict[str, Any]] = []
    payloads: list[tuple[PurePosixPath, bytes]] = []
    with zipfile.ZipFile(candidate) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            if member_is_symlink(info):
                raise RuntimeError(f"UNSAFE_ZIP_SYMLINK:{info.filename}")
            p = normalized_member(info.filename)
            key = str(p)
            if key in seen:
                raise RuntimeError(f"DUPLICATE_ZIP_MEMBER:{key}")
            seen.add(key)
            data = zf.read(info)
            if len(data) != info.file_size:
                raise RuntimeError(f"ZIP_MEMBER_SIZE_MISMATCH:{key}")
            payloads.append((p, data))
            records.append({
                "path": key,
                "bytes": len(data),
                "sha256": sha256_bytes(data),
                "classification": classify(key),
                "architecture_name_hint": architecture_hint(key),
            })

    if not records:
        raise RuntimeError("EMPTY_EXACT_PACKAGE")

    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=False)
    for rel, data in payloads:
        out = destination.joinpath(*rel.parts)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)
        if sha256_file(out) != sha256_bytes(data):
            raise RuntimeError(f"POST_EXTRACT_HASH_MISMATCH:{rel}")

    records.sort(key=lambda x: x["path"])
    source_count = sum(x["classification"] == "SOURCE" for x in records)
    checkpoint_count = sum(x["classification"] == "CHECKPOINT_OR_MODEL_ASSET" for x in records)
    hinted = [x["path"] for x in records if x["architecture_name_hint"]]
    return {
        **base,
        "status": "EXACT_PACKAGE_RECOVERED_ARCHITECTURE_REVIEW_REQUIRED",
        "exact_package_identity_proven": True,
        "extracted": True,
        "extraction_root": str(destination),
        "file_count": len(records),
        "source_file_count": source_count,
        "checkpoint_or_model_asset_count": checkpoint_count,
        "architecture_name_hint_paths": hinted,
        "files": records,
        "next_gate": "Hash-verify the actual architecture source subset, recover exact parameter count and training contract, then create VARDHANI_SPECIALIST_R3_ARCHITECTURE_MANIFEST_V1. Do not train before that manifest passes teacher preflight.",
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("candidate_zip", type=Path)
    ap.add_argument("--destination", type=Path, default=Path("R3_V02_EXACT_PACKAGE"))
    ap.add_argument("--report", type=Path, default=Path("R3_V02_PACKAGE_RECOVERY_REPORT.json"))
    args = ap.parse_args()

    try:
        report = inspect_candidate(args.candidate_zip, args.destination)
    except Exception as exc:
        report = {
            "format": REPORT_FORMAT,
            "target_package": PACKAGE_NAME,
            "expected_sha256": PACKAGE_SHA256,
            "status": "REJECT_RECOVERY_ERROR",
            "error": f"{type(exc).__name__}:{exc}",
            "exact_package_identity_proven": False,
            "teacher_optimizer_start_allowed": False,
            "model_construction_allowed": False,
            "execution_authority": False,
        }
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report.get("exact_package_identity_proven") else 2


if __name__ == "__main__":
    sys.exit(main())

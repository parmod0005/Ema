from __future__ import annotations

import math
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import numpy as np

import build_full_market_corpus as base
import build_full_market_corpus_v2 as build2
import verify_teacher_preflight as pre
import verify_teacher_preflight_v2 as verify2
import verify_teacher_preflight_v2_strict as strict

IST = ZoneInfo("Asia/Kolkata")


class FullMarketV2Tests(unittest.TestCase):
    def row(self, dt: datetime, price: float) -> base.MarketRow:
        sm = dt.hour * 60 + dt.minute - 555
        return base.MarketRow(
            epoch_second=int(dt.timestamp()),
            source_timestamp=dt.isoformat(),
            day=dt.date().isoformat(),
            session_minute=sm,
            open=price - 0.25,
            high=price + 1.0,
            low=price - 1.0,
            close=price,
        )

    def test_ist_contract_normalizes_utc_timestamp(self):
        day, sm = build2.ist_contract("2024-12-30T03:45:00+00:00")
        self.assertEqual(day, "2024-12-30")
        self.assertEqual(sm, 0)

    def test_split_masks_physically_seal_2026(self):
        rows = [
            self.row(datetime(2024, 12, 31, 9, 15, tzinfo=IST), 100.0),
            self.row(datetime(2025, 1, 2, 9, 15, tzinfo=IST), 101.0),
            self.row(datetime(2026, 1, 2, 9, 15, tzinfo=IST), 102.0),
        ]
        tr, va, sealed, leak = build2.split_masks(rows)
        self.assertEqual(tr.tolist(), [True, False, False])
        self.assertEqual(va.tolist(), [False, True, False])
        self.assertEqual(sealed.tolist(), [False, False, True])
        self.assertEqual(leak, {
            "train_2025_rows": 0,
            "train_2026_rows": 0,
            "validation_pre_2025_rows": 0,
            "validation_2026_rows": 0,
        })

    def test_vix_alignment_never_reads_future_vix(self):
        t0 = datetime(2024, 12, 30, 9, 15, tzinfo=IST)
        market = [self.row(t0, 100.0), self.row(t0 + timedelta(minutes=1), 101.0)]
        vix = [self.row(t0, 20.0), self.row(t0 + timedelta(minutes=2), 30.0)]
        out = base.align_vix(market, vix)
        self.assertAlmostEqual(float(out[0, 0]), 20.0 / 50.0)
        self.assertAlmostEqual(float(out[1, 0]), 20.0 / 50.0)
        self.assertEqual(float(out[0, 4]), 1.0)
        self.assertEqual(float(out[1, 4]), 0.0)
        self.assertEqual(float(out[1, 6]), 1.0)

    def test_actual_feature_builder_future_mutation_is_zero(self):
        market = []
        vix = []
        start = datetime(2024, 12, 27, 9, 15, tzinfo=IST)
        for session in range(2):
            d0 = start + timedelta(days=session * 3)
            for minute in range(350):
                dt = d0 + timedelta(minutes=minute)
                p = 20000.0 + session * 40.0 + 5.0 * math.sin(minute / 17.0) + minute * 0.01
                market.append(self.row(dt, p))
                vix.append(self.row(dt, 14.0 + 0.2 * math.sin(minute / 23.0)))
        self.assertEqual(base.feature_mutation_audit(market, vix), 0.0)

    def valid_market_npz(self, root: Path, *, day: str = "2024-12-30"):
        d0 = datetime.fromisoformat(day + "T09:15:00").replace(tzinfo=IST)
        ts = np.asarray([int((d0 + timedelta(minutes=i)).timestamp()) for i in range(3)], dtype=np.int64)
        arrays = {
            "TS_EPOCH_SECOND": ts,
            "DAY": np.asarray([day] * 3, dtype="U10"),
            "SESSION_MIN": np.asarray([0, 1, 2], dtype=np.int16),
            "CLOSE": np.asarray([100.0, 101.0, 102.0], dtype=np.float64),
            "XLO": np.zeros((3, 74), dtype=np.float32),
            "X15": np.zeros((3, 23), dtype=np.float32),
        }
        path = root / "m.npz"
        np.savez_compressed(path, **arrays)
        spec = {
            "path": path.name,
            "market": "NIFTY",
            "split": "train",
            "rows": 3,
            "bytes": path.stat().st_size,
            "sha256": pre.sha256(path),
            "canonical_row_stream_sha256": verify2.canonical_npz_digest(arrays),
            "min_day": day,
            "max_day": day,
        }
        return path, spec, arrays

    def test_physical_npz_contract_passes(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _, spec, _ = self.valid_market_npz(root)
            failures = []
            ts = verify2.verify_npz_artifact(root, spec, "NIFTY", "train", failures, "x")
            self.assertIsNotNone(ts)
            self.assertEqual(failures, [])

    def test_physical_npz_day_tamper_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path, spec, arrays = self.valid_market_npz(root)
            arrays["DAY"] = np.asarray(["2025-01-02"] * 3, dtype="U10")
            np.savez_compressed(path, **arrays)
            spec.update({
                "bytes": path.stat().st_size,
                "sha256": pre.sha256(path),
                "canonical_row_stream_sha256": verify2.canonical_npz_digest(arrays),
                "min_day": "2025-01-02",
                "max_day": "2025-01-02",
            })
            failures = []
            verify2.verify_npz_artifact(root, spec, "NIFTY", "train", failures, "x")
            self.assertTrue(any("DAY does not equal" in f or "after 2024" in f for f in failures))

    def test_strict_conservation_rejects_missing_market_rows(self):
        manifest = {
            "files": {
                "nifty_train": {"rows": 100}, "nifty_validation": {"rows": 100},
                "sensex_train": {"rows": 100}, "sensex_validation": {"rows": 100},
            },
            "sealed_2026_rows_rejected": 10,
        }
        failures = []
        strict.verify_full_market_v2_strict(manifest, Path("."), failures)
        self.assertTrue(any("row conservation failed" in f for f in failures))


if __name__ == "__main__":
    unittest.main()

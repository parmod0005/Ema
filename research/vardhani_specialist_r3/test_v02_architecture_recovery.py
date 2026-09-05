from __future__ import annotations

import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

import recover_v02_architecture_package as recover


class V02ArchitectureRecoveryTests(unittest.TestCase):
    def make_zip(self, root: Path, members: dict[str, bytes]) -> Path:
        p = root / "candidate.zip"
        with zipfile.ZipFile(p, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, data in members.items():
                zf.writestr(name, data)
        return p

    def test_wrong_sha_never_extracts(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            candidate = self.make_zip(root, {"model.py": b"class Brain: pass\n"})
            out = root / "out"
            report = recover.inspect_candidate(candidate, out, expected_sha256="0" * 64)
            self.assertEqual(report["status"], "REJECT_SHA256_MISMATCH")
            self.assertFalse(report["exact_package_identity_proven"])
            self.assertFalse(report["model_construction_allowed"])
            self.assertFalse(out.exists())

    def test_matching_hash_extracts_and_hashes_every_member(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            candidate = self.make_zip(root, {
                "src/mtfn_model.py": b"class MTFN: pass\n",
                "config/train.json": b"{\"seed\":1}\n",
                "weights/model.pt": b"not-a-real-checkpoint-test-bytes",
            })
            expected = recover.sha256_file(candidate)
            out = root / "out"
            report = recover.inspect_candidate(candidate, out, expected_sha256=expected)
            self.assertEqual(report["status"], "EXACT_PACKAGE_RECOVERED_ARCHITECTURE_REVIEW_REQUIRED")
            self.assertTrue(report["exact_package_identity_proven"])
            self.assertFalse(report["model_construction_allowed"])
            self.assertEqual(report["file_count"], 3)
            by_path = {x["path"]: x for x in report["files"]}
            self.assertEqual(by_path["src/mtfn_model.py"]["classification"], "SOURCE")
            self.assertTrue(by_path["src/mtfn_model.py"]["architecture_name_hint"])
            for rel, spec in by_path.items():
                data = (out / rel).read_bytes()
                self.assertEqual(hashlib.sha256(data).hexdigest(), spec["sha256"])

    def test_path_traversal_is_rejected_even_when_archive_hash_matches(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            candidate = self.make_zip(root, {"../escape.py": b"bad\n"})
            expected = recover.sha256_file(candidate)
            with self.assertRaisesRegex(RuntimeError, "UNSAFE_ZIP_PATH"):
                recover.inspect_candidate(candidate, root / "out", expected_sha256=expected)
            self.assertFalse((root.parent / "escape.py").exists())


if __name__ == "__main__":
    unittest.main()

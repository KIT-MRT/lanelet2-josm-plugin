#!/usr/bin/env python3
"""Re-run the lanelet2 backend golden corpus and diff against stored outputs.

Each case lives under cases/<backend>/<case-name>/ and is executed the same
way the JOSM plugin will invoke the backends: ``python <script.py> <argv...>``.

Usage:
    run_golden.py [python_interpreter]
    run_golden.py --python /path/to/python
    LL2_BACKENDS_PYTHON=/path/to/python run_golden.py

Defaults:
    python:        /tmp/ll2-backend-venv/bin/python
    backends dir:  $LL2_BACKENDS_DIR, else the staged OSS backends next to
                   this tooling tree.

Exit status is 0 only when every compared artefact matches after
normalisation (see README.md and normalize_text / normalize_osm).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

CORPUS_ROOT = Path(__file__).resolve().parent
DEFAULT_PYTHON = "/tmp/ll2-backend-venv/bin/python"
# testdata/golden -> testdata -> JOSM_LL2_Plugin -> tooling root
DEFAULT_BACKENDS_RELATIVE = (
    CORPUS_ROOT.parents[2]
    / "JOSM_lanelet2_editing_scripts"
    / "lanelet2_mapping_backends"
    / "lanelet2_mapping_backends"
)
FALLBACK_BACKENDS = Path(
    "/ll2_tooling_root/JOSM_lanelet2_editing_scripts/"
    "lanelet2_mapping_backends/lanelet2_mapping_backends"
)

# Merge-introduced git metadata. Values (and even presence) depend on whether
# the input files are tracked in a git repo and on that repo's history, so they
# are stripped before comparison. See README.md.
GIT_TAG_KEYS = ("last_modified", "committer", "commit_message", "all_committers")
GIT_TAG_RE = re.compile(
    r"""^[ \t]*<tag k=(["'])(?:%s)\1 v=(["']).*?\2\s*/>\s*\n?"""
    % "|".join(GIT_TAG_KEYS),
    re.MULTILINE,
)
# logging.basicConfig format used by merge_osm_files / split_merged_osm_file
LOG_TIMESTAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3}")


def default_backends_dir() -> Path:
    env = os.environ.get("LL2_BACKENDS_DIR", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    if DEFAULT_BACKENDS_RELATIVE.is_dir():
        return DEFAULT_BACKENDS_RELATIVE.resolve()
    return FALLBACK_BACKENDS


def parse_cmd_txt(path: Path) -> List[str]:
    """Parse cmd.txt: one argv token per line, '#' comments and blanks skipped."""
    tokens: List[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        tokens.append(line)
    return tokens


def load_case(case_dir: Path) -> dict:
    meta_path = case_dir / "case.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}
    cmd_path = case_dir / "cmd.txt"
    if cmd_path.is_file() and "argv" not in meta:
        meta["argv"] = parse_cmd_txt(cmd_path)
    if "backend" not in meta:
        meta["backend"] = case_dir.parent.name
    meta.setdefault("setup", [])
    meta.setdefault("output_files", [])
    meta.setdefault("output_dirs", [])
    meta.setdefault("inplace", False)
    meta.setdefault("also_compare", {})
    meta.setdefault("expect_success", True)
    return meta


def discover_cases(corpus: Path) -> List[Path]:
    cases_root = corpus / "cases"
    found = []
    for cmd in sorted(cases_root.glob("*/*/cmd.txt")):
        found.append(cmd.parent)
    return found


def replace_paths(text: str, replacements: Sequence[Tuple[str, str]]) -> str:
    # Longest first so a prefix of another path cannot eat the longer match.
    ordered = sorted(replacements, key=lambda kv: len(kv[0]), reverse=True)
    for src, dst in ordered:
        if not src:
            continue
        text = text.replace(src, dst)
        # logging / Path may emit the same location with a trailing slash
        if not src.endswith(os.sep):
            text = text.replace(src + os.sep, dst + "/")
    return text


def normalize_text(text: str, path_replacements: Sequence[Tuple[str, str]]) -> str:
    """Normalise captured stdout/stderr for stable comparison.

    - Log timestamps from merge/split ``%(asctime)s`` (change every run).
    - Absolute paths of the corpus, the per-run work directory, the python
      interpreter, and the backends directory (``Path.resolve()`` / logging).
    """
    text = LOG_TIMESTAMP_RE.sub("<TIMESTAMP>", text)
    text = replace_paths(text, path_replacements)
    return text


def normalize_osm(text: str, path_replacements: Sequence[Tuple[str, str]]) -> str:
    """Normalise an OSM XML document for stable comparison.

    Git commit tags written by merge_osm_files are stripped because they
    appear only when the input path is inside a git repo with history for
    that file, and their values change when history changes. Absolute paths
    that leak into tag values (file_origin, peerA/peerB, MERGE_OUTPUT-style
    resolved locations) are rewritten with the same placeholders as logs.
    """
    text = GIT_TAG_RE.sub("", text)
    text = replace_paths(text, path_replacements)
    return text


def read_bytes(path: Path) -> bytes:
    return path.read_bytes()


def looks_like_text(data: bytes) -> bool:
    if b"\0" in data:
        return False
    try:
        data.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def unified_diff(expected: str, actual: str, exp_name: str, act_name: str, limit: int = 80) -> str:
    import difflib

    lines = list(
        difflib.unified_diff(
            expected.splitlines(keepends=True),
            actual.splitlines(keepends=True),
            fromfile=exp_name,
            tofile=act_name,
        )
    )
    if len(lines) > limit:
        omitted = len(lines) - limit
        lines = lines[:limit] + [f"... ({omitted} more diff lines omitted)\n"]
    return "".join(lines)


def compare_file(
    expected_path: Path,
    actual_path: Path,
    path_replacements: Sequence[Tuple[str, str]],
    osm: bool,
) -> Optional[str]:
    if not actual_path.is_file():
        return f"missing actual file: {actual_path}"
    if not expected_path.is_file():
        return f"missing expected file: {expected_path}"
    exp_b = read_bytes(expected_path)
    act_b = read_bytes(actual_path)
    if not looks_like_text(exp_b) or not looks_like_text(act_b):
        if exp_b == act_b:
            return None
        return f"binary mismatch ({len(exp_b)} vs {len(act_b)} bytes)"
    exp = exp_b.decode("utf-8")
    act = act_b.decode("utf-8")
    if osm or expected_path.suffix == ".osm" or actual_path.suffix == ".osm":
        exp_n = normalize_osm(exp, path_replacements)
        act_n = normalize_osm(act, path_replacements)
    else:
        exp_n = normalize_text(exp, path_replacements)
        act_n = normalize_text(act, path_replacements)
    if exp_n == act_n:
        return None
    return unified_diff(exp_n, act_n, str(expected_path), str(actual_path))


def run_backend(
    python: str,
    backends_dir: Path,
    backend: str,
    argv: Sequence[str],
    cwd: Path,
    env: dict,
) -> subprocess.CompletedProcess:
    script = backends_dir / f"{backend}.py"
    if not script.is_file():
        raise FileNotFoundError(f"backend script not found: {script}")
    cmd = [python, str(script), *argv]
    return subprocess.run(
        cmd,
        cwd=str(cwd),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def copy_inputs(corpus: Path, work: Path) -> None:
    shutil.copytree(corpus / "inputs", work / "inputs")


def expected_store_name(work_rel: str) -> str:
    return Path(work_rel).name


def collect_produced(work: Path, meta: dict) -> Dict[str, Path]:
    """Map expected-basename -> actual path in the work dir."""
    produced: Dict[str, Path] = {}
    for rel in meta.get("output_files") or []:
        produced[expected_store_name(rel)] = work / rel
    for rel_dir in meta.get("output_dirs") or []:
        d = work / rel_dir
        if d.is_dir():
            for f in sorted(d.iterdir()):
                if f.is_file():
                    produced[f.name] = f
    if meta.get("inplace"):
        for rel in meta.get("argv") or []:
            if rel.startswith("-"):
                continue
            src = work / rel
            if src.is_file() and src.suffix == ".osm":
                produced[src.name] = src
    return produced


def run_case(
    case_dir: Path,
    python: str,
    backends_dir: Path,
    update: bool,
) -> Tuple[bool, List[str]]:
    meta = load_case(case_dir)
    backend = meta["backend"]
    argv = list(meta.get("argv") or [])
    if not argv:
        return False, [f"{case_dir}: no argv (cmd.txt / case.json)"]

    notes: List[str] = []
    with tempfile.TemporaryDirectory(prefix="ll2-golden-") as tmp:
        work = Path(tmp).resolve()
        copy_inputs(CORPUS_ROOT, work)

        env = os.environ.copy()
        env["PYTHONUNBUFFERED"] = "1"
        # Do not inherit a JOSM-oriented output dir; routing --once writes to argv.
        env.pop("LL2_OUTPUT_DIR", None)
        env.pop("LL2_VALIDATE_OUTPUT", None)

        python_real = str(Path(python).resolve()) if Path(python).exists() else python
        path_replacements = [
            (str(work), "<WORK>"),
            (str(CORPUS_ROOT), "<CORPUS>"),
            (str(backends_dir.resolve()), "<BACKENDS>"),
            (python_real, "<PYTHON>"),
        ]

        for step in meta.get("setup") or []:
            step_backend = step["backend"]
            step_argv = list(step["argv"])
            proc = run_backend(python, backends_dir, step_backend, step_argv, work, env)
            if proc.returncode != 0:
                err = proc.stderr.decode("utf-8", "replace")
                return False, [
                    f"{case_dir.name}: setup {step_backend} failed (rc={proc.returncode})",
                    normalize_text(err, path_replacements),
                ]

        proc = run_backend(python, backends_dir, backend, argv, work, env)
        stdout = proc.stdout.decode("utf-8", "replace")
        stderr = proc.stderr.decode("utf-8", "replace")
        expect_ok = bool(meta.get("expect_success", True))
        if expect_ok and proc.returncode != 0:
            return False, [
                f"{case_dir.name}: backend exited {proc.returncode}",
                "--- stdout ---",
                normalize_text(stdout, path_replacements),
                "--- stderr ---",
                normalize_text(stderr, path_replacements),
            ]
        if not expect_ok and proc.returncode == 0:
            return False, [f"{case_dir.name}: expected non-zero exit, got 0"]

        stdout_n = normalize_text(stdout, path_replacements)
        stderr_n = normalize_text(stderr, path_replacements)

        mismatches: List[str] = []
        expected_dir = case_dir / "expected"

        if update:
            (case_dir / "stdout.txt").write_text(stdout_n, encoding="utf-8")
            (case_dir / "stderr.txt").write_text(stderr_n, encoding="utf-8")
            produced = collect_produced(work, meta)
            if produced:
                expected_dir.mkdir(parents=True, exist_ok=True)
            if expected_dir.is_dir():
                for old in expected_dir.iterdir():
                    if old.is_file() and old.name not in produced:
                        old.unlink()
            for name, src in produced.items():
                if not src.is_file():
                    mismatches.append(f"update: produced file missing: {src}")
                    continue
                data = src.read_bytes()
                if looks_like_text(data) and src.suffix == ".osm":
                    text = normalize_osm(data.decode("utf-8"), path_replacements)
                    (expected_dir / name).write_text(text, encoding="utf-8")
                else:
                    shutil.copy2(src, expected_dir / name)
            if expected_dir.is_dir() and not any(expected_dir.iterdir()):
                expected_dir.rmdir()
            if mismatches:
                return False, mismatches
            notes.append("updated expected outputs")
            return True, notes

        # Compare stdout/stderr when the stored file exists (including empty).
        for label, actual, stored in (
            ("stdout.txt", stdout_n, case_dir / "stdout.txt"),
            ("stderr.txt", stderr_n, case_dir / "stderr.txt"),
        ):
            if not stored.is_file():
                continue
            exp = stored.read_text(encoding="utf-8")
            if exp != actual:
                mismatches.append(
                    f"{label} mismatch:\n"
                    + unified_diff(exp, actual, str(stored), f"<actual {label}>")
                )

        produced = collect_produced(work, meta)
        expected_files = []
        if expected_dir.is_dir():
            expected_files = [p for p in sorted(expected_dir.iterdir()) if p.is_file()]
        if expected_files and not produced:
            mismatches.append(
                "case has expected/ files but produced no outputs "
                "(set output_files / output_dirs / inplace in case.json)"
            )
        for exp_path in expected_files:
            actual_path = produced.get(exp_path.name)
            if actual_path is None:
                mismatches.append(f"no produced file for expected {exp_path.name}")
                continue
            err = compare_file(exp_path, actual_path, path_replacements, osm=True)
            if err:
                mismatches.append(f"{exp_path.name}: {err}")

        for produced_rel, original_rel in (meta.get("also_compare") or {}).items():
            actual_path = work / produced_rel
            original_path = work / original_rel
            if not original_path.is_file():
                original_path = CORPUS_ROOT / original_rel
            err = compare_file(original_path, actual_path, path_replacements, osm=True)
            if err:
                mismatches.append(
                    f"round-trip {produced_rel} vs {original_rel}: {err}"
                )
            else:
                notes.append(f"round-trip lossless: {produced_rel} == {original_rel}")

        if mismatches:
            return False, mismatches
        return True, notes


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument(
        "python_positional",
        nargs="?",
        default=None,
        help="Python interpreter with lanelet2 (optional positional)",
    )
    p.add_argument(
        "--python",
        default=None,
        help="Python interpreter (overrides positional / env)",
    )
    p.add_argument(
        "--backends-dir",
        default=None,
        help="Directory containing the backend .py scripts",
    )
    p.add_argument(
        "--update",
        action="store_true",
        help="Rewrite stored expected outputs / stdout / stderr from this run",
    )
    p.add_argument(
        "--case",
        action="append",
        default=[],
        help="Substring filter on cases/<backend>/<name> (repeatable)",
    )
    return p


def resolve_python(args: argparse.Namespace) -> str:
    if args.python:
        return args.python
    if args.python_positional:
        return args.python_positional
    env = os.environ.get("LL2_BACKENDS_PYTHON") or os.environ.get("GOLDEN_PYTHON")
    if env:
        return env
    return DEFAULT_PYTHON


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    python = resolve_python(args)
    backends_dir = (
        Path(args.backends_dir).expanduser().resolve()
        if args.backends_dir
        else default_backends_dir()
    )
    if not Path(python).exists() and os.path.sep in python:
        print(f"error: python interpreter not found: {python}", file=sys.stderr)
        return 2
    if not backends_dir.is_dir():
        print(f"error: backends dir not found: {backends_dir}", file=sys.stderr)
        return 2

    cases = discover_cases(CORPUS_ROOT)
    if args.case:
        cases = [
            c
            for c in cases
            if any(filt in str(c.relative_to(CORPUS_ROOT)) for filt in args.case)
        ]
    if not cases:
        print("error: no cases found", file=sys.stderr)
        return 2

    print(f"python:    {python}")
    print(f"backends:  {backends_dir}")
    print(f"corpus:    {CORPUS_ROOT}")
    print(f"cases:     {len(cases)}")
    if args.update:
        print("mode:      UPDATE (rewriting expected outputs)")
    print()

    failed = 0
    for case_dir in cases:
        rel = case_dir.relative_to(CORPUS_ROOT)
        ok, notes = run_case(case_dir, python, backends_dir, args.update)
        status = "PASS" if ok else "FAIL"
        extra = f"  ({'; '.join(notes)})" if notes and ok else ""
        print(f"{status}  {rel}{extra}")
        if not ok:
            failed += 1
            for line in notes:
                for sub in line.splitlines() or [line]:
                    print(f"      {sub}")
            print()

    print()
    passed = len(cases) - failed
    print(f"{passed}/{len(cases)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

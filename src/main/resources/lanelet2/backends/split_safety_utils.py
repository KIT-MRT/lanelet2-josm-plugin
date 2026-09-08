#!/usr/bin/env python3
"""Safety checks for in-place split of merged maps."""

from __future__ import annotations

import os
import shutil
import subprocess
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Tuple


def find_git_repo(file_path: str) -> Optional[str]:
    path = os.path.abspath(file_path)
    if os.path.isfile(path):
        path = os.path.dirname(path)
    while path and path != os.path.dirname(path):
        if os.path.isdir(os.path.join(path, ".git")):
            return path
        path = os.path.dirname(path)
    return None


def _run_git(repo_dir: str, args: List[str]) -> Tuple[bool, str]:
    try:
        proc = subprocess.run(
            ["git"] + args,
            capture_output=True,
            text=True,
            cwd=repo_dir,
            timeout=30,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode == 0, out.strip()
    except Exception as exc:
        return False, str(exc)


def paths_dirty_in_git(repo_dir: str, target_paths: List[str]) -> Tuple[bool, List[str]]:
    """
    Return (is_dirty, dirty_relative_paths) for the given absolute target paths
    under repo_dir. Uses git status --porcelain for each file.
    """
    dirty: List[str] = []
    for abspath in target_paths:
        try:
            rel = os.path.relpath(os.path.abspath(abspath), repo_dir)
        except ValueError:
            continue
        if rel.startswith(".."):
            continue
        ok, out = _run_git(repo_dir, ["status", "--porcelain", "--", rel])
        if ok and out.strip():
            dirty.append(rel)
    return (len(dirty) > 0, dirty)


def backup_files(target_paths: List[str], backup_root: str) -> str:
    """Copy existing files to backup_root/<timestamp>/. Returns backup directory."""
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = Path(backup_root) / stamp
    backup_dir.mkdir(parents=True, exist_ok=True)
    for abspath in target_paths:
        src = Path(abspath)
        if not src.is_file():
            continue
        dst = backup_dir / src.name
        shutil.copy2(str(src), str(dst))
    return str(backup_dir)


def resolve_backup_root() -> Path:
    out_root = os.environ.get("LL2_OUTPUT_DIR", "").strip()
    if out_root:
        return Path(os.path.expanduser(out_root)) / "backups"
    root = os.environ.get("LL2_TOOLING_ROOT", "").strip()
    if root:
        return Path(os.path.expanduser(root)) / "backups"
    return Path.home() / "ll2_output" / "backups"

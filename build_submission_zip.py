#!/usr/bin/env python3
"""
Build the submission zip without WSL.

The reviewer's tooling reads the archive on Linux, so the entries have to carry
Unix metadata rather than the Windows-flavoured metadata Compress-Archive emits.
This reproduces what `zip -r` produced under WSL on a /mnt/d mount, matching the
2.5.0 archive that was accepted:

  * forward-slash paths under a single <tree>/ root
  * create_system = 3 (Unix) and create_version = 30, as Info-ZIP writes
  * 0777 permissions, which is what a DrvFs mount reports for every file
  * explicit directory entries, stored; file entries deflated
  * mtimes carried over from disk

Usage:
    python build_submission_zip.py ATAK5.6
    python build_submission_zip.py ATAK5.7
    python build_submission_zip.py ATAK5.6 --no-gradle-cache

Exclusions match the accepted 2.5.0 archive. Note that app/libs/main.jar (the
~32 MB SDK jar) is deliberately INCLUDED: it was in the accepted archive, which
is why these come out around 30 MB.
"""

import argparse
import os
import re
import sys
import time
import zipfile
from pathlib import Path

# Directories dropped wherever they appear in the tree.
EXCLUDED_DIRS = {".git", ".idea", "build", "dist", ".cxx"}

# The Gradle cache was present in the accepted archive, so it is included by
# default to stay faithful to that. It is pure build scratch and can be dropped
# with --no-gradle-cache if you would rather send a clean tree.
GRADLE_CACHE_DIR = ".gradle"

EXCLUDED_FILES = {"local.properties", ".DS_Store", "Thumbs.db"}
EXCLUDED_SUFFIXES = {".iml", ".apk", ".zip", ".aab", ".log"}

# Info-ZIP's "made by" version, and the permissions a DrvFs mount reports.
CREATE_VERSION = 30
UNIX_SYSTEM = 3
DIR_ATTR = (0o40777 << 16) | 0x10   # 0x41ff0010, trailing bit = MS-DOS dir flag
FILE_ATTR = 0o100777 << 16          # 0x81ff0000


def plugin_version(tree: Path) -> str:
    """Read ext.PLUGIN_VERSION out of the tree's app/build.gradle."""
    text = (tree / "app" / "build.gradle").read_text(encoding="utf-8", errors="replace")
    match = re.search(r'ext\.PLUGIN_VERSION\s*=\s*"([^"]+)"', text)
    if not match:
        sys.exit(f"could not find ext.PLUGIN_VERSION in {tree}/app/build.gradle")
    return match.group(1)


def skip(rel: Path, keep_gradle_cache: bool) -> bool:
    parts = set(rel.parts)
    if parts & EXCLUDED_DIRS:
        return True
    if not keep_gradle_cache and GRADLE_CACHE_DIR in parts:
        return True
    if rel.name in EXCLUDED_FILES:
        return True
    return rel.suffix.lower() in EXCLUDED_SUFFIXES


def add(zf: zipfile.ZipFile, path: Path, arcname: str, is_dir: bool) -> None:
    mtime = path.stat().st_mtime
    info = zipfile.ZipInfo(arcname + "/" if is_dir else arcname,
                           date_time=time.localtime(mtime)[:6])
    info.create_system = UNIX_SYSTEM
    info.create_version = CREATE_VERSION
    if is_dir:
        info.external_attr = DIR_ATTR
        info.compress_type = zipfile.ZIP_STORED
        zf.writestr(info, b"")
    else:
        info.external_attr = FILE_ATTR
        info.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(info, path.read_bytes(), compresslevel=9)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("tree", help="ATAK5.6 or ATAK5.7")
    ap.add_argument("--no-gradle-cache", action="store_true",
                    help="omit .gradle/ build scratch (the accepted 2.5.0 zip included it)")
    ap.add_argument("-o", "--output", help="override the output filename")
    args = ap.parse_args()

    root = Path(__file__).resolve().parent
    tree = root / args.tree
    if not tree.is_dir():
        sys.exit(f"no such tree: {tree}")

    version = plugin_version(tree)
    sdk = args.tree.replace("ATAK", "")          # ATAK5.6 -> 5.6
    out = root / (args.output or
                  f"ATAK-Plugin-ICU_VideoStreamer-{version}-ATAK-{sdk}-civ-release.zip")

    keep_cache = not args.no_gradle_cache
    files = dirs = 0

    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        add(zf, tree, args.tree, is_dir=True)
        dirs += 1
        # Walk sorted so the entry order is stable between runs.
        for dirpath, dirnames, filenames in os.walk(tree):
            here = Path(dirpath)
            dirnames[:] = sorted(
                d for d in dirnames
                if not skip(here.relative_to(tree) / d, keep_cache))
            for d in dirnames:
                rel = (here / d).relative_to(root)
                add(zf, here / d, rel.as_posix(), is_dir=True)
                dirs += 1
            for f in sorted(filenames):
                if skip(here.relative_to(tree) / f, keep_cache):
                    continue
                p = here / f
                if not p.is_file():          # skip dangling links
                    continue
                add(zf, p, p.relative_to(root).as_posix(), is_dir=False)
                files += 1

    print(f"{out.name}")
    print(f"  {files} files, {dirs} dirs, {out.stat().st_size / 1_048_576:.1f} MB")


if __name__ == "__main__":
    main()

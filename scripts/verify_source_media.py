#!/usr/bin/env python3
"""Verify exact user-authorized source media against the checked-in manifest.

No media is uploaded here: run after attaching the original bytes to repo.
In CI, --allow-missing preserves APK builds while media are still pending.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

def verify(repo_root, allow_missing=False):
    manifest=json.loads((repo_root/"reference/source/media_manifest.json").read_text(encoding="utf-8"))
    target=manifest["intended_repository_paths"]
    keys={"2026-10-18 15.40.42.mp4":target["real_walkthrough"],
          "ChatGPT Image Oct 18, 2026, 04_08_56 PM.png":target["design_reference"]}
    failures=0
    missing=0
    for media in manifest["originals"]:
        name=media["file_name"]
        path=repo_root/keys[name]
        if not path.is_file():
            missing+=1
            print(f"MISSING {path.relative_to(repo_root)}")
            continue
        sha=hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda:f.read(1<<20),b""):
                sha.update(chunk)
        good=(path.stat().st_size==media["bytes"] and sha.hexdigest()==media["sha256"])
        print(("VERIFIED" if good else "MISMATCH"),path.relative_to(repo_root))
        if not good:failures+=1
    if failures:return 2
    if missing:return 0 if allow_missing else 1
    return 0

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--root",type=Path,default=Path(__file__).resolve().parents[1])
    p.add_argument("--allow-missing",action="store_true")
    args=p.parse_args()
    sys.exit(verify(args.root,args.allow_missing))

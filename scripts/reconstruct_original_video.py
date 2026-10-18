#!/usr/bin/env python3
"""Reassemble the byte-identical original home video from public Git text shards.

The source is the user-provided video, explicitly authorized for public posting.
The fragments are ordinary base64 ASCII and preserve exact original bytes.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path

ORIGINAL_SHA256 = "b2c45336564b79e9db51fc835b290bbd3517b9bf0274e63caf3240047507f2c3"
ORIGINAL_BYTES = 6_682_968
PHOTO_SHA256 = "0a4b0390a077ec57c60467f58160e7e3a12c052230feb3593e141883f3c0fe54"
PHOTO_BYTES = 3_058_988

def reconstruct(root: Path, output: Path) -> dict:
    source = root / "reference/source"
    pieces = sorted((source / "video_b64").glob("part_*.b64"))
    if len(pieces) != 12:
        raise ValueError(f"Expected 12 video shards, got {len(pieces)}")
    digest = hashlib.sha256()
    written = 0
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".tmp")
    try:
        with temporary.open("wb") as out:
            for part in pieces:
                encoded = "".join(part.read_text(encoding="ascii").split())
                chunk = base64.b64decode(encoded, validate=True)
                out.write(chunk)
                written += len(chunk)
                digest.update(chunk)
        if written != ORIGINAL_BYTES or digest.hexdigest() != ORIGINAL_SHA256:
            raise ValueError(f"Original MP4 hash mismatch: size={written}; sha256={digest.hexdigest()}")
        temporary.replace(output)
    finally:
        if temporary.exists():
            temporary.unlink()
    photo = source / "scandinavian_kitchen_reference.png"
    if not photo.is_file():
        raise FileNotFoundError(photo)
    if photo.stat().st_size != PHOTO_BYTES:
        raise ValueError("Original PNG size mismatch")
    photo_digest = hashlib.sha256(photo.read_bytes()).hexdigest()
    if photo_digest != PHOTO_SHA256:
        raise ValueError("Original PNG SHA-256 mismatch")
    return {"status": "verified", "video_sha256": digest.hexdigest(),
            "video_bytes": written, "photo_sha256": photo_digest, "photo_bytes": PHOTO_BYTES}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output or (root / "reference/source/room_walkthrough_original.mp4")
    result = reconstruct(root, output)
    print(json.dumps(result, indent=2, sort_keys=True))

if __name__ == "__main__":
    main()

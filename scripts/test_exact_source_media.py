"""Check exact original media, original-size constraints and reconstruction."""
import hashlib
import tempfile
import unittest
from pathlib import Path
from reconstruct_original_video import reconstruct, ORIGINAL_BYTES, ORIGINAL_SHA256, PHOTO_BYTES, PHOTO_SHA256

ROOT=Path(__file__).resolve().parents[1]

class ExactSourceMediaTest(unittest.TestCase):
    def test_originals_are_committed_and_hashes_match(self):
        source=ROOT/"reference/source"
        video=source/"room_walkthrough_original.mp4"
        photo=source/"scandinavian_kitchen_reference.png"
        self.assertEqual(video.stat().st_size, ORIGINAL_BYTES)
        self.assertEqual(photo.stat().st_size, PHOTO_BYTES)
        self.assertEqual(hashlib.sha256(video.read_bytes()).hexdigest(), ORIGINAL_SHA256)
        self.assertEqual(hashlib.sha256(photo.read_bytes()).hexdigest(), PHOTO_SHA256)

    def test_base64_shards_reassemble_original_bit_for_bit(self):
        with tempfile.TemporaryDirectory() as d:
            output=Path(d)/"walkthrough.mp4"
            result=reconstruct(ROOT,output)
            self.assertEqual(result["status"],"verified")
            self.assertEqual(result["video_sha256"],ORIGINAL_SHA256)
            self.assertEqual(output.read_bytes(),
                             (ROOT/"reference/source/room_walkthrough_original.mp4").read_bytes())

if __name__ == "__main__":
    unittest.main()

"""Contract tests for the Blender-importable photo-reference kitchen."""
import json
from pathlib import Path
import tempfile
import unittest

import trimesh

from build_reference_kitchen import build, DIMS


class ReferenceKitchenGenerationTest(unittest.TestCase):
    def test_reference_kitchen_is_physically_sized_with_individual_objects(self):
        with tempfile.TemporaryDirectory() as tmp:
            glb = Path(tmp) / "reference.glb"
            manifest = Path(tmp) / "manifest.json"
            build(glb, manifest)
            self.assertTrue(glb.exists())
            self.assertLess(glb.stat().st_size, 20_000_000)
            details = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertTrue(details["sizes_are_assumptions"])
            self.assertFalse(details["room_measured"])
            self.assertEqual(details["scene_units"], "meters")
            self.assertEqual(details["pieces"], 236)
            self.assertAlmostEqual(details["bounding_box_m"][0], 3.89, places=2)
            self.assertAlmostEqual(details["bounding_box_m"][1], 2.62, places=2)
            self.assertAlmostEqual(details["bounding_box_m"][2], 3.227, places=2)
            self.assertAlmostEqual(DIMS["island_width"], 2.55, places=2)
            loaded = trimesh.load(glb, force="scene")
            self.assertEqual(len(loaded.graph.nodes_geometry), 236)
            for expected in ["island_quartz_top", "back_white_quartz_counter",
                             "pendant_0_cream_bell", "pendant_1_cream_bell",
                             "stool_0_linen_seat", "stool_1_linen_seat",
                             "stool_2_linen_seat", "pantry_upper_cream_door",
                             "sink_recess"]:
                self.assertIn(expected, loaded.graph.nodes)
            with glb.open("rb") as f:
                f.seek(12)
                header = f.read(8)
                import struct
                chunk_len, chunk_type = struct.unpack("<I4s", header)
                self.assertEqual(chunk_type, b"JSON")
                doc = json.loads(f.read(chunk_len))
            self.assertFalse(any(b.get("uri") for b in doc["buffers"]))
            self.assertFalse(any(i.get("uri") for i in doc.get("images", [])))
            self.assertGreaterEqual(len(doc["materials"]), 12)


if __name__ == "__main__":
    unittest.main()

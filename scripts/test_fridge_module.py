"""Separate refrigerator must be metric, standalone and not on panoramic-glass wall."""
import tempfile
from pathlib import Path
import json
import unittest
import trimesh
from build_fridge_module import build

class FridgeModuleTest(unittest.TestCase):
    def test_standalone_fridge_bounds_and_layout(self):
        with tempfile.TemporaryDirectory() as td:
            glb=Path(td)/"fridge.glb"
            manifest=Path(td)/"fridge.json"
            build(glb,manifest)
            data=json.loads(manifest.read_text(encoding="utf-8"))
            self.assertTrue(data["dimensions_assumed_not_measured"])
            self.assertIn("opaque wall",data["required_wall"])
            self.assertEqual(len(data["nodes"]),11)
            scene=trimesh.load(glb,force="scene")
            self.assertEqual(len(scene.graph.nodes_geometry),11)
            bounds=(scene.bounds[1]-scene.bounds[0])
            self.assertAlmostEqual(bounds[0],1.148,places=3)
            self.assertAlmostEqual(bounds[1],2.37,places=3)
            self.assertAlmostEqual(bounds[2],.7525,places=3)
            self.assertLess(glb.stat().st_size,1_000_000)

if __name__=="__main__":
    unittest.main()

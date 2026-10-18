import unittest
import tempfile
from pathlib import Path
import numpy as np
import trimesh
from split_reference_kitchen import export, DIMS, is_island

class SpatialGroupTests(unittest.TestCase):
    def test_separate_ar_assemblies_and_real_dimensions(self):
        with tempfile.TemporaryDirectory() as d:
            details = export(Path(d))
            wall = trimesh.load(Path(d, "reference_wall.glb"), force="scene")
            island = trimesh.load(Path(d,"reference_island.glb"),force="scene")
            wall_names=set(wall.graph.nodes_geometry)
            island_names=set(island.graph.nodes_geometry)
            self.assertTrue({"back_white_quartz_counter","dishwasher_stainless_face"}<=wall_names)
            self.assertTrue({"island_quartz_top","stool_0_linen_seat","pendant_1_cream_bell"}<=island_names)
            self.assertFalse(wall_names & island_names)
            self.assertTrue(all(not is_island(x) for x in wall_names))
            self.assertTrue(all(is_island(x) for x in island_names))
            self.assertEqual(len(wall_names)+len(island_names),236)
            # Island no longer retains its original offset relative to the sink wall.
            ib = island.bounds
            self.assertLess(abs(ib[0][0]),.26)
            self.assertLess(abs(ib[0][2]),.2)
            self.assertGreater(ib[1][1],2.5)  # pendant wires to the ceiling
            self.assertAlmostEqual(details["island"]["size"][0],DIMS["island_width"]+.11,places=2)
            self.assertGreater(details["island"]["size"][2],DIMS["island_depth"])
            self.assertLess(Path(d,"reference_wall.glb").stat().st_size,15_000_000)
            self.assertLess(Path(d,"reference_island.glb").stat().st_size,15_000_000)
            self.assertGreater(ib[0][1],-.05)

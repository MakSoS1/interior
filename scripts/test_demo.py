import tempfile
import struct
import json
import unittest
from pathlib import Path
from generate_demo import make_glb

class KitchenTest(unittest.TestCase):
    def test_binary_gltf_dimensions_and_materials(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"demo.glb"
            self.assertGreater(make_glb(path), 20)
            raw=path.read_bytes()
            magic,version,length=struct.unpack_from("<III",raw,0)
            self.assertEqual((magic,version,length),(0x46546c67,2,len(raw)))
            jlen,kind=struct.unpack_from("<I4s",raw,12)
            self.assertEqual(kind,b"JSON")
            doc=json.loads(raw[20:20+jlen])
            self.assertEqual(doc["asset"]["version"],"2.0")
            self.assertGreaterEqual(len(doc["materials"]),6)
            fridge=next(x for x in doc["nodes"] if x["name"]=="fridge")
            self.assertAlmostEqual(fridge["scale"][1],2.05)
            self.assertAlmostEqual(fridge["translation"][0]+fridge["scale"][0]/2,2.6)
            self.assertTrue(any(x["name"]=="continuous_stone_countertop" for x in doc["nodes"]))
if __name__=="__main__": unittest.main()

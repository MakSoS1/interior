import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from interior_adapter import pack, validate, inspect_glb
from make_demo_pack import generate

class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.example=generate(self.root)
    def tearDown(self):self.tmp.cleanup()
    def test_example_has_separate_cabinet_and_rigid_chair_pair(self):
        doc=validate(self.example)
        self.assertEqual(len(doc['groups']),2)
        self.assertEqual([g['metresPerUnit'] for g in doc['groups']],[1.,1.])
        chairs=inspect_glb(self.root/'Two_chairs.glb')
        self.assertGreater(len(chairs['nodes']),10)
        with zipfile.ZipFile(self.example) as z:
            self.assertEqual(len(z.namelist()),3)
    def test_same_model_can_have_two_independent_instances_without_copying_bytes(self):
        p=self.root/'reuse.zip';m=self.root/'Cabinet_60cm.glb'
        pack([m,m],p,'Two cabinets')
        doc=validate(p)
        self.assertNotEqual(doc['groups'][0]['id'],doc['groups'][1]['id'])
        self.assertEqual(doc['groups'][0]['asset'],doc['groups'][1]['asset'])
        with zipfile.ZipFile(p) as z:self.assertEqual(len(z.namelist()),2)
    def test_millimetre_contract_and_authored_origin(self):
        p=self.root/'mm.zip'
        pack([self.root/'Cabinet_60cm.glb'],p,'mm',units='mm',authored_pivot=True)
        g=validate(p)['groups'][0]
        self.assertEqual(g['metresPerUnit'],.001)
        self.assertEqual(g['pivot'],[0,0,0])
    def test_missing_mesh_and_wrong_file_are_rejected(self):
        wrong=self.root/'wrong.glb';wrong.write_bytes(b'not a glb')
        with self.assertRaises(ValueError):pack([wrong],self.root/'bad.zip')
        self.assertFalse((self.root/'bad.zip').exists())
    def test_modified_model_checksum_rejected(self):
        target=self.root/'tampered.zip'
        with zipfile.ZipFile(self.example) as z, zipfile.ZipFile(target,'w') as out:
            for name in z.namelist():
                data=z.read(name)
                if name.endswith('.glb'):data=data[:-1]+bytes([data[-1]^1])
                out.writestr(name,data)
        with self.assertRaises(ValueError):validate(target)
    def test_zip_traversal_rejected(self):
        target=self.root/'unsafe.zip'
        with zipfile.ZipFile(target,'w') as z:z.writestr('../escape.glb',b'escape')
        with self.assertRaises(ValueError):validate(target)
    def test_output_cannot_overwrite_source(self):
        m=self.root/'Cabinet_60cm.glb';before=hashlib.sha256(m.read_bytes()).hexdigest()
        with self.assertRaises(ValueError):pack([m],m)
        self.assertEqual(before,hashlib.sha256(m.read_bytes()).hexdigest())
    def test_group_limit_is_explicit(self):
        with self.assertRaises(ValueError):pack([self.root/'Cabinet_60cm.glb']*33,self.root/'many.zip')

if __name__=='__main__':unittest.main()

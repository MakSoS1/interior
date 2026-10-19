import sys,unittest,tempfile,json,zipfile,hashlib,struct
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from pack_glb import package_glbs

def glb(path):
    data=json.dumps({'asset':{'version':'2.0'},'meshes':[{'name':'static-test'}]}).encode()
    data+=b' '*((-len(data))%4)
    path.write_bytes(struct.pack('<III',0x46546c67,2,20+len(data))+struct.pack('<I4s',len(data),b'JSON')+data)
    return path

class PackageTest(unittest.TestCase):
    def test_no_empty_selection(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):package_glbs(Path(d)/'p.zip',[])
    def test_single_group_remains_one_glb_and_package_embeds_checksum(self):
        with tempfile.TemporaryDirectory() as d:
            src=glb(Path(d)/'group.glb');out=package_glbs(Path(d)/'set.zip',[src])
            with zipfile.ZipFile(out) as z:
                m=json.loads(z.read('manifest.json'));self.assertEqual(m['units'],'m');self.assertEqual(len(m['assets']),1)
                a=m['assets'][0];self.assertEqual(z.read(a['file']),src.read_bytes())
                self.assertEqual(a['sha256'],hashlib.sha256(src.read_bytes()).hexdigest())
    def test_two_groups_have_distinct_ids_and_no_external_resources(self):
        with tempfile.TemporaryDirectory() as d:
            out=package_glbs(Path(d)/'set.zip',[glb(Path(d)/'a.glb'),glb(Path(d)/'b.glb')])
            with zipfile.ZipFile(out) as z:
                m=json.loads(z.read('manifest.json'));self.assertEqual(len(m['assets']),2)
                self.assertEqual(len({a['id'] for a in m['assets']}),2)
                self.assertTrue(all(a['file'].startswith('models/') for a in m['assets']))
    def test_non_glb_rejected_without_partial_package(self):
        with tempfile.TemporaryDirectory() as d:
            src=Path(d)/'bad.glb';src.write_text('Not GLB');out=Path(d)/'set.zip'
            with self.assertRaises(ValueError):package_glbs(out,[src])
            self.assertFalse(out.exists())
if __name__=='__main__':unittest.main()

"""Run with Blender in CI; creates throwaway test geometry, not a user scene."""
import importlib.util
from pathlib import Path
import sys
import json
import zipfile
import bpy

root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('interior_export',root/'tools/blender_interior_export.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.context.scene.unit_settings.system='METRIC'
bpy.context.scene.unit_settings.scale_length=1.0
for n,label in enumerate(['Cabinet collection','Chair pair']):
    collection=bpy.data.collections.new(label);bpy.context.scene.collection.children.link(collection)
    for j in range(n+1):
        bpy.ops.mesh.primitive_cube_add(size=1,location=(n*2+j*.7,.2,.4))
        obj=bpy.context.object;obj.name=label+' '+str(j);obj.dimensions=(.6,.5,.8)
        for c in list(obj.users_collection):c.objects.unlink(obj)
        collection.objects.link(obj)
previous={o.name for o in bpy.context.selected_objects}
output=root/'dist/blender_smoke.interior.zip'
result=module.export_collections(output)
assert len(result['groups'])==2
assert previous=={o.name for o in bpy.context.selected_objects}
with zipfile.ZipFile(output) as z:
    manifest=json.loads(z.read('interior.json'))
    assert len(manifest['groups'])==2
    assert all(z.getinfo(g['asset']).file_size>100 for g in manifest['groups'])
print('BLENDER_ADAPTER_SMOKE_OK',bpy.app.version_string,output)

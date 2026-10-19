"""Actual Blender export/import regression, not a mocked bpy test."""
import bpy,sys,json,zipfile,hashlib
from pathlib import Path
from mathutils import Vector
root=Path(__file__).resolve().parents[2];sys.path.insert(0,str(root/'adapter'))
from interior_ar_export import export_package
out=Path(sys.argv[sys.argv.index('--')+1]);out.mkdir(parents=True,exist_ok=True)
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
scene=bpy.context.scene;scene.unit_settings.system='METRIC';scene.unit_settings.scale_length=.01
original=[]
for index,(name,dimensions) in enumerate([('Stool',(.4,.5,.7)),('Cabinet',(.6,.6,.9))]):
    coll=bpy.data.collections.new(name);scene.collection.children.link(coll)
    bpy.ops.mesh.primitive_cube_add(size=1,location=(index*100,0,dimensions[2]*50))
    obj=bpy.context.object;obj.name=name;obj.dimensions=tuple(x*100 for x in dimensions)
    for old in list(obj.users_collection):old.objects.unlink(obj)
    coll.objects.link(obj);original.append(obj)
bpy.context.view_layer.update();bpy.ops.object.select_all(action='DESELECT')
for obj in original:obj.select_set(True)
bpy.context.view_layer.objects.active=original[0]
selected={o.name for o in bpy.context.selected_objects}
result=Path(export_package(bpy.context,out/'two-groups.interior.zip','COLLECTIONS'))
assert abs(scene.unit_settings.scale_length-.01)<1e-8
assert selected=={o.name for o in bpy.context.selected_objects}
assert all(o.name in bpy.data.objects for o in original)
with zipfile.ZipFile(result) as z:
    manifest=json.loads(z.read('manifest.json'));assert len(manifest['assets'])==2
    report=[]
    for item in manifest['assets']:
        raw=z.read(item['file']);assert hashlib.sha256(raw).hexdigest()==item['sha256']
        path=out/Path(item['file']).name;path.write_bytes(raw)
        bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
        scene.unit_settings.scale_length=1.0
        bpy.ops.import_scene.gltf(filepath=str(path))
        points=[obj.matrix_world@Vector(c) for obj in bpy.context.selected_objects if obj.type=='MESH' for c in obj.bound_box]
        bounds=[max(p[i] for p in points)-min(p[i] for p in points) for i in range(3)]
        assert max(bounds)>.59 and max(bounds)<1.01,(item['name'],bounds)
        report.append({'name':item['name'],'dimensions_m':bounds,'sha256':item['sha256']})
(out/'blender-roundtrip.json').write_text(json.dumps({'blender':bpy.app.version_string,'groups':report,'units_restored':True},indent=2))
print('BLENDER_ROUNDTRIP_PASSED',report)

#!/usr/bin/env python3
"""Sparse SfM of the original room video, not a hand-keyframed camera.

This estimates a similarity-scale reconstruction. Metric scale/floor/wall alignment
must be supplied separately. A low reprojection error is NOT proof of correct furniture placement.
"""
from pathlib import Path
import json
import cv2
import numpy as np
import pycolmap

root = Path('build/video_registration_v4')
images = root / 'images'
images.mkdir(parents=True, exist_ok=True)
cap = cv2.VideoCapture('reference/source/room_walkthrough_original.mp4')
assert cap.isOpened(), 'Original video missing'
fps = cap.get(cv2.CAP_PROP_FPS)
n = 0
frame_times = {}
while True:
    ok, frame = cap.read()
    if not ok:
        break
    if n % 6 == 0:
        name = f'f{n:04d}.jpg'
        cv2.imwrite(str(images / name), frame, [cv2.IMWRITE_JPEG_QUALITY, 97])
        frame_times[name] = {'index': n, 't': cap.get(cv2.CAP_PROP_POS_MSEC) / 1000}
    n += 1
cap.release()
(root/'frame_times.json').write_text(json.dumps(frame_times))
print('PYCOLMAP', pycolmap.__version__, 'images', len(frame_times), flush=True)
db = root / 'room.db'
ex = pycolmap.FeatureExtractionOptions()
ex.num_threads = 3
ex.max_image_size = 1400
ex.sift.max_num_features = 3000
reader = pycolmap.ImageReaderOptions()
reader.camera_model = 'SIMPLE_RADIAL'
reader.camera_params = '920,360,640,0'
pycolmap.extract_features(db, images, camera_mode=pycolmap.CameraMode.SINGLE,
    reader_options=reader, extraction_options=ex, device=pycolmap.Device.cpu)
matching = pycolmap.FeatureMatchingOptions()
matching.num_threads = 3
pycolmap.match_exhaustive(db, matching_options=matching, device=pycolmap.Device.cpu)
opts = pycolmap.IncrementalPipelineOptions()
opts.num_threads = 3
opts.min_model_size = 5
opts.init_num_trials = 200
opts.ba_refine_focal_length = True
opts.ba_refine_extra_params = True
opts.mapper.init_min_tri_angle = 3
opts.mapper.filter_max_reproj_error = 3
opts.mapper.abs_pose_min_num_inliers = 20
maps = pycolmap.incremental_mapping(db, images, root/'sparse', options=opts)
assert maps, 'No 3D reconstruction: do not fabricate a camera'
for idx, rec in maps.items():
    print('MODEL',idx,rec.summary(),flush=True)
    path = root/f'model_{idx}'
    path.mkdir(exist_ok=True)
    rec.write(path)
    rec.write_text(path)
    rec.export_PLY(path/'cloud.ply')
    data={'version':pycolmap.__version__, 'cameras':{}, 'images':{}, 'points':{},
          'scale':'unknown similarity scale; do not infer physical units'}
    for cid,c in rec.cameras.items():
        data['cameras'][str(cid)]={'model':c.model_name,'width':c.width,'height':c.height,'params':c.params.tolist()}
    for iid,i in rec.images.items():
        pose = i.cam_from_world() if callable(i.cam_from_world) else i.cam_from_world
        data['images'][i.name]={'id':iid,'camera_id':i.camera_id,
            'R':pose.rotation.matrix().tolist(),'t':pose.translation.tolist(),
            'observations':[[float(p.xy[0]),float(p.xy[1]),int(p.point3D_id)] for p in i.points2D if p.has_point3D()]}
    for pid,p in rec.points3D.items():
        data['points'][str(pid)]={'xyz':p.xyz.tolist(),'color':p.color.tolist(),'error':p.error,
            'track':[[int(x.image_id),int(x.point2D_idx)] for x in p.track.elements]}
    (path/'reconstruction.json').write_text(json.dumps(data,separators=(',',':')))
print('All maps exported',flush=True)

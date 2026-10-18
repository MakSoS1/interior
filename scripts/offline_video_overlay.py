#!/usr/bin/env python3
"""Offline *illustrative* alignment of 3D reference kitchen to supplied video.
This is NOT ARCore or an accurate camera/room scan. It uses 4 manually estimated floor
correspondences (see DESIGN_FLOOR_PIXELS) + SIFT RANSAC image homography to follow a
short portion of a panning handheld video. Requires calibration for each new video.
"""
import argparse, json, math
from pathlib import Path
import cv2
import numpy as np
import trimesh

# At t=1s, rough *floor* corners of planned island, manually selected in pixels
# Rear left, rear right, front right, front left; not measured landmarks.
DESIGN_FLOOR_PIXELS = np.float32([[180,800],[590,785],[675,1090],[65,1100]])
FLOOR_WORLD = np.float32([[.76,0,1.60],[3.31,0,1.60],[3.31,0,2.65],[.76,0,2.65]])
FX=930.0


def estimate_tracking(ref,frame,sift,bf,k0,d0):
    k,d=sift.detectAndCompute(cv2.cvtColor(frame,cv2.COLOR_BGR2GRAY),None)
    if d is None or len(k)<8:return None,0,0
    knn=bf.knnMatch(d0,d,k=2)
    good=[a for a,b in knn if a.distance < .73*b.distance]
    if len(good)<28:return None,len(good),0
    p0=np.float32([k0[m.queryIdx].pt for m in good]); p1=np.float32([k[m.trainIdx].pt for m in good])
    H,valid=cv2.findHomography(p0,p1,cv2.RANSAC,3.5)
    return H,len(good),int(valid.sum()) if valid is not None else 0


def camera_reference():
    """Manual illustrative camera: physical scale unknown without measured room landmarks."""
    K=np.array([[920.,0,360],[0,920.,640],[0,0,1]],dtype=np.float64)
    eye=np.array([2.05,1.66,6.7],dtype=np.float64)
    target=np.array([1.95,1.13,1.05],dtype=np.float64)
    forward=(target-eye)/np.linalg.norm(target-eye)
    right=np.cross(forward,[0.,1.,0.]);right/=np.linalg.norm(right)
    down=np.cross(forward,right);down/=np.linalg.norm(down)
    R=np.vstack((right,down,forward))
    tvec=-R@eye
    rvec,_=cv2.Rodrigues(R)
    pts,_=cv2.projectPoints(FLOOR_WORLD,rvec,tvec,K,None)
    print('Approximate projected island floor:',pts.reshape(-1,2).round().astype(int).tolist())
    return K,rvec,tvec,None


def project_scene(scene,K,rvec,tvec):
    R,_=cv2.Rodrigues(rvec)
    faces=[]
    for node in scene.graph.nodes_geometry:
        matrix,geometry_id=scene.graph[node]
        mesh=scene.geometry[geometry_id]
        # Select all structural parts but decimate small detail aggressively for 25fps software render.
        if node.startswith(('herb_leaf','herb_stem','induction_ring','burner','pendant_')):
            continue
        if len(mesh.faces)>500: continue
        xyz=trimesh.transform_points(mesh.vertices,matrix).astype(np.float64)
        points,_=cv2.projectPoints(xyz,rvec,tvec,K,None)
        points=points.reshape(-1,2)
        depths=(R@xyz.T + tvec.reshape(3,1)).T[:,2]
        material=getattr(mesh.visual,'material',None)
        if material is None: base=(199,174,145)
        elif getattr(material,'baseColorTexture',None) is not None:
            pixels=np.array(material.baseColorTexture.convert('RGB'))
            base=tuple(int(v) for v in pixels.mean(axis=(0,1)))
        else:
            rgba=getattr(material,'baseColorFactor',None)
            base=tuple(int(v) for v in rgba[:3]) if rgba is not None else (200,190,180)
        for face in mesh.faces:
            a,b,c=xyz[face]
            normal=np.cross(b-a,c-a);length=np.linalg.norm(normal)
            if length<1e-10:continue
            normal/=length
            light=np.array([-.52,.84,.38]); light/=np.linalg.norm(light)
            brightness=.52+.45*abs(float(normal@light))
            color=np.clip(np.asarray(base)*brightness,0,255).astype('uint8').tolist()
            d=float(depths[face].mean())
            if d<=.10:continue
            pp=points[face]
            if not np.isfinite(pp).all():continue
            if max(abs(pp.flatten()))>30000:continue
            faces.append((d,pp.astype(np.float32),color))
    faces.sort(key=lambda a:-a[0]);return faces


def render_reference(canvas,triangles,H,alpha=.83):
    h,w=canvas.shape[:2]
    polygon_layer=np.zeros_like(canvas)
    occlusion=np.zeros((h,w),np.uint8)
    for _,xy,rgb in triangles:
        transformed=cv2.perspectiveTransform(xy[None,:,:],H)[0]
        if not np.isfinite(transformed).all():continue
        if transformed[:,0].max()<-4 or transformed[:,1].max()<-4 or transformed[:,0].min()>w+4 or transformed[:,1].min()>h+4:continue
        poly=np.round(transformed).astype(np.int32)
        color=tuple(int(v) for v in reversed(rgb))
        cv2.fillConvexPoly(polygon_layer,poly,color,lineType=cv2.LINE_AA)
        cv2.fillConvexPoly(occlusion,poly,255,lineType=cv2.LINE_AA)
    mix=cv2.addWeighted(canvas,1-alpha,polygon_layer,alpha,0)
    mask=(occlusion>0)
    canvas[mask]=mix[mask]
    return canvas


def label(frame,t,inliers,threshold=22):
    h,w=frame.shape[:2]
    overlay=frame.copy();cv2.rectangle(overlay,(13,13),(w-13,109),(26,32,37),-1)
    cv2.addWeighted(overlay,.77,frame,.23,0,frame)
    cv2.putText(frame,'INTERIOR AR  |  OFFLINE PROOF OF CONCEPT',(24,45),cv2.FONT_HERSHEY_SIMPLEX,.49,(241,247,250),1,cv2.LINE_AA)
    cv2.putText(frame,'Approximate alignment - not surveyed/ARCore',(24,71),cv2.FONT_HERSHEY_SIMPLEX,.46,(82,215,242),1,cv2.LINE_AA)
    cv2.putText(frame,f't = {t:4.2f}s    SIFT inliers = {inliers}',(24,95),cv2.FONT_HERSHEY_SIMPLEX,.45,(225,229,229),1,cv2.LINE_AA)
    return frame


def main():
    p=argparse.ArgumentParser();p.add_argument('--input',required=True);p.add_argument('--glb',required=True);p.add_argument('--output',required=True)
    p.add_argument('--seconds',type=float,default=3.1);p.add_argument('--maxfps',type=float,default=12);args=p.parse_args()
    cap=cv2.VideoCapture(args.input);fps=cap.get(cv2.CAP_PROP_FPS); width=int(cap.get(3));height=int(cap.get(4))
    ref_frame=round(1.0*fps)
    cap.set(cv2.CAP_PROP_POS_FRAMES,ref_frame);ok,ref=cap.read();assert ok
    sift=cv2.SIFT_create(nfeatures=1900);bf=cv2.BFMatcher();k0,d0=sift.detectAndCompute(cv2.cvtColor(ref,cv2.COLOR_BGR2GRAY),None)
    K,rvec,tvec,error=camera_reference()
    scene=trimesh.load(args.glb,force='scene')
    tris=project_scene(scene,K,rvec,tvec)
    print('calibration reprojection error',error,'render triangles',len(tris))
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
    fourcc=cv2.VideoWriter_fourcc(*'mp4v')
    writer=cv2.VideoWriter(str(output),fourcc,args.maxfps,(width,height))
    tracker=[]
    steps=int(args.seconds*args.maxfps)
    for index in range(steps):
        t=index/args.maxfps;cap.set(cv2.CAP_PROP_POS_MSEC,t*1000);ok,frame=cap.read()
        if not ok:break
        H,good,inliers=estimate_tracking(ref,frame,sift,bf,k0,d0)
        stable=H is not None and inliers>=25 and inliers/max(good,1)>.25 and np.linalg.cond(H)<5e8
        if stable:
            frame=render_reference(frame,tris,H,alpha=.93)
        else:
            frame=cv2.copyMakeBorder(frame,0,0,0,0,cv2.BORDER_DEFAULT)
        label(frame,t,inliers)
        writer.write(frame)
        tracker.append({'t_s':round(t,3),'matches':good,'inliers':inliers,'overlay':bool(stable)})
        if index%12==0:print(index,'inliers',inliers,'stable',stable,flush=True)
    writer.release();cap.release()
    output.with_suffix('.json').write_text(json.dumps({'reprojection_error_px':error, 'camera_calibration':'approximate/manual; no physical ground truth','frames':tracker,'kind':'illustrative_offline_projection'},indent=2))
    print('Saved',output,output.stat().st_size,'bytes', 'stable frames',sum(x['overlay'] for x in tracker),'/',len(tracker))

if __name__=='__main__':main()
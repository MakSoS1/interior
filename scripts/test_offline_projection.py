import unittest
import numpy as np
import cv2
from offline_video_overlay import camera_reference, estimate_tracking, FLOOR_WORLD

class OfflineProjectionTest(unittest.TestCase):
    def test_sift_tracker_recovers_short_shift(self):
        rng=np.random.default_rng(41)
        ref=rng.integers(0,255,size=(640,360,3)).astype('uint8')
        observed=cv2.warpAffine(ref,np.float32([[1,0,13],[0,1,-9]]),(360,640))
        sift=cv2.SIFT_create(nfeatures=1500)
        kp,desc=sift.detectAndCompute(cv2.cvtColor(ref,cv2.COLOR_BGR2GRAY),None)
        H,matches,inliers=estimate_tracking(ref,observed,sift,cv2.BFMatcher(),kp,desc)
        self.assertIsNotNone(H)
        self.assertGreater(inliers,75)
        p=np.array([[[140.,180.]]],dtype=np.float32)
        pp=cv2.perspectiveTransform(p,H)[0,0]
        np.testing.assert_allclose(pp,[153.,171.],atol=1.2)

    def test_manual_projection_does_not_claim_measured_camera(self):
        K,rvec,tvec,error=camera_reference()
        self.assertIsNone(error)
        pts,_=cv2.projectPoints(FLOOR_WORLD,rvec,tvec,K,None)
        p=pts.reshape(-1,2)
        self.assertTrue((p[:,0]>0).all() and (p[:,0]<720).all())
        self.assertTrue((p[:,1]>0).all() and (p[:,1]<1280).all())

if __name__=='__main__': unittest.main()

package com.maksos.interiorar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class FloorCalibrationMachineTest {
    @Test fun threeFloorPointsBuildBasisAndRealMetreMeasurements() {
        val machine = FloorCalibrationMachine(PlacementTarget.ISLAND)
        assertTrue(machine.add(Point3(1f,0f,2f)) is CalibrationUpdate.NeedNext)
        assertTrue(machine.add(Point3(3.55f,0f,2f)) is CalibrationUpdate.NeedNext)
        val ready = machine.add(Point3(1f,0f,3.3f)) as CalibrationUpdate.Ready
        assertEquals(0f, ready.calibration.yawRadians, 1e-5f)
        assertEquals(2.55f, ready.calibration.baselineMetres, 1e-4f)
        assertEquals(1.3f, ready.calibration.forwardMetres, 1e-4f)
        assertEquals(0, machine.count)
        assertEquals(1f, ready.calibration.xAxis().x,1e-5f)
        assertEquals(1f, ready.calibration.zAxis().z,1e-5f)
    }
    @Test fun rotationAtRightAngleStillPointsIntoRoom() {
        val m=FloorCalibrationMachine(PlacementTarget.WALL)
        m.add(Point3(0f,0f,0f));m.add(Point3(0f,0f,2f))
        val ready=m.add(Point3(-1f,0f,0f)) as CalibrationUpdate.Ready
        assertEquals((-PI/2).toFloat(),ready.calibration.yawRadians,1e-5f)
        assertEquals(-1f,ready.calibration.zAxis().x,1e-5f)
    }
    @Test fun rejectsMirrorSideInsteadOfRotatingFurnitureIntoWall() {
        val m=FloorCalibrationMachine(PlacementTarget.ISLAND)
        m.add(Point3(0f,0f,0f));m.add(Point3(2f,0f,0f))
        assertTrue(m.add(Point3(0f,0f,-1f)) is CalibrationUpdate.Invalid)
        assertEquals(2,m.count)
        assertTrue(m.add(Point3(0f,0f,1f)) is CalibrationUpdate.Ready)
    }
    @Test fun rejectsWrongHeightAndKeepsPreviouslyMeasuredPoints() {
        val m=FloorCalibrationMachine(PlacementTarget.WALL)
        m.add(Point3(0f,0f,0f))
        assertTrue(m.add(Point3(1f,.3f,0f)) is CalibrationUpdate.Invalid)
        assertEquals(1,m.count)
    }
    @Test fun rejectsShortOrNonfiniteBaselineAndRequiresMetres() {
        val m=FloorCalibrationMachine(PlacementTarget.WALL)
        m.add(Point3(0f,0f,0f))
        assertTrue(m.add(Point3(.3f,0f,0f)) is CalibrationUpdate.Invalid)
        assertTrue(m.add(Point3(Float.NaN,0f,0f)) is CalibrationUpdate.Invalid)
        assertEquals(1,m.count)
    }
    @Test fun fridgeMayUseShorterBaseline() {
        val m=FloorCalibrationMachine(PlacementTarget.FRIDGE)
        m.add(Point3(0f,0f,0f))
        assertTrue(m.add(Point3(.4f,0f,0f)) is CalibrationUpdate.NeedNext)
    }
    @Test fun adjustmentUsesExactMetresAndWrapsAngle() {
        val p=ModuleAdjustment(.01f,0f,179f).adjusted(dx=.02f,dz=.05f,dyaw=5f)
        assertEquals(.03f,p.xMeters,1e-5f)
        assertEquals(.05f,p.zMeters,1e-5f)
        assertEquals(-176f,p.yawDegrees,1e-4f)
    }
}

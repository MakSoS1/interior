package com.maksos.interiorar

import com.google.android.filament.Scene
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Runs AFTER SceneView 4.57 onARFrame (notifyHostOfFrame/onSessionUpdated).
 * SceneView retains ownership of SH/cubemap native resources. We set ABSOLUTE
 * output intensities against captured baselines, never multiply last-frame output.
 * Only virtual light sources change; camera exposure and background are untouched.
 */
class RuntimeLightController(private val light:LightNode,private val scene:Scene,private val baselineIbl:Float) {
    private val exposure=ExposureSmoother()
    private val magnitude=ExposureSmoother(.35f)
    private var lastFrame=0L
    private var lastSample=0L
    private var lastEstimate=0L
    private var lumaGain=1f
    private var targetMagnitude=.35f
    private var color=floatArrayOf(1f,1f,1f)
    private var direction=floatArrayOf(0f,-1f,0f)
    var status="Свет: нейтральное ожидание"
        private set
    var appliedGain=.82f
        private set
    fun update(session:Session,frame:Frame,settings:LightingSettings) {
        val now=frame.timestamp
        if(now<=0 || now==lastFrame) return
        val dt=if(lastFrame==0L || now<lastFrame) 1f/30 else ((now-lastFrame)/1e9).toFloat().coerceAtMost(.2f)
        lastFrame=now
        val active=frame.camera.trackingState==TrackingState.TRACKING
        val estimate=frame.lightEstimate
        if(active && !settings.lock && now-lastSample>=200_000_000L) {
            lastSample=now
            try {
                frame.acquireCameraImage().use { image ->
                    val p=image.planes[0]
                    lumaGain=LumaMatching.gain(sampleLuma(p.buffer,image.width,image.height,p.rowStride,p.pixelStride))
                }
            } catch(_:com.google.ar.core.exceptions.NotYetAvailableException) {
                // The CPU camera stream may lag. Keep the previous bounded estimate.
            } catch(_:IllegalStateException) { }
        }
        if(active && !settings.lock && estimate.state==LightEstimate.State.VALID && estimate.timestamp!=lastEstimate) {
            lastEstimate=estimate.timestamp
            val alpha=(1f-exp(-dt/.6f)).coerceIn(0f,1f)
            if(session.config.lightEstimationMode==Config.LightEstimationMode.ENVIRONMENTAL_HDR) {
                val rgb=estimate.environmentalHdrMainLightIntensity
                if(rgb.size>=3 && rgb.all { it.isFinite() && it>=0 }) {
                    val top=rgb.maxOrNull() ?: .35f
                    targetMagnitude=top.coerceIn(.05f,4f)
                    if(top>1e-5f) for(i in 0..2)color[i]+=(rgb[i]/top-color[i])*alpha
                }
                val d=estimate.environmentalHdrMainLightDirection
                if(d.size>=3 && d.all { it.isFinite() }) {
                    val next=FloatArray(3){direction[it]*(1-alpha)-d[it]*alpha}
                    val norm=sqrt(next.sumOf { (it*it).toDouble() }).toFloat()
                    if(norm>.01f)direction=FloatArray(3){next[it]/norm}
                }
                status="HDR + согласование яркости"
            } else {
                val correction=FloatArray(4);estimate.getColorCorrection(correction,0)
                if(correction.all { it.isFinite() && it>=0 }) {
                    targetMagnitude=(correction[3]*1.8f).coerceIn(.05f,4f)
                    val max=correction.take(3).maxOrNull()?.coerceAtLeast(.001f) ?: 1f
                    for(i in 0..2)color[i]+=((correction[i]/max).pow(2.2f)-color[i])*alpha
                }
                status="Оценка общего света (без HDR)"
            }
        }
        val natural=if(settings.natural) .82f*lumaGain else 1f
        val target=natural*2f.pow(settings.exposureStops)
        if(!settings.lock)appliedGain=exposure.update(target,dt)
        val factor=if(settings.lock)magnitude.value else magnitude.update(targetMagnitude,dt)
        // The native estimator overwrites these each frame; reapply smoothed absolute values.
        light.intensity=10_000f*factor*appliedGain
        light.color=Color(color[0],color[1],color[2])
        light.lightDirection=Direction(direction[0],direction[1],direction[2])
        scene.indirectLight?.intensity=baselineIbl*appliedGain
        if(!active)status="Трекинг приостановлен · яркость сохранена"
        if(settings.lock)status="Яркость зафиксирована · отражения остаются живыми"
    }
    companion object {
        /** Grid sampling respects padded Y planes and does not retain or upload camera images. */
        fun sampleLuma(buffer:ByteBuffer,width:Int,height:Int,rowStride:Int,pixelStride:Int):FloatArray {
            if(width<1 || height<1 || rowStride<1 || pixelStride<1)return floatArrayOf()
            val b=buffer.duplicate();val start=b.position();val end=b.limit();val output=ArrayList<Float>(768)
            for(j in 1..24)for(i in 1..32) {
                val x=(i*width/33).coerceAtMost(width-1);val y=(j*height/25).coerceAtMost(height-1)
                val idx=start+y.toLong()*rowStride+x.toLong()*pixelStride
                if(idx>=start && idx<end) output.add((b.get(idx.toInt()).toInt() and 255)/255f)
            }
            return output.toFloatArray()
        }
    }
}

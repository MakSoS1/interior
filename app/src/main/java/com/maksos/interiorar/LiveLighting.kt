package com.maksos.interiorar

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Session
import io.github.sceneview.ar.light.LightEstimator
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.node.LightNode
import kotlin.math.exp
import kotlin.math.sqrt

/** Single owner of virtual HDR light. Camera background is never colour-graded. */
class LiveLighting(private val engine:Engine,private val scene:Scene,private val camera:ARCameraNode,
    loader:EnvironmentLoader,private val environment:Environment,private val main:LightNode) {
    private val estimator=LightEstimator(engine,loader.iblPrefilter)
    private val baseDirect=main.intensity
    private val baseIndirect=environment.indirectLight?.intensity ?: 30_000f
    private val intensity=LightingFilter(.9)
    private val gain=LightingFilter(1.4,.65)
    private var color=floatArrayOf(1f,1f,1f)
    private var direction=floatArrayOf(0f,-1f,0f)
    private var sh:FloatArray?=null
    private var owned:IndirectLight?=null
    private var lastEstimate=0L;private var lastUpdate=0L;private var lastCamera=0L
    private var brightness=1f;private var factor=1f;private var destroyed=false
    var gainApplied=.683f;private set
    var validEstimates=0;private set
    var status="Ожидание оценки света";private set
    var frames=0L;private set
    fun update(session:Session,frame:Frame,settings:LightSettings) {
        if(destroyed || frame.timestamp<=0)return
        frames++;val now=frame.timestamp
        if(settings.natural && now-lastCamera>=500_000_000L){
            lastCamera=now
            try{
                frame.acquireCameraImage().use{image ->
                    val p=image.planes[0];val b=p.buffer;val start=b.position();val samples=IntArray(192)
                    for(y in 0 until 12)for(x in 0 until 16){
                        val px=(x+.5f)*image.width/16;val py=(y+.5f)*image.height/12
                        samples[y*16+x]=b.get(start+py.toInt()*p.rowStride+px.toInt()*p.pixelStride).toInt() and 255
                    }
                    brightness=CameraBrightness.gain(samples)
                }
            }catch(_:com.google.ar.core.exceptions.NotYetAvailableException){
                // Keep the previous bounded gain until the CPU camera image becomes available.
            }catch(_:Exception){brightness=1f}
        }
        gainApplied=gain.update(if(settings.natural)settings.gain()*brightness else 1f,now)
        val light=frame.lightEstimate
        if(light.state==LightEstimate.State.VALID && light.timestamp!=lastEstimate && now-lastUpdate>=100_000_000L){
            val dt=if(lastUpdate==0L)1.0 else ((now-lastUpdate)/1e9).coerceIn(0.0,.25)
            lastUpdate=now;lastEstimate=light.timestamp
            val blend=if(validEstimates==0 || !settings.natural)1f else (1-exp(-dt/.9)).toFloat()
            clearOwned()
            try{
                val estimate=estimator.update(session,frame,camera.camera)
                if(estimate!=null){
                    estimate.mainLightIntensity?.let{raw ->if(raw.isFinite() && raw>=0)factor=if(settings.natural)intensity.update(raw,now) else raw}
                    estimate.mainLightColor?.let{c ->
                        // Ordinary component getters avoid JVM-21-only inline r/g/b aliases.
                        val v=floatArrayOf(c.x,c.y,c.z)
                        if(v.all{it.isFinite() && it>=0})for(i in 0..2)color[i]+=(v[i]-color[i])*blend
                    }
                    estimate.mainLightDirection?.let{d ->
                        val v=floatArrayOf(d.x,d.y,d.z)
                        if(v.all{it.isFinite()}){
                            val candidate=FloatArray(3){direction[it]+(v[it]-direction[it])*blend}
                            val length=sqrt(candidate.sumOf{(it*it).toDouble()}).toFloat()
                            if(length>.0001f)direction=FloatArray(3){candidate[it]/length}
                        }
                    }
                    estimate.irradiance?.let{raw ->if(raw.size==27 && raw.all{it.isFinite()}){
                        val previous=sh;sh=if(previous==null)raw.copyOf() else FloatArray(27){previous[it]+(raw[it]-previous[it])*blend}
                    }}
                    val reflections=estimate.reflections ?: environment.indirectLight?.reflectionsTexture
                    val harmonics=sh
                    if(harmonics!=null && reflections!=null){
                        owned=IndirectLight.Builder().irradiance(3,harmonics).reflections(reflections).intensity(baseIndirect*gainApplied).build(engine)
                        scene.indirectLight=owned
                    }
                    validEstimates++;status=if(settings.natural)"HDR · естественный свет" else "HDR · без коррекции"
                }
            }catch(e:Exception){status="Свет: нейтральный резервный режим";android.util.Log.w("InteriorLighting","Light estimate unavailable",e)}
        }else if(lastUpdate>0 && now-lastUpdate>3_000_000_000L)status="Свет: удерживается последняя оценка"
        main.intensity=baseDirect*factor*gainApplied
        main.color=Color(color[0],color[1],color[2],1f)
        main.lightDirection=Direction(direction[0],direction[1],direction[2])
        (owned ?: environment.indirectLight)?.intensity=baseIndirect*gainApplied
    }
    private fun clearOwned(){scene.indirectLight=environment.indirectLight;owned?.let{engine.destroyIndirectLight(it)};owned=null}
    fun destroy(){if(destroyed)return;destroyed=true;clearOwned();estimator.destroy()}
}

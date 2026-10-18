package me.rerere.rikkahub.data.remote.p14

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal enum class P14Availability { UNSUPPORTED, DISABLED, PERMISSION_REQUIRED, READY, DEGRADED }
internal data class P14EffectiveCapability(val id:String, val state:P14Availability, val required:Boolean, val reason:String)

/** The Profile never dynamically loads code: only these native capabilities can execute. */
internal object P14CapabilityRegistry {
    private val compiled = setOf("text","photo","voice","scan","file","share","observation.location")
    private val yaml = Yaml(SafeConstructor(LoaderOptions()))
    fun resolve(source:String, granted:Set<String>, available:Set<String> = compiled):List<P14EffectiveCapability> {
        P14ProfileContract.validate(source)
        val root=yaml.load<Any>(source) as Map<*,*>
        val capture=(root["capture"] as? Map<*,*>).orEmpty()
        val obs=(root["observation"] as? Map<*,*>).orEmpty()
        val settings=linkedMapOf<String,Map<*,*>>()
        capture.forEach { (k,v) -> if(k!="draft_mode" && v is Map<*,*>) settings[k.toString()]=v }
        obs.forEach { (k,v) -> if(v is Map<*,*>) settings["observation.$k"]=v }
        return settings.map { (name,config) ->
            val enabled=config["enabled"]==true
            val required=config["required"]==true
            val needed=when(name) {
                "scan" -> setOf("CAMERA")
                "voice" -> setOf("MICROPHONE")
                "observation.location" -> setOf("LOCATION","BACKGROUND_LOCATION")
                else -> emptySet()
            }
            val status=when {
                name !in compiled || name !in available -> P14Availability.UNSUPPORTED
                !enabled -> P14Availability.DISABLED
                !granted.containsAll(needed) -> P14Availability.PERMISSION_REQUIRED
                else -> P14Availability.READY
            }
            P14EffectiveCapability(name,status,required,when(status) {
                P14Availability.UNSUPPORTED->"Not compiled into this app"
                P14Availability.DISABLED->"Disabled by Profile"
                P14Availability.PERMISSION_REQUIRED->"Grant ${ (needed-granted).joinToString() } in Android Settings"
                P14Availability.READY->"Ready"
                P14Availability.DEGRADED->"Unavailable"
            })
        }
    }

    fun granted(context:Context):Set<String> = buildSet {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) add("CAMERA")
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) add("MICROPHONE")
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) add("LOCATION")
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED) add("BACKGROUND_LOCATION")
    }

    fun activation(source:String,granted:Set<String>):List<P14EffectiveCapability> {
        val states=resolve(source,granted)
        val impossible=states.filter { it.required && it.state != P14Availability.READY }
        require(impossible.isEmpty()) { "Required capability unavailable: ${impossible.joinToString { it.id }}" }
        // Required capabilities may not silently bypass Android permission grants.
        return states
    }
}

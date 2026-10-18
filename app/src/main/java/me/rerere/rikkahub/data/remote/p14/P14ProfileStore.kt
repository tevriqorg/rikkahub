package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Portable, non-executable profile. No credential is serialized or logged. */
internal class P14ProfileStore(private val context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("p14-native-profile",Context.MODE_PRIVATE)
    private val yaml=Yaml(SafeConstructor(LoaderOptions()))
    fun exportProfile():String = prefs.getString("active_profile",null) ?: DEFAULT_PROFILE
    fun captureEnabled(kind:String):Boolean {
        require(kind in setOf("text","photo","scan","file","voice","share"))
        val root=yaml.load<Any>(exportProfile()) as? Map<*,*> ?: return false
        val capture=root["capture"] as? Map<*,*> ?: return false
        val section=capture[kind] as? Map<*,*>
        return section?.get("enabled") == true
    }
    fun locationEnabled():Boolean = readLocationPolicy(exportProfile()).first
    fun retentionDays():Int = readLocationPolicy(exportProfile()).second
    fun locationMinDistanceMeters():Float {
        val root=yaml.load<Any>(exportProfile()) as? Map<*,*> ?: return 500f
        val location=(root["observation"] as? Map<*,*>)?.get("location") as? Map<*,*>
        val policy=location?.get("policy") as? Map<*,*>
        return ((policy?.get("min_distance_m") as? Number)?.toFloat() ?: 500f).coerceIn(100f,10000f)
    }

    fun importProfile(source:String) {
        P14ProfileContract.validate(source)
        check(prefs.edit().putString("active_profile",source).commit()) { "Profile activation failed" }
        P14LocationObservationScheduler.configure(context)
    }

    private fun readLocationPolicy(source:String):Pair<Boolean,Int> {
        val validation=P14ProfileContract.validate(source)
        return validation.locationEnabled to validation.localRetentionDays
    }
    companion object {
        private val DEFAULT_PROFILE="""schema: p14.profile/v1
id: personal-default
name: Personal Default
version: 1
capture:
  draft_mode: single-active
  text: {enabled: true}
  photo: {enabled: true}
  scan: {enabled: true}
  file: {enabled: true}
  voice: {enabled: true}
  share: {enabled: true}
observation:
  location:
    enabled: false
    required: false
    policy: {mode: significant-change, min_distance_m: 500}
    retention: {local_days: 7}
"""
    }
}

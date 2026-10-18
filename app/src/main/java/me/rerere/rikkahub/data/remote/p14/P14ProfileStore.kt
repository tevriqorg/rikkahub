package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Portable, non-executable profile. No credential is serialized or logged. */
internal class P14ProfileStore(context:Context) {
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

    fun importProfile(source:String) {
        require(source.length<=32768) { "Profile is too large" }
        val obj = yaml.load<Any>(source) as? Map<*,*> ?: error("Profile must be YAML mapping")
        require(obj["schema"]=="p14.profile/v1") { "Unsupported P14 profile schema" }
        require(obj["id"] is String && (obj["id"] as String).isNotBlank())
        require((obj["version"] as? Number)?.toInt()?.let{it>=1}==true)
        // Secrets are forbidden anywhere in portable configuration; credential_ref is a logical ref only.
        fun validate(node:Any?) {
            if(node is Map<*,*>) for((k,v) in node) {
                val key=k.toString().lowercase()
                require(key !in setOf("token","password","private_key","api_key","secret","authorization")) { "Profile must not contain secrets" }
                if(key=="credential_ref") require(v is String && v.length<=128 && !v.contains(' '))
                validate(v)
            } else if(node is List<*>) node.forEach(::validate)
        }
        validate(obj)
        readLocationPolicy(source)
        val capture=obj["capture"] as? Map<*,*>
        require(capture?.get("draft_mode") in setOf(null,"single-active"))
        val supported=setOf("text","photo","scan","file","voice","share","draft_mode")
        require(capture?.keys?.all{it in supported}!=false) { "Unknown Capture capability" }
        check(prefs.edit().putString("active_profile",source).commit()) { "Profile activation failed" }
    }

    private fun readLocationPolicy(source:String):Pair<Boolean,Int> {
        val root=yaml.load<Any>(source) as? Map<*,*> ?: error("Invalid Profile")
        val observation=root["observation"] as? Map<*,*>
        require(observation?.keys?.all{it=="location"}!=false) { "Unknown Observation capability" }
        val location=observation?.get("location") as? Map<*,*>
        val enabled=location?.get("enabled") as? Boolean ?: false
        val retention=location?.get("retention") as? Map<*,*>
        val days=(retention?.get("local_days") as? Number)?.toInt() ?: 7
        require(days in 1..30) { "Location retention must be 1..30 days" }
        return enabled to days
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

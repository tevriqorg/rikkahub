package me.rerere.rikkahub.data.remote.p14

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Pure contract validation before an imported Profile can replace the active one. */
internal object P14ProfileContract {
    private val compiledCapture = setOf("text", "photo", "scan", "voice", "file", "share")
    private val yaml = Yaml(SafeConstructor(LoaderOptions().apply {
        maxAliasesForCollections = 10
        codePointLimit = 32768
    }))

    data class Validation(val unsupportedOptional: Set<String>, val locationEnabled: Boolean, val localRetentionDays: Int)

    fun validate(source: String): Validation {
        require(source.length <= 32768) { "Profile is too large" }
        val root = yaml.load<Any>(source) as? Map<*, *> ?: error("Profile must be a mapping")
        require(root["schema"] == "p14.profile/v1") { "Unsupported profile schema" }
        require((root["id"] as? String)?.isNotBlank() == true) { "Profile id missing" }
        require((root["version"] as? Number)?.toInt()?.let { it >= 1 } == true) { "Profile version invalid" }
        validateNoSecrets(root)
        val capture = root["capture"] as? Map<*, *> ?: emptyMap<Any, Any>()
        require(capture["draft_mode"] in listOf(null, "single-active")) { "Unsupported draft mode" }
        val unsupported = mutableSetOf<String>()
        for ((key, value) in capture) {
            if (key == "draft_mode") continue
            val name = key as? String ?: error("Invalid capture capability name")
            val options = value as? Map<*, *> ?: error("Malformed $name capability")
            require(options["enabled"] is Boolean) { "Missing enabled flag for $name" }
            require(options["required"] == null || options["required"] is Boolean) { "Invalid required flag for $name" }
            if (name !in compiledCapture) {
                require(options["required"] != true) { "Unsupported required capability: $name" }
                unsupported += name
            }
        }
        val observations = root["observation"] as? Map<*, *> ?: emptyMap<Any, Any>()
        for ((key, options) in observations) {
            if (key != "location") {
                val config = options as? Map<*, *> ?: error("Malformed observation capability $key")
                require(config["required"] != true) { "Unsupported required observation: $key" }
                unsupported += "observation.$key"
            }
        }
        val location = observations["location"] as? Map<*, *>
        require(location?.get("enabled") in listOf(null, true, false)) { "Invalid Location enabled flag" }
        require(location?.get("required") in listOf(null, true, false)) { "Invalid Location required flag" }
        val policy = location?.get("policy") as? Map<*, *>
        require(policy?.get("mode") in listOf(null, "significant-change")) { "Unsupported Location policy" }
        val distance = (policy?.get("min_distance_m") as? Number)?.toDouble() ?: 500.0
        require(distance.isFinite() && distance in 100.0..10000.0) { "Location distance must be 100..10000m" }
        val retention = location?.get("retention") as? Map<*, *>
        val days = (retention?.get("local_days") as? Number)?.toInt() ?: 7
        require(days in 1..30) { "Location retention must be 1..30 days" }
        return Validation(unsupported, location?.get("enabled") == true, days)
    }

    private fun validateNoSecrets(node: Any?) {
        when (node) {
            is Map<*, *> -> node.forEach { (key, value) ->
                val name = key?.toString()?.lowercase().orEmpty()
                require(name !in setOf("token", "password", "private_key", "api_key", "secret", "authorization", "bearer")) {
                    "Profile must not include credentials"
                }
                if (name == "credential_ref") require(value is String && value.isNotBlank() && value.length <= 128 && !value.contains(' '))
                validateNoSecrets(value)
            }
            is List<*> -> node.forEach(::validateNoSecrets)
        }
    }
}

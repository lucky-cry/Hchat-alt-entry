package h.Hchat.hooks.items.script.agent

internal object ScriptPluginAgentRequestHeaders {
    private val validName = Regex("[!#$%&'*+.^_`|~A-Za-z0-9-]+")
    private val reserved = setOf(
        "accept", "connection", "content-encoding", "content-length", "content-type", "expect", "host",
        "keep-alive", "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade"
    )
    private val authNames = setOf("authorization", "x-api-key", "x-goog-api-key")

    fun parse(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val names = hashSetOf<String>()
        text.split('\n').forEachIndexed { index, raw ->
            val line = raw.removeSuffix("\r")
            if (line.all { it == ' ' || it == '\t' }) return@forEachIndexed
            val separator = line.indexOf(':')
            require(separator > 0) { "第 ${index + 1} 行请填写「名称: 值」" }
            val name = line.substring(0, separator).trim(' ', '\t')
            val value = line.substring(separator + 1).trim(' ', '\t')
            require(validName.matches(name)) { "第 ${index + 1} 行的请求头名称无效" }
            val lowerName = name.lowercase()
            require(lowerName !in reserved) { "第 ${index + 1} 行的请求头由客户端管理" }
            require(isValidValue(value)) { "第 ${index + 1} 行的请求头值包含无效字符" }
            require(names.add(lowerName)) { "第 ${index + 1} 行的请求头名称重复" }
            result[name] = value
        }
        return result
    }

    fun merge(defaults: Map<String, String>, config: ScriptPluginAgentConfig): Map<String, String> {
        if (config.customHeaders.isEmpty()) return defaults
        val result = defaults.toMutableMap()
        for ((name, value) in config.customHeaders) {
            val lowerName = name.lowercase()
            require(validName.matches(name) && lowerName !in reserved && isValidValue(value)) {
                "自定义请求头无效，请检查模型配置"
            }
            val previous = result.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (previous == null) {
                result[name] = value
            } else if (config.allowAuthHeaderOverride && lowerName in authNames) {
                result[previous] = value
            }
        }
        return result
    }

    private fun isValidValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }
}

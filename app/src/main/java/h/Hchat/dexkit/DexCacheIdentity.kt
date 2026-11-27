package h.Hchat.dexkit

/** 仅按微信包体与补丁身份判断定位缓存是否仍属于同一运行时。 */
object DexCacheIdentity {
    @JvmStatic
    fun sameRuntime(cachedKey: String?, runtimeKey: String?): Boolean {
        if (cachedKey.isNullOrBlank() || runtimeKey.isNullOrBlank()) return false
        if (cachedKey == runtimeKey) return true
        val cached = cachedKey.split('|')
        val runtime = runtimeKey.split('|')
        if (cached.size < 7 || runtime.size < 7) return false
        // 第 8 段（索引 7）是 ClassLoader hash，同一微信运行时可能每次启动变化；
        // 其余字段（包括模块版本指纹）必须一致，避免跨模块版本复用旧解析结果。
        val cachedStable = cached.filterIndexed { index, _ -> index != 7 }
        val runtimeStable = runtime.filterIndexed { index, _ -> index != 7 }
        return cachedStable == runtimeStable
    }
}

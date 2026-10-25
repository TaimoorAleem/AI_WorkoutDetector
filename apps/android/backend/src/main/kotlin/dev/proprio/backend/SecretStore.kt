package dev.proprio.backend

/** Small string vault for secrets (the refresh token). Android backs it with Keystore-wrapped Tink AEAD. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemorySecretStore : SecretStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

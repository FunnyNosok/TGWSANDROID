package com.tgwsproxy.proxy

import java.security.SecureRandom

data class ProxyConfig(
    var port: Int = 1443,
    var host: String = "127.0.0.1",
    var secret: String = generateSecret(),
    var dcRedirects: MutableMap<Int, String> = mutableMapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220"
    ),
    var bufferSize: Int = 256 * 1024,
    var poolSize: Int = 4,
    var fallbackCfProxy: Boolean = true,
    var fallbackCfProxyPriority: Boolean = true,
    var cfProxyUserDomain: String = "",
    var dpiBypass: Boolean = false,
    var fakeTlsDomain: String = "",
    var proxyProtocol: Boolean = false
) {
    companion object {
        fun generateSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun parseDcIpList(list: List<String>): MutableMap<Int, String> {
            val result = mutableMapOf<Int, String>()
            for (entry in list) {
                val parts = entry.split(":", limit = 2)
                if (parts.size != 2) throw IllegalArgumentException("Invalid DC:IP format: $entry")
                val dc = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid DC number: ${parts[0]}")
                result[dc] = parts[1]
            }
            return result
        }
    }
}

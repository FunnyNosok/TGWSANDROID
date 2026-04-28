package com.tgwsproxy.proxy

object Constants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEFL
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEEL
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDDL

    val RESERVED_FIRST_BYTES = setOf(0xEF.toByte())

    val RESERVED_STARTS = setOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44),   // HEAD
        byteArrayOf(0x50, 0x4F, 0x53, 0x54),   // POST
        byteArrayOf(0x47, 0x45, 0x54, 0x20),   // GET
        byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02)
    ).map { it.toList() }.toSet()

    val RESERVED_CONTINUE = byteArrayOf(0x00, 0x00, 0x00, 0x00).toList()

    val ZERO_64 = ByteArray(64)

    val DC_DEFAULT_IPS = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100"
    )

    const val TLS_RECORD_HANDSHAKE: Byte = 0x16
    const val TLS_RECORD_CCS: Byte = 0x14
    const val TLS_RECORD_APPDATA: Byte = 0x17
    const val TLS_APPDATA_MAX = 16384

    const val DC_FAIL_COOLDOWN = 15.0
    const val WS_FAIL_TIMEOUT = 8.0
    const val WS_POOL_MAX_AGE = 120_000L

    fun humanBytes(n: Long): String {
        var value = n.toDouble()
        for (unit in arrayOf("B", "KB", "MB", "GB")) {
            if (kotlin.math.abs(value) < 1024) {
                return "%.1f%s".format(value, unit)
            }
            value /= 1024
        }
        return "%.1fTB".format(value)
    }

    fun wsDomainsForDc(dc: Int, isMedia: Boolean?): List<String> {
        val d = if (dc == 203) 2 else dc
        return if (isMedia == null || isMedia) {
            listOf("kws${d}-1.web.telegram.org", "kws${d}.web.telegram.org")
        } else {
            listOf("kws${d}.web.telegram.org", "kws${d}-1.web.telegram.org")
        }
    }
}

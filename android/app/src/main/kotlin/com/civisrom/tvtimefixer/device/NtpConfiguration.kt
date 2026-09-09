package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.data.isValidNtpServer
import java.net.URI

data class NtpEndpoint(val host: String, val port: Int = 123)

/** Raw значение нужно для точного возврата: Android 14+ поддерживает список URI. */
data class NtpConfiguration(val raw: String) {
    val systemDefault: Boolean get() = raw == "null"
    val endpoints: List<NtpEndpoint> get() {
        if (isValidNtpServer(raw)) return listOf(NtpEndpoint(raw))
        if (raw.length > 4096) return emptyList()
        return runCatching { raw.split('|').map { value ->
            val uri = URI(value)
            require(uri.scheme == "ntp" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.path.isNullOrEmpty())
            val host = requireNotNull(uri.host).removeSurrounding("[", "]")
            require(isValidNtpServer(host) || ':' in host)
            val port = if (uri.port == -1) 123 else uri.port
            require(port in 1..65535)
            NtpEndpoint(host, port)
        } }.getOrDefault(emptyList())
    }
}

enum class NtpActivation { RESTART_REQUIRED, NEXT_REFRESH, UNKNOWN }

fun ntpActivation(api: Int?): NtpActivation = when {
    api == null || api < 23 -> NtpActivation.UNKNOWN
    api <= 29 -> NtpActivation.RESTART_REQUIRED
    else -> NtpActivation.NEXT_REFRESH
}

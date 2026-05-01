// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import java.net.URI

/**
 * Refusal policy for the HA base URL.
 *
 * HTTPS is required unless the host is on the LAN, where the bearer token
 * is judged safe to send in cleartext. "On the LAN" means: loopback
 * (127.0.0.1, ::1), an IPv4 in any RFC1918 range (10/8, 172.16/12,
 * 192.168/16), an IPv6 unique-local-address (fc00::/7), an IPv6
 * link-local (fe80::/10), or a `.local` / `.lan` hostname.
 *
 * Public WAN hosts must use HTTPS. A long-lived bearer token captured on
 * café or train Wi-Fi is full HA compromise; LAN is treated as trusted
 * only because the user's own router sits between phone and internet.
 *
 * CGNAT (100.64.0.0/10) is intentionally NOT treated as LAN. Tailscale and
 * similar overlays serve HTTPS via MagicDNS-issued certs, so users on
 * those setups should configure the HTTPS endpoint, not the raw IP.
 */
object HaUrlPolicy {
    sealed interface Result {
        object Ok : Result
        object Empty : Result
        data class CleartextWanRefused(val host: String) : Result
        object Malformed : Result
    }

    private val DOTTED_QUAD_SHAPED = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun validate(rawUrl: String): Result {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.Empty
        val uri = try {
            URI(url)
        } catch (_: Throwable) {
            return Result.Malformed
        }
        val scheme = uri.scheme?.lowercase() ?: return Result.Malformed
        if (scheme == "https") return Result.Ok
        if (scheme != "http") return Result.Malformed
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return Result.Malformed
        // Strings shaped like IPv4 dotted-quads must have valid octets, otherwise treat
        // as malformed rather than letting them fall through as a hostname.
        if (DOTTED_QUAD_SHAPED.matches(host) && host.split('.').any { (it.toIntOrNull() ?: -1) !in 0..255 }) {
            return Result.Malformed
        }
        return if (isLanHost(host)) Result.Ok else Result.CleartextWanRefused(host)
    }

    /**
     * Short user-facing message for the [Result.CleartextWanRefused] case.
     * Leads with "Refused" so the chip slot (which also surfaces server-side
     * errors like `HTTP 401`) cannot be misread as an HA response. The host
     * is sanitised: non-printables stripped, length-capped so long subdomains
     * don't blow the chip width.
     */
    fun refusalMessage(host: String): String {
        val safe = host.filter { it.code in 0x20..0x7E && it != '"' }
        val display = when {
            safe.isEmpty() -> "<unknown>"
            safe.length > 40 -> safe.take(37) + "..."
            else -> safe
        }
        return "Refused: HTTPS required for non-LAN host ($display)"
    }

    /** Short user-facing message for the [Result.Malformed] case. */
    fun malformedMessage(): String =
        "Refused: URL must start with https:// or http://"

    private fun isLanHost(host: String): Boolean {
        if (host == "localhost") return true
        if (host.endsWith(".local") || host.endsWith(".lan")) return true

        val ipv4 = host.split('.').takeIf { it.size == 4 }?.let { parts ->
            parts.mapNotNull { it.toIntOrNull()?.takeIf { v -> v in 0..255 } }
                .takeIf { it.size == 4 }
        }
        if (ipv4 != null) {
            val a = ipv4[0]; val b = ipv4[1]
            return when {
                a == 10 -> true
                a == 127 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                else -> false
            }
        }

        if (host == "::1") return true
        // IPv6 ULA fc00::/7: first byte 0xfc or 0xfd → leading hextet starts "fc" or "fd".
        if (host.startsWith("fc") || host.startsWith("fd")) {
            // Guard against hostnames that happen to start with "fc"/"fd" — require a colon.
            if (host.contains(':')) return true
        }
        // IPv6 link-local fe80::/10: leading hextet 0xfe80..0xfebf, i.e. high byte 0xfe with
        // second nibble 8/9/a/b.
        if (host.length >= 4 && host[0] == 'f' && host[1] == 'e' && host.contains(':')) {
            val nibble = host[2]
            if (nibble in setOf('8', '9', 'a', 'b')) return true
        }
        return false
    }
}

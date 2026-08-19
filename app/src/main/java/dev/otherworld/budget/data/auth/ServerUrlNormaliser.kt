package dev.otherworld.budget.data.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns whatever a user types into the server field into a canonical base URL. */
object ServerUrlNormaliser {

    private val privateHost = Regex(
        """^(localhost|127\.\d+\.\d+\.\d+|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|.*\.local|.*\.lan|.*\.internal|.*\.home\.arpa)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * IPv6 link-local range fe80::/10: the top 10 bits are fixed, so the first
     * hextet of the canonical (RFC 5952) form always renders as "fe8", "fe9",
     * "fea", or "feb" — the leading "f" is never suppressed because it's non-zero.
     */
    private val ipv6LinkLocalPrefix = Regex("""^fe[89ab]""", RegexOption.IGNORE_CASE)

    /**
     * OkHttp's URL parser is lenient about host characters (it just percent-encodes
     * whatever it's given), so something like "ht!tp://x" — a mistyped scheme that
     * falls through to the no-scheme branch below — still parses "successfully" with
     * host "ht!tp". Restricting the host to characters valid in a DNS name or IPv4
     * literal catches that case as the malformed input it is. IPv6 literals are
     * validated separately (see [normalise]): OkHttp only ever puts a colon into
     * `url.host` for a bracketed literal it has already syntax-checked, so no further
     * character validation is needed for those.
     */
    private val validHost = Regex("""^[a-zA-Z0-9.-]+$""")

    fun normalise(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Enter your Nextcloud address"))

        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val url = withScheme.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("That doesn't look like a web address"))

        // A colon can only appear in url.host when OkHttp parsed a bracketed IPv6
        // literal (an unbracketed colon is consumed as the port separator instead),
        // so this is a reliable — and cheap — way to tell IPv6 apart from a hostname.
        val isIpv6 = url.host.contains(':')
        if (!isIpv6 && !validHost.matches(url.host)) {
            return Result.failure(IllegalArgumentException("That doesn't look like a web address"))
        }

        if (url.scheme == "http" && !isPrivateHost(url.host, isIpv6)) {
            return Result.failure(
                IllegalArgumentException("Use https:// — plain http is only allowed for local addresses")
            )
        }

        val host = if (isIpv6) "[${url.host}]" else url.host
        val path = url.encodedPath.trimEnd('/')
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return Result.success("${url.scheme}://$host$port$path")
    }

    private fun isPrivateHost(host: String, isIpv6: Boolean): Boolean {
        if (!isIpv6) return privateHost.matches(host)
        return host == "::1" ||
            host.startsWith("fc", ignoreCase = true) ||
            host.startsWith("fd", ignoreCase = true) ||
            ipv6LinkLocalPrefix.containsMatchIn(host)
    }
}

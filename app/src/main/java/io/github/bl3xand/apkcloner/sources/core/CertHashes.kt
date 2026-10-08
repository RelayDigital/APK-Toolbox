package io.github.bl3xand.apkcloner.sources.core

import java.security.MessageDigest

object CertHashes {
    private val hex64 = Regex("^[0-9a-fA-F]{64}$")
    private val nonHex = Regex("[^0-9a-fA-F]")
    private val separators = Regex("[\\s,;]+")

    /** SHA-256 of a DER certificate as upper-case, colon-separated hex. */
    fun format(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    /** Accepts a hash with or without separators; anything else is returned upper-cased. */
    fun normalize(raw: String): String {
        val hex = nonHex.replace(raw, "")
        if (!hex64.matches(hex)) return raw.trim().uppercase()
        return hex.chunked(2).joinToString(":").uppercase()
    }

    /** Parses the per-app list of allowed hashes (separated by whitespace, comma or semicolon). */
    fun parseAllowed(raw: String?): Set<String> =
        (raw ?: "").split(separators).map { it.trim() }.filter { it.isNotEmpty() }.map(::normalize).toSet()

    fun isValidList(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        return value.split(separators).map { it.trim() }.filter { it.isNotEmpty() }
            .all { hex64.matches(nonHex.replace(it, "")) }
    }
}

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

package io.github.bl3xand.apkcloner.sources.core

/** The downloaded APK is not signed with an expected certificate. */
class SigningCertMismatchError(
    val hardBlock: Boolean,
    val expected: Set<String>,
    val actual: Set<String>,
) : SourceError(
    code = "SIGNING_CERT_MISMATCH",
    data = mapOf("hardBlock" to hardBlock, "expected" to expected.toList(), "actual" to actual.toList()),
)

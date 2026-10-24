package io.github.bl3xand.apkcloner.sources.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judgment behind the question a differently-signed pick asks: whether the signer of what
 * is on the device and the signer of what the source offers are known to disagree, so that
 * installing means removing the installed app first. The facts come from the caller; this is
 * the decision over them, tested as the pure function it is. What the caller rules out before
 * asking - nothing installed, a clone, the check switched off - is not tested here.
 */
class SigningClashTest {
    private val installed = setOf("aa")
    private val offered = setOf("bb")
    private val both = setOf("aa", "bb")

    @Test
    fun knownDisagreementIsAClash() {
        assertTrue(SourcesInstaller.signingClash(installed, offered, downloadedSigner = emptySet()))
        // The very file, when it was downloaded already: its signer says the same.
        assertTrue(SourcesInstaller.signingClash(installed, offered, offered))
    }

    @Test
    fun matchingSignersAreNotAClash() {
        // The installed app carries the offered signer: an ordinary update.
        assertFalse(SourcesInstaller.signingClash(installed, installed, downloadedSigner = emptySet()))
        // The offered set within the installed one: the same key, more signers (v1 plus v2)
        // installed than the remembered offer lists.
        assertFalse(SourcesInstaller.signingClash(both, installed, downloadedSigner = emptySet()))
    }

    @Test
    fun theDownloadedFileSpeaksOverTheRememberedOffer() {
        // The remembered offer disagrees, but the file itself is signed as installed: a source
        // whose file names carry versions remembers the signer of another file of the branch.
        // The install would succeed; there is nothing to ask about.
        assertFalse(SourcesInstaller.signingClash(installed, offered, installed))
        // And the other way round: the remembered offer agreed, the file does not - the
        // remembered signer was of another file, this one would not go over the install.
        assertTrue(SourcesInstaller.signingClash(installed, installed, offered))
    }

    @Test
    fun anUnknownSideIsNotAClash() {
        // Nothing installed, or nothing ever learned about the source's signer: the question
        // has no subject, and the install's own checks stay the ones that speak.
        assertFalse(SourcesInstaller.signingClash(emptySet(), offered, downloadedSigner = emptySet()))
        assertFalse(SourcesInstaller.signingClash(installed, emptySet(), downloadedSigner = emptySet()))
        // A file whose signer cannot be read falls back to the source's remembered say-so.
        assertTrue(SourcesInstaller.signingClash(installed, offered, downloadedSigner = emptySet()))
    }
}
package io.github.bl3xand.apkcloner.install

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome

    /** Handed to the system; the result arrives later through [InstallReceiver]. */
    data object Pending : InstallOutcome
}

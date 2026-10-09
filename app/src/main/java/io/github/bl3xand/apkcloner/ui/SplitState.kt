package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.merge.MergeResult
import io.github.bl3xand.apkcloner.merge.SplitStep

sealed interface SplitState {
    data object Idle : SplitState
    data class Running(val step: SplitStep) : SplitState
    data class Done(val result: MergeResult) : SplitState
    data class Mismatch(val splits: List<String>) : SplitState
    data class TooLarge(val megabytes: Long) : SplitState
    data class Failed(val message: String) : SplitState
}

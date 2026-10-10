package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.data.CloneInfo

/** What a clone can be picked by in the filter. A clone can be of several kinds at once. */
enum class CloneKind(val label: Int) {
    UPDATE_AVAILABLE(R.string.chip_update_available),
    NOT_UPDATED(R.string.chip_no_updates),
    FROM_ORIGINAL(R.string.chip_clones_original),
    FROM_SOURCE(R.string.chip_clones_source),
    ;

    companion object {
        fun of(clone: CloneInfo): Set<CloneKind> = buildSet {
            if (clone.wantsUpdate) add(UPDATE_AVAILABLE)
            if (clone.frozen) add(NOT_UPDATED)
            if (clone.tracked != null) add(FROM_SOURCE) else if (clone.original != null) add(FROM_ORIGINAL)
        }
    }
}

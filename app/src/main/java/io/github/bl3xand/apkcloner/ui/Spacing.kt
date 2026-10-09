package io.github.bl3xand.apkcloner.ui

/** The few measures every sheet and dialog of this tab is built from, in dp. */
object Spacing {
    /** Side padding of a sheet or dialog body. */
    const val SHEET = 24

    /** Smallest height of anything that is tapped as a row. */
    const val ROW = 56

    /** Empty space that shows on either side of a line between two groups. */
    const val GROUP = 24

    /** Empty space that shows between two things of one group: a label and its chips, a note and a button. */
    const val BLOCK = 16

    /** What a heading or a plain label leaves, of [BLOCK], for the view placed under it. */
    const val UNDER_HEADING = BLOCK - 5
    const val UNDER_LABEL = BLOCK - 2

    // How far the visible part of a view is from its own edge; see trailingSpace().
    /**
     * A heading is pulled this much closer to the line above it. Measured to the top of its
     * capitals the gap would be even, but the eye goes by the body of the letters, and a solid
     * button or card above the line looks nearer than text does - so those get a little more.
     */
    const val HEADING_INSET = 8
    const val TEXT_INSET = 1
    const val BUTTON_INSET = 2
    const val CARD_INSET = -2
    const val FLAT_BUTTON_INSET = 15
    const val FIELD_INSET = 0
    const val SLIDER_INSET = 2

    /** A row with a switch: its text usually ends a little above the row's padding. */
    const val ROW_INSET = 3

    /** Padding above and below every setting, the same as in the main settings. */
    const val ITEM = 10
}

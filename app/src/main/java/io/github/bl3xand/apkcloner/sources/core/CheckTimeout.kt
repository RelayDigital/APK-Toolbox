package io.github.bl3xand.apkcloner.sources.core

/**
 * The one budget every update-check path gives a source to answer, in seconds: the checker's
 * own give-up time, and the wait of a read that a source answers slowly or never. One number,
 * so every path a check can take times out alike - never a mix of budgets where one layer
 * would outwait the other or give up long before it.
 */
const val CHECK_TIMEOUT_SECONDS = 15L
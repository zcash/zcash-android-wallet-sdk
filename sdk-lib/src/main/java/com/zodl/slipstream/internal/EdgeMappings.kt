@file:Suppress("MaxLineLength")

package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.model.BlockHeight

/**
 * R5, section 5.2: the degraded `processorInfo` this adapter can honestly serve - `networkBlockHeight`
 * is the real engine tip; `overallSyncRange`/`firstUnenhancedHeight` describe processor internals
 * that do not exist under this engine and are always `null`. Pulled out as a pure function so the
 * null-field contract is unit-testable without a live [com.zodl.slipstream.internal.SlipstreamEngine].
 */
internal fun toProcessorInfo(networkHeight: BlockHeight?): CompactBlockProcessor.ProcessorInfo =
    CompactBlockProcessor.ProcessorInfo(networkBlockHeight = networkHeight, overallSyncRange = null, firstUnenhancedHeight = null)

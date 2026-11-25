package com.example.domain.compiler

import com.example.data.model.MessageEntity

/**
 * Immutable concatenation view for generation bookkeeping. Building
 * (history + current) copies thousands of MessageEntity references even
 * though pending-partial handling and the completed-turn saver only need
 * list access. Reuse one view for both paths.
 *
 * The caller must not mutate the source history list after creating the view.
 */
internal class GenerationHistoryWithCurrent(
    private val history: List<MessageEntity>,
    private val current: MessageEntity
) : AbstractList<MessageEntity>() {
    override val size: Int = history.size + 1

    override fun get(index: Int): MessageEntity = when {
        index < 0 || index >= size -> throw IndexOutOfBoundsException("index=$index, size=$size")
        index == history.size -> current
        else -> history[index]
    }
}

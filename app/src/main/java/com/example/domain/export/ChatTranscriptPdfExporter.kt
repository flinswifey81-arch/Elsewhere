package com.example.domain.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.repository.MessageRepository
import com.example.domain.story.StoryResponseCodec
import java.io.Closeable
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owner-initiated, offline, plain-text story transcript PDF.
 * Reads the ARCHIVAL selected-variant timeline, NOT paged UI chat bubbles or
 * generation/Wack history. Exporting the visible transcript includes pre-Wack
 * messages, because a Wack resets model context, not the user's saved story.
 *
 * Exports no settings, hidden NPC control regions, provider secrets, unselected
 * regeneration alternatives, unsent drafts, or automatic model requests.
 */
data class ChatPdfTurnRange(val first: Int, val last: Int) {
    init {
        require(first >= 1) { "The first turn must be at least 1." }
        require(last >= first) { "The last turn must be at or after the first." }
    }
    val count: Int get() = last - first + 1
}

data class ChatPdfExportResult(val turns: Int, val pages: Int)

class ChatTranscriptPdfExporter(private val messages: MessageRepository) {
    suspend fun selectedTurnCount(chatId: String): Int =
        messages.countSelectedTurnsForPdf(chatId)

    /** Saves through Android's caller-owned SAF stream; never writes to app-private chat data. */
    suspend fun export(
        chatId: String,
        chatTitle: String,
        range: ChatPdfTurnRange,
        destination: OutputStream
    ): ChatPdfExportResult = withContext(Dispatchers.IO) {
        val originalCount = selectedTurnCount(chatId)
        require(originalCount > 0) { "This chat has no saved turns to export." }
        require(range.last <= originalCount) {
            "The selected range is larger than the saved transcript."
        }
        val pdf = ChatTranscriptPdfRenderer(chatTitle, range)
        try {
            var nextTurn = range.first
            while (nextTurn <= range.last) {
                val expectedPageSize = minOf(128, range.last - nextTurn + 1)
                val batch = messages.getSelectedHistoryForwardPage(
                    chatId = chatId,
                    resetBoundary = null, // The saved, visible story includes pre-Wack turns.
                    offset = nextTurn - 1,
                    pageSize = expectedPageSize
                )
                check(batch.size == expectedPageSize) {
                    "Saved chat changed during PDF export. Try again."
                }
                for (message in batch) {
                    check(message.isPrimaryVariant && message.chatId == chatId) {
                        "Saved chat selection changed during PDF export. Try again."
                    }
                    pdf.append(nextTurn, message)
                    nextTurn++
                }
            }
            check(originalCount == selectedTurnCount(chatId)) {
                "The chat changed during PDF export. Try again."
            }
            pdf.writeTo(destination)
            ChatPdfExportResult(range.count, pdf.pageCount)
        } finally {
            pdf.close()
        }
    }
}

/** Data projected into the PDF must be the same human-readable story shown in chat. */
object ChatPdfText {
    fun speaker(message: MessageEntity): String {
        val snapshot = message.speakerDisplayNameSnapshot.trim()
        if (snapshot.isNotEmpty()) return snapshot
        return when (message.speakerType) {
            SpeakerType.PERSONA -> "Persona"
            SpeakerType.CHARACTER -> "Character"
            SpeakerType.NARRATOR -> "Narrator"
            SpeakerType.DIRECTOR -> "Director"
            SpeakerType.SYSTEM -> "System"
        }
    }

    fun visibleContent(message: MessageEntity): String {
        val withoutNpcControl = NpcStructuredOutputParser.finalVisibleStoryText(message.content)
        val visible = StoryResponseCodec.decode(withoutNpcControl)
            ?.let(StoryResponseCodec::readableTranscript)
            ?: if (StoryResponseCodec.isEncoded(withoutNpcControl)) {
                "[Unable to display this structured response]"
            } else {
                withoutNpcControl
            }
        return visible.replace("\r\n", "\n").replace('\r', '\n')
            .replace("\u0000", "").ifBlank { "(empty message)" }
    }
}

/**
 * Android native vector PDF: Unicode-aware StaticLayout handles word wrapping,
 * newline preservation and splitting even a single oversized message over
 * consecutive pages. Only one message's layout is active at a time.
 */
class ChatTranscriptPdfRenderer(
    private val chatTitle: String,
    private val range: ChatPdfTurnRange
) : Closeable {
    private val document = PdfDocument()
    private var activePage: PdfDocument.Page? = null
    private var y = BODY_TOP
    private var closed = false
    private var written = false
    var pageCount: Int = 0
        private set

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 41, 49)
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 8.5f
    }
    private val speakerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 56, 67)
        textSize = 10.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 10.5f
        typeface = Typeface.DEFAULT
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun append(turnNumber: Int, message: MessageEntity) {
        check(!closed && !written) { "PDF already finished." }
        require(turnNumber in range.first..range.last) { "Turn outside requested export range." }
        if (activePage == null) beginPage()
        if (y + 42f > BODY_BOTTOM) beginPage()
        val speaker = ChatPdfText.speaker(message)
        val stamp = dateFormat.format(Date(message.createdAt))
        drawParagraph("Turn " + turnNumber + " · " + speaker + " · " + stamp, speakerPaint, 4f)
        drawParagraph(ChatPdfText.visibleContent(message), bodyPaint, 14f)
    }

    private fun beginPage() {
        activePage?.let(document::finishPage)
        pageCount++
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageCount).create()
        val page = document.startPage(info)
        activePage = page
        page.canvas.drawColor(Color.WHITE)
        val safeTitle = chatTitle.trim().ifEmpty { "Untitled chat" }
            .replace('\n', ' ').replace('\r', ' ')
        val display = TextUtils.ellipsize(
            "Elsewhere · " + safeTitle, android.text.TextPaint(headerPaint),
            (PAGE_WIDTH - 2 * MARGIN).toFloat(), TextUtils.TruncateAt.END
        ).toString()
        page.canvas.drawText(display, MARGIN.toFloat(), 35f, headerPaint)
        page.canvas.drawText(
            "Selected turns " + range.first + "–" + range.last,
            MARGIN.toFloat(), 49f, metaPaint
        )
        page.canvas.drawLine(
            MARGIN.toFloat(), 57f, (PAGE_WIDTH - MARGIN).toFloat(), 57f, metaPaint
        )
        val pageLabel = "Page " + pageCount
        page.canvas.drawText(
            pageLabel, (PAGE_WIDTH - MARGIN).toFloat() - metaPaint.measureText(pageLabel),
            (PAGE_HEIGHT - 23).toFloat(), metaPaint
        )
        y = BODY_TOP
    }

    private fun drawParagraph(text: String, paint: TextPaint, trailingSpace: Float) {
        if (activePage == null) beginPage()
        // Avoid a huge one-line canvas operation; StaticLayout wraps and shapes the text.
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, BODY_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        var line = 0
        while (line < layout.lineCount) {
            val available = (BODY_BOTTOM - y).toInt()
            var stop = line
            while (stop < layout.lineCount &&
                layout.getLineBottom(stop) - layout.getLineTop(line) <= available
            ) {
                stop++
            }
            if (stop == line) {
                beginPage()
                continue
            }
            val canvas = requireNotNull(activePage).canvas
            val firstTop = layout.getLineTop(line)
            val height = layout.getLineTop(stop) - firstTop
            canvas.save()
            canvas.clipRect(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y + height)
            canvas.translate(MARGIN.toFloat(), y - firstTop)
            layout.draw(canvas)
            canvas.restore()
            y += height
            line = stop
            if (line < layout.lineCount) beginPage()
        }
        if (y + trailingSpace >= BODY_BOTTOM) beginPage()
        else y += trailingSpace
    }

    fun writeTo(destination: OutputStream) {
        check(!closed && !written) { "PDF already finished." }
        if (activePage == null) beginPage()
        activePage?.let(document::finishPage)
        activePage = null
        document.writeTo(destination)
        destination.flush()
        written = true
    }

    override fun close() {
        if (closed) return
        closed = true
        activePage?.let(document::finishPage)
        activePage = null
        document.close()
    }

    private companion object {
        const val PAGE_WIDTH = 595   // A4 at 72 dpi.
        const val PAGE_HEIGHT = 842
        const val MARGIN = 42
        const val BODY_WIDTH = PAGE_WIDTH - MARGIN * 2
        const val BODY_TOP = 74f
        const val BODY_BOTTOM = 801f
    }
}

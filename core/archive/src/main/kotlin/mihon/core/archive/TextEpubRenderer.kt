package mihon.core.archive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Turns a text-only epub into page images so the normal reader (swipe, zoom, page turn) can show it.
 * Each chapter starts on a new page; pages are laid out once, rendered on demand.
 */
class TextEpubRenderer(chapters: List<List<EpubReader.TextBlock>>) {

    private class Slice(val layout: StaticLayout, val top: Int, val bottom: Int)

    private val slices = mutableListOf<Slice>()

    val pageCount: Int get() = slices.size

    init {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            textSize = TEXT_SIZE
            typeface = Typeface.SERIF
        }
        val contentHeight = HEIGHT - 2 * MARGIN
        for (blocks in chapters) {
            val sb = SpannableStringBuilder()
            blocks.forEach { b ->
                val start = sb.length
                sb.append(b.text).append("\n\n")
                if (b.heading) {
                    sb.setSpan(RelativeSizeSpan(1.35f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, WIDTH - 2 * MARGIN)
                .setLineSpacing(0f, 1.25f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            var pageTopLine = 0
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(pageTopLine)
                if (layout.getLineBottom(line) - top > contentHeight && line > pageTopLine) {
                    slices += Slice(layout, top, layout.getLineTop(line))
                    pageTopLine = line
                }
            }
            if (pageTopLine < layout.lineCount) {
                slices += Slice(layout, layout.getLineTop(pageTopLine), layout.getLineBottom(layout.lineCount - 1))
            }
        }
    }

    /** Page `index` as a JPEG stream. */
    fun render(index: Int): InputStream {
        val s = slices[index]
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(250, 247, 240))
        canvas.save()
        canvas.translate(MARGIN.toFloat(), (MARGIN - s.top).toFloat())
        canvas.clipRect(0f, s.top.toFloat(), (WIDTH - 2 * MARGIN).toFloat(), s.bottom.toFloat())
        s.layout.draw(canvas)
        canvas.restore()
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        return ByteArrayInputStream(out.toByteArray())
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 1800
        const val MARGIN = 96
        const val TEXT_SIZE = 46f
    }
}

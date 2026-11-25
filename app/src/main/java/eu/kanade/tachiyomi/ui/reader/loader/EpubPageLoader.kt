package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.EpubReader
import mihon.core.archive.TextEpubRenderer

/**
 * Loader used to load a chapter from a .epub file.
 */
internal class EpubPageLoader(private val reader: EpubReader) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val images = reader.getImagesFromPages()
        val chapters = reader.getTextChapters()
        // Picture books (comics, manga) carry almost no text; a text book has far more characters than images.
        val chars = chapters.sumOf { c -> c.sumOf { it.text.length } }
        if (images.isNotEmpty() && chars < TEXT_CHARS_PER_IMAGE * images.size) {
            return images.mapIndexed { i, path ->
                ReaderPage(i).apply {
                    stream = { reader.getInputStream(path)!! }
                    status = Page.State.Ready
                }
            }
        }
        val text = TextEpubRenderer(chapters)
        check(text.pageCount > 0) { "This epub has no readable pages" }
        return List(text.pageCount) { i ->
            ReaderPage(i).apply {
                stream = { text.render(i) }
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }

    private companion object {
        const val TEXT_CHARS_PER_IMAGE = 1500
    }
}

package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Wrapper over ArchiveReader to load files in epub format.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    /**
     * Returns the path of the cover image the epub declares (the same one other readers show), or null.
     * Order: EPUB 3 `cover-image` property, EPUB 2 `<meta name="cover">`, then an image item named "cover".
     */
    fun getCoverImage(): String? {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val items = doc.select("manifest > item")
        fun isImage(type: String) = type.startsWith("image/")
        val item = items.firstOrNull { it.attr("properties").split(' ').contains("cover-image") && isImage(it.attr("media-type")) }
            ?: doc.select("metadata > meta[name=cover]").firstOrNull()?.attr("content")?.let { id ->
                items.firstOrNull { it.attr("id") == id && isImage(it.attr("media-type")) }
            }
            ?: items.firstOrNull {
                isImage(it.attr("media-type")) && (it.attr("id").equals("cover", true) || it.attr("href").substringAfterLast('/').startsWith("cover", true))
            }
        val href = item?.attr("href")?.takeIf { it.isNotEmpty() } ?: return null
        val path = resolveZipPath(getParentDirectory(ref), href)
        return path.takeIf { getInputStream(it) != null }
    }

    /** One block of readable text from a chapter. */
    data class TextBlock(val text: String, val heading: Boolean)

    /**
     * Returns the readable text of every spine page (one list of blocks per page, empty pages dropped).
     * Used when the epub is a text book without page images.
     */
    fun getTextChapters(): List<List<TextBlock>> {
        val ref = getPackageHref()
        val basePath = getParentDirectory(ref)
        return getPagesFromDocument(getPackageDocument(ref)).mapNotNull { page ->
            val entry = resolveZipPath(basePath, page)
            val doc = getInputStream(entry)?.use { Jsoup.parse(it, null, "") } ?: return@mapNotNull null
            val blocks = doc.select("h1,h2,h3,h4,h5,h6,p,li,pre")
                .mapNotNull { el ->
                    val t = el.text().trim()
                    if (t.isEmpty()) null else TextBlock(t, el.tagName().startsWith("h"))
                }
                .ifEmpty { doc.body()?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(TextBlock(it, false)) }.orEmpty() }
            blocks.takeIf { it.isNotEmpty() }
        }
    }

    /** Number of pages in the spine. */
    fun getSpineSize(): Int = getPagesFromDocument(getPackageDocument(getPackageHref())).size

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    /**
     * Returns all the pages from the epub.
     */
    private fun getPagesFromDocument(document: Document): List<String> {
        val pages = document.select("manifest > item")
            .filter { node -> node.attr("media-type") in PAGE_MEDIA_TYPES }
            .associateBy { it.attr("id") }

        val spine = document.select("spine > itemref").map { it.attr("idref") }
        return spine.mapNotNull { pages[it] }.map { it.attr("href") }
    }

    /**
     * Returns all the images contained in every page from the epub.
     */
    private fun getImagesFromPages(pages: List<String>, packageHref: String): List<String> {
        val result = mutableListOf<String>()
        val basePath = getParentDirectory(packageHref)
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, page)
            val document = getInputStream(entryPath)!!.use { Jsoup.parse(it, null, "") }
            val imageBasePath = getParentDirectory(entryPath)

            document.allElements.forEach {
                when (it.tagName()) {
                    "img" -> result.add(resolveZipPath(imageBasePath, it.attr("src")))
                    "image" -> result.add(resolveZipPath(imageBasePath, it.attr("xlink:href")))
                }
            }
        }

        return result
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }

    private companion object {
        // Some generators label their pages text/html instead of application/xhtml+xml.
        val PAGE_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")
    }
}

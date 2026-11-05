package eu.kanade.tachiyomi.extension.vi.archiveorg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Vietnamese books and old comic scans from archive.org (language `vie`) that have page images.
 *
 * - An entry is an archive.org item; a chapter is one volume, i.e. one `*_jp2.zip` in the item
 *   (a single-volume item has one chapter). That is how a series stored as one item with dozens
 *   of volumes, like Dũng sĩ Hesman, becomes a series with a chapter each.
 * - Pages are JPEGs from archive.org's BookReader endpoint, which converts the stored JP2 pages;
 *   the page count is read from the volume's `_scandata.xml`.
 */
class ArchiveOrgVi : HttpSource() {

    override val name = "Internet Archive (Tiếng Việt)"
    override val baseUrl = "https://archive.org"
    override val lang = "vi"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private companion object {
        const val PAGE_SIZE = 30
        const val BASE_QUERY = "language:(vie OR Vietnamese) AND format:\"Single Page Processed JP2 ZIP\""
        const val ZIP_SUFFIX = "_jp2.zip"
        const val SCALE = 2
    }

    // Listing: advancedsearch, sorted by downloads (popular) or upload date (latest)

    private fun searchRequest(page: Int, query: String?, sort: String): Request {
        val q = if (query.isNullOrBlank()) BASE_QUERY else "($query) AND $BASE_QUERY"
        val url = "$baseUrl/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "creator")
            .addQueryParameter("sort[]", sort)
            .addQueryParameter("rows", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("output", "json")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaRequest(page: Int) = searchRequest(page, null, "downloads desc")

    override fun latestUpdatesRequest(page: Int) = searchRequest(page, null, "publicdate desc")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        searchRequest(page, query, "downloads desc")

    override fun popularMangaParse(response: Response) = parseListing(response)

    override fun latestUpdatesParse(response: Response) = parseListing(response)

    override fun searchMangaParse(response: Response) = parseListing(response)

    private fun parseListing(response: Response): MangasPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject["response"]!!.jsonObject
        val docs = root["docs"]?.jsonArray.orEmpty()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val found = root["numFound"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val items = docs.map { doc ->
            val o = doc.jsonObject
            val id = o["identifier"]!!.jsonPrimitive.content
            SManga.create().apply {
                url = "/details/$id"
                title = o.text("title") ?: id
                author = o.text("creator")
                thumbnail_url = "$baseUrl/services/img/$id"
            }
        }
        return MangasPage(items, page * PAGE_SIZE < found)
    }

    // Details and chapters both come from the item's metadata

    private fun itemId(manga: SManga) = manga.url.substringAfterLast('/')

    override fun mangaDetailsRequest(manga: SManga) = GET("$baseUrl/metadata/${itemId(manga)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val meta = json.parseToJsonElement(response.body.string()).jsonObject["metadata"]!!.jsonObject
        return SManga.create().apply {
            title = meta.text("title").orEmpty()
            author = meta.text("creator")
            genre = meta["subject"].texts().joinToString().ifBlank { null }
            description = meta.text("description")?.let { Jsoup.parse(it).text() }
            status = SManga.COMPLETED
            thumbnail_url = "$baseUrl/services/img/${meta.text("identifier")}"
        }
    }

    override fun chapterListRequest(manga: SManga) = GET("$baseUrl/metadata/${itemId(manga)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val meta = root["metadata"]!!.jsonObject
        val id = meta.text("identifier").orEmpty()
        val title = meta.text("title") ?: id
        val uploaded = meta.text("publicdate")?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
        val zips = root["files"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.text("name") }
            .filter { it.endsWith(ZIP_SUFFIX) && !it.contains('/') }
            .sorted()
        return zips.map { zip ->
            val volume = zip.removeSuffix(ZIP_SUFFIX)
            SChapter.create().apply {
                url = "/details/$id?volume=${URLEncoder.encode(volume, "UTF-8")}"
                name = if (zips.size == 1) title else volume
                date_upload = uploaded
            }
        }.reversed()
    }

    // Pages: one BookReader JPEG per scanned leaf

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfter("/details/").substringBefore('?')
        val volume = URLDecoder.decode(chapter.url.substringAfter("volume=", ""), "UTF-8")
        // The volume rides along as a query parameter, which the metadata endpoint ignores, so the parser can read it.
        val url = "$baseUrl/metadata/$id".toHttpUrl().newBuilder().addQueryParameter("volume", volume).build()
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val id = response.request.url.pathSegments.last()
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val server = root["d1"]?.jsonPrimitive?.content ?: root["server"]!!.jsonPrimitive.content
        val dir = root["dir"]!!.jsonPrimitive.content
        val volume = response.request.url.queryParameter("volume")?.takeIf { it.isNotBlank() } ?: error("Volume missing")
        val scan = client.newCall(GET("$baseUrl/download/$id/${URLEncoder.encode(volume, "UTF-8").replace("+", "%20")}_scandata.xml", headers))
            .execute().use { it.body.string() }
        val count = Regex("<page ").findAll(scan).count().takeIf { it > 0 } ?: error("No pages found for $volume")
        val vol = URLEncoder.encode(volume, "UTF-8").replace("+", "%20")
        return (0 until count).map { leaf ->
            val file = "%s_%04d.jp2".format(vol, leaf)
            val url = "https://$server/BookReader/BookReaderImages.php?zip=$dir/$vol$ZIP_SUFFIX&file=${vol}_jp2/$file&id=$id&scale=$SCALE&rotate=0"
            Page(leaf, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun JsonObject.text(key: String): String? = this[key].texts().firstOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonElement?.texts(): List<String> = when (this) {
        null -> emptyList()
        is JsonArray -> map { it.jsonPrimitive.content }
        is JsonPrimitive -> listOf(content)
        else -> emptyList()
    }
}

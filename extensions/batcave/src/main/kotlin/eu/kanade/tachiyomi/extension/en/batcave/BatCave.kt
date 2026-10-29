package eu.kanade.tachiyomi.extension.en.batcave

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * batcave.biz is a DataLife Engine comics site behind Cloudflare. The host app's default client
 * already solves the Cloudflare challenge, so no extra interceptor is needed here.
 *
 * - Listing/search: `a.poster` cards, "load more" pagination at `/page/N/`.
 * - Detail and chapters: a `window.__DATA__ = {...}` script on the comic page (`chapters[]`).
 * - Pages: the same `window.__DATA__` on `/reader/<comicId>/<chapterId>` (`images[]`).
 */
class BatCave : HttpSource() {

    override val name = "BatCave"
    override val baseUrl = "https://batcave.biz"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val DATA_ASSIGNMENT = "window.__DATA__ ="
        const val SITE_HOST = "batcave.biz"
        const val STUB_LIMIT = 1500
    }

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    /**
     * The site answers requests that carry no session cookie with a 404 or a ~1 KB stub page.
     * Loading the home page sets the cookies, so on such a response visit it once and retry.
     */
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url.host != SITE_HOST || !response.looksLikeMissingSession()) {
                return@addInterceptor response
            }
            response.close()
            chain.proceed(GET("$baseUrl/", headers)).close()
            val retried = chain.proceed(request)
            if (retried.looksLikeMissingSession()) {
                // The site's browser check can only be passed in a real browser, so ask for that.
                retried.close()
                throw IOException("BatCave needs a browser check: open it in WebView, wait for the page, then retry")
            }
            retried
        }
        .build()

    private fun Response.looksLikeMissingSession(): Boolean {
        if (code == 404) return true
        val isHtml = header("Content-Type")?.contains("text/html") == true
        return code == 200 && isHtml && peekBody(STUB_LIMIT + 1L).string().length < STUB_LIMIT
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comix/${pagePath(page)}", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response.asDocument())

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/new-comics.html${pagePath(page)}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response.asDocument())

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)
        if (page == 1) {
            // The site's own search box POSTs here and gets a 302 to /search/<query>.
            val form = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("story", query)
                .build()
            return POST("$baseUrl/comix/", headers, form)
        }
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addPathSegment(query)
            .build()
        return GET("$url${pagePath(page)}", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response.asDocument())

    private fun pagePath(page: Int) = if (page > 1) "page/$page/" else ""

    private fun parseListing(document: Document): MangasPage {
        val seen = HashSet<String>()
        val mangas = mutableListOf<SManga>()

        fun add(manga: SManga) {
            if (manga.title.isNotEmpty() && seen.add(manga.url)) mangas += manga
        }

        // /comix/ and search results
        document.select("div.readed").forEach { card ->
            val link = card.selectFirst("h2.readed__title a") ?: return@forEach
            add(
                SManga.create().apply {
                    url = link.absUrl("href").removePrefix(baseUrl)
                    title = link.text()
                    thumbnail_url = card.selectFirst("img")?.imageUrl()
                },
            )
        }
        // /new-comics.html
        document.select("li.nc-card").forEach { card ->
            val link = card.selectFirst("a.nc-card__title") ?: return@forEach
            add(
                SManga.create().apply {
                    url = link.absUrl("href").removePrefix(baseUrl)
                    title = link.text()
                    thumbnail_url = card.selectFirst("img")?.imageUrl()
                },
            )
        }
        // Home page carousels
        document.select("a.poster[href\$=.html]").forEach { card ->
            add(
                SManga.create().apply {
                    url = card.absUrl("href").removePrefix(baseUrl)
                    title = card.selectFirst(".poster__title")?.text().orEmpty()
                    thumbnail_url = card.selectFirst("img")?.imageUrl()
                },
            )
        }

        // Pagination is a row of numbered links; there is a next page when one points past the current.
        val hasNext = document.selectFirst("a[href*=/page/]:contains(Next), .pagination a[href*=/page/]") != null ||
            document.select("a[href*=/page/]").any { it.text().trim().toIntOrNull() != null }
        return MangasPage(mangas, hasNext && document.selectFirst("li.nc-card") == null)
    }

    private fun Element.imageUrl(): String = absUrl("data-src").ifEmpty { absUrl("src") }

    // Details
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".page__poster img")?.absUrl("src")
            description = document.selectFirst(".page__text")?.text()
            author = document.select("a[href*=/ComicList/w=]").joinToString { it.text() }.ifEmpty { null }
            genre = document.select("a[href*=/ComicList/g=]").joinToString { it.text() }.ifEmpty { null }
            status = SManga.UNKNOWN
        }
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.asDocument().dataObject()
        val comicId = data["news_id"]!!.jsonPrimitive.content
        return data["chapters"]!!.jsonArray
            .map { it.jsonObject }
            .filterNot { it["broken"]?.jsonPrimitive?.content == "true" }
            .map { ch ->
                SChapter.create().apply {
                    url = "/reader/$comicId/${ch["id"]!!.jsonPrimitive.content}"
                    name = ch["title"]!!.jsonPrimitive.content
                    chapter_number = ch["posi"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                    date_upload = ch["date"]?.jsonPrimitive?.content
                        ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    // Pages
    override fun pageListParse(response: Response): List<Page> {
        val data = response.asDocument().dataObject()
        // The site's own script trims `images` to `pages`; do the same so no stray entries show up.
        val count = data["pages"]?.jsonPrimitive?.content?.toIntOrNull()
        val images = data["images"]!!.jsonArray.let { if (count != null) it.take(count) else it }
        return images.mapIndexed { i, el -> Page(i, imageUrl = el.jsonPrimitive.content) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // Helpers
    private fun Response.asDocument(): Document = org.jsoup.Jsoup.parse(body.string(), request.url.toString())

    /** Extracts the JSON assigned to `window.__DATA__` from the page's inline scripts. */
    private fun Document.dataObject(): JsonObject {
        val script = select("script").map { it.data() }.firstOrNull { DATA_ASSIGNMENT in it }
            ?: error("Could not find page data. Open the site in WebView to clear Cloudflare, then retry.")
        val start = script.indexOf('{', script.indexOf(DATA_ASSIGNMENT))
        return json.parseToJsonElement(extractBalancedObject(script, start)).jsonObject
    }

    /** Takes the `{...}` starting at [start], honouring strings so braces inside text don't count. */
    private fun extractBalancedObject(text: String, start: Int): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        error("Unterminated page data")
    }
}

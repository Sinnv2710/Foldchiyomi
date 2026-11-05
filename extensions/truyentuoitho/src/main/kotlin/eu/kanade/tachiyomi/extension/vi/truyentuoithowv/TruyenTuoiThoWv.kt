package eu.kanade.tachiyomi.extension.vi.truyentuoithowv

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * truyentuoitho.com is a WP-Manga (Madara) site of old Vietnamese comics. Listing, details and the
 * chapter list are plain Madara. A chapter's images are not in the HTML: a script builds them in the
 * page, and the encoding changes now and then (the Keiyoushi extension lists one page per chapter
 * since the last change). So pages are read from a real WebView after the page has built them.
 */
class TruyenTuoiThoWv : HttpSource() {

    override val name = "TruyenTuoiTho (WebView)"
    override val baseUrl = "https://truyentuoitho.com"
    override val lang = "vi"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

    private companion object {
        const val WEBVIEW_TIMEOUT_SECONDS = 45L
        const val POLL_MS = 500L

        // Equal polls in a row before a count is trusted: the page builds its images in waves a few seconds apart.
        const val STABLE_POLLS = 8

        // Pages only: the reading area also holds product-slider ads (`mtp-slide-img`) and emoji. Placeholders
        // (no URL yet) are counted, so a lazy page is waited for. Each poll also scrolls on, which is what makes
        // the page fill in the images that are still empty.
        const val IMAGES_JS =
            "(function(){var all=[].slice.call(document.querySelectorAll('.reading-content img'))" +
                ".filter(function(i){return !i.classList.contains('mtp-slide-img')&&!i.classList.contains('emoji')&&!i.closest('.mtp-slider,.mtp-slide,[class*=mtp-]')});" +
                "var urls=all.map(function(i){return i.getAttribute('data-src')||i.getAttribute('data-lazy-src')||i.currentSrc||i.src||''});" +
                "window.scrollBy(0,window.innerHeight*2);" +
                "return JSON.stringify({total:all.length,urls:urls})})()"
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listing: /manga/page/N/?m_orderby=views|latest

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga/${pagePath(page)}?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response.asDocument())

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga/${pagePath(page)}?m_orderby=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response.asDocument())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/${pagePath(page)}".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response.asDocument())

    private fun pagePath(page: Int) = if (page > 1) "page/$page/" else ""

    private fun parseListing(document: Document): MangasPage {
        val items = document.select("div.page-item-detail, div.c-tabs-item__content").mapNotNull { card ->
            val link = card.selectFirst("h3 a, h5 a, .post-title a") ?: card.selectFirst("a[title]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text().ifBlank { link.attr("title") }
                thumbnail_url = card.selectFirst("img")?.imageUrl()
            }
        }.distinctBy { it.url }
        return MangasPage(items, document.selectFirst("a.nextpostslink, a.next.page-numbers") != null)
    }

    private fun Element.imageUrl(): String? = listOf("data-src", "data-lazy-src", "src")
        .map { absUrl(it) }
        .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asDocument()
        return SManga.create().apply {
            title = doc.selectFirst(".post-title h1, .post-title h3")?.ownText()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst(".summary_image img")?.imageUrl()
            author = doc.select(".author-content a").joinToString { it.text() }.ifBlank { null }
            genre = doc.select(".genres-content a").joinToString { it.text() }.ifBlank { null }
            description = doc.selectFirst(".description-summary .summary__content, .summary__content")?.text()?.ifBlank { null }
            val state = doc.select(".post-status .summary-content").text().lowercase(Locale.ROOT)
            status = when {
                "hoàn thành" in state || "hoan thanh" in state || "completed" in state -> SManga.COMPLETED
                "đang" in state || "ongoing" in state -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters: Madara posts to <series>/ajax/chapters/

    override fun chapterListRequest(manga: SManga): Request {
        val path = manga.url.trimEnd('/') + "/ajax/chapters/"
        return POST(
            baseUrl + path,
            headers.newBuilder().add("X-Requested-With", "XMLHttpRequest").build(),
            FormBody.Builder().build(),
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        response.asDocument().select("li.wp-manga-chapter").map { li ->
            val link = li.selectFirst("a")!!
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text().trim()
                date_upload = li.selectFirst("span.chapter-release-date i")?.text()
                    ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
            }
        }

    // Pages

    override fun pageListParse(response: Response): List<Page> {
        val urls = loadImagesInWebView(response.request.url.toString())
            ?: throw IOException("TruyenTuoiTho did not show the pages: open the chapter in WebView, then retry")
        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    /** Opens [url] in a WebView and returns the chapter's image URLs once their count has stopped growing. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun loadImagesInWebView(url: String): List<String>? {
        val done = CountDownLatch(1)
        var result: List<String>? = null
        val main = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var last = ""
        var stable = 0
        var best: List<String> = emptyList()

        fun poll() {
            val view = webView ?: return
            view.evaluateJavascript(IMAGES_JS) { raw ->
                val all = runCatching {
                    json.parseToJsonElement(json.decodeFromString<String>(raw)).jsonObject["urls"]!!
                        .jsonArray.map { it.jsonPrimitive.content }
                }.getOrNull().orEmpty()
                val loaded = all.filter { it.startsWith("http") }
                if (loaded.size >= best.size) best = loaded
                // Done when every image has its URL and the total has stopped growing for a few seconds.
                val key = "${all.size}/${loaded.size}"
                stable = if (key == last) stable + 1 else 0
                last = key
                if (all.isNotEmpty() && loaded.size == all.size && stable >= STABLE_POLLS) {
                    result = loaded
                    done.countDown()
                } else {
                    main.postDelayed({ poll() }, POLL_MS)
                }
            }
        }

        main.post {
            val view = WebView(Injekt.get<Application>())
            webView = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            // Detached views have no size, and a page that loads images when they come into view then loads none.
            view.layout(0, 0, 1080, 1920)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = poll()
            }
            view.loadUrl(url)
        }
        done.await(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // On a timeout, the pages that did load are better than an error.
        if (result == null && best.isNotEmpty()) result = best
        main.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        return result
    }

    private fun Response.asDocument(): Document = Jsoup.parse(body.string(), request.url.toString())
}

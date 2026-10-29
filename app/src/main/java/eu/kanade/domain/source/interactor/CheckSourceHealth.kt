package eu.kanade.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.source.service.SourceManager
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

/**
 * Loads the first page of a source's popular list once and says whether the source answered, was
 * stopped by Cloudflare or another bot check, or could not be reached at all.
 */
@Inject
class CheckSourceHealth(
    private val sourceManager: SourceManager,
) {

    suspend fun await(sourceId: Long): SourceHealth = withContext(Dispatchers.IO) {
        val source = sourceManager.get(sourceId) as? CatalogueSource ?: return@withContext SourceHealth.Down
        try {
            val page = withTimeout(TIMEOUT) { source.getPopularManga(1) }
            if (page.mangas.isNotEmpty()) SourceHealth.Ok else SourceHealth.Blocked
        } catch (e: TimeoutCancellationException) {
            SourceHealth.Down
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            classify(e)
        }
    }

    internal fun classify(e: Throwable): SourceHealth {
        val chain = generateSequence(e) { it.cause }.toList()
        val http = chain.filterIsInstance<HttpException>().firstOrNull()
        return when {
            http != null && http.code in CLOUDFLARE_CODES -> SourceHealth.Cloudflare
            chain.any { it.message?.contains("cloudflare", ignoreCase = true) == true } -> SourceHealth.Cloudflare
            http != null && http.code >= 500 -> SourceHealth.Down
            chain.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException } ->
                SourceHealth.Down
            // Anything else (404 without a session, an empty stub, a page that cannot be parsed) means the
            // site answered but not with what the source expects.
            else -> SourceHealth.Blocked
        }
    }

    private companion object {
        val TIMEOUT = 25.seconds
        val CLOUDFLARE_CODES = setOf(403, 503)
    }
}

enum class SourceHealth { Ok, Cloudflare, Blocked, Down }

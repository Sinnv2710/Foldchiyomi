package tachiyomi.presentation.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import coil3.annotation.ExperimentalCoilApi
import coil3.asDrawable
import coil3.executeBlocking
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.transform.RoundedCornersTransformation
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import mihon.core.metro.metroGraph
import mihon.presentation.widget.di.PresentationWidgetGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.presentation.widget.components.CoverHeight
import tachiyomi.presentation.widget.components.CoverWidth
import tachiyomi.presentation.widget.components.LockedWidget
import tachiyomi.presentation.widget.components.UpdatesWidget
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.util.appWidgetBackgroundRadius
import tachiyomi.presentation.widget.util.calculateRowAndColumnCount
import kotlin.time.Clock
import kotlin.time.Instant

@HasMemberInjections
abstract class BaseUpdatesGridGlanceWidget : GlanceAppWidget() {

    @Inject private lateinit var getUpdates: GetUpdates

    @Inject private lateinit var getLibraryManga: GetLibraryManga

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var preferences: SecurityPreferences

    /** What the grid shows: recent chapter updates (the original widget), the library, or what to read on. */
    protected open val mode: WidgetMode = WidgetMode.UPDATES

    override val sizeMode = SizeMode.Exact

    abstract val foreground: ColorProvider
    abstract val background: ImageProvider
    abstract val topPadding: Dp
    abstract val bottomPadding: Dp

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.metroGraph<PresentationWidgetGraph>().inject(this)
        val locked = preferences.useAuthenticator.get()
        val containerModifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .appWidgetBackground()
            .padding(top = topPadding, bottom = bottomPadding)
            .appWidgetBackgroundRadius()

        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(javaClass)
        val (rowCount, columnCount) = ids
            .flatMap { manager.getAppWidgetSizes(it) }
            .maxBy { it.height.value * it.width.value }
            .calculateRowAndColumnCount(topPadding, bottomPadding)

        provideContent {
            // If app lock enabled, don't do anything
            if (locked) {
                LockedWidget(
                    foreground = foreground,
                    modifier = containerModifier,
                )
                return@provideContent
            }

            val flow = remember {
                covers().map { rawData -> rawData.prepareData(context, rowCount, columnCount) }
            }
            val data by flow.collectAsState(initial = null)
            UpdatesWidget(
                data = data,
                contentColor = foreground,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
                emptyText = when (mode) {
                    WidgetMode.UPDATES -> MR.strings.information_no_recent
                    WidgetMode.LIBRARY -> MR.strings.information_empty_library
                    WidgetMode.CONTINUE -> MR.strings.information_widget_nothing_to_continue
                },
                modifier = containerModifier,
            )
        }
    }

    private fun covers(): Flow<List<WidgetCover>> = when (mode) {
        WidgetMode.UPDATES -> getUpdates.subscribe(false, DateLimit.toEpochMilliseconds()).map { updates ->
            updates.distinctBy { it.mangaId }.map {
                WidgetCover(it.mangaId, it.sourceId, it.coverData.url, it.coverData.lastModified)
            }
        }
        WidgetMode.LIBRARY, WidgetMode.CONTINUE -> getLibraryManga.subscribe().map { library ->
            // Entries that sit only in hidden categories stay out of the widget as well.
            val hidden = libraryPreferences.hiddenCategories.get()
            library
                .filter { entry -> entry.categories.any { it.toString() !in hidden } }
                .filter { mode == WidgetMode.LIBRARY || (it.hasStarted && it.unreadCount > 0) }
                .sortedWith(compareByDescending<LibraryManga> { it.lastRead }.thenBy { it.manga.title.lowercase() })
                .map {
                    WidgetCover(it.manga.id, it.manga.source, it.manga.thumbnailUrl, it.manga.coverLastModified)
                }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun List<WidgetCover>.prepareData(
        context: Context,
        rowCount: Int,
        columnCount: Int,
    ): List<Pair<Long, Bitmap?>> {
        // Resize to cover size
        val widthPx = CoverWidth.value.toInt().dpToPx
        val heightPx = CoverHeight.value.toInt().dpToPx
        val roundPx = context.resources.getDimension(R.dimen.appwidget_inner_radius)
        return withIOContext {
            this@prepareData
                .take(rowCount * columnCount)
                .map { updatesView ->
                    val request = ImageRequest.Builder(context)
                        .data(
                            MangaCover(
                                mangaId = updatesView.mangaId,
                                sourceId = updatesView.sourceId,
                                isMangaFavorite = true,
                                url = updatesView.url,
                                lastModified = updatesView.lastModified,
                            ),
                        )
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .precision(Precision.EXACT)
                        .size(widthPx, heightPx)
                        .scale(Scale.FILL)
                        .let {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                it.transformations(RoundedCornersTransformation(roundPx))
                            } else {
                                it // Handled by system
                            }
                        }
                        .build()
                    val bitmap = context.imageLoader.executeBlocking(request)
                        .image
                        ?.asDrawable(context.resources)
                        ?.toBitmap()
                    Pair(updatesView.mangaId, bitmap)
                }
        }
    }

    /** One cover to draw, whichever list it came from. */
    data class WidgetCover(val mangaId: Long, val sourceId: Long, val url: String?, val lastModified: Long)

    enum class WidgetMode { UPDATES, LIBRARY, CONTINUE }

    companion object {
        val DateLimit: Instant
            get() = Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())
    }
}

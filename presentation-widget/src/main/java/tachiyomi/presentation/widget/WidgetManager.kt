package tachiyomi.presentation.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleCoroutineScope
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.updates.interactor.GetUpdates

@Inject
class WidgetManager(
    private val getUpdates: GetUpdates,
    private val getLibraryManga: GetLibraryManga,
    private val securityPreferences: SecurityPreferences,
) {

    context(context: Context)
    fun init(scope: LifecycleCoroutineScope) {
        combine(
            getUpdates.subscribe(read = false, after = BaseUpdatesGridGlanceWidget.DateLimit.toEpochMilliseconds()),
            securityPreferences.useAuthenticator.changes(),
            transform = { a, b -> a to b },
        )
            .distinctUntilChanged { old, new ->
                old.second == new.second &&
                    old.first.map { it.chapterId }.toSet() == new.first.map { it.chapterId }.toSet()
            }
            .onEach {
                try {
                    UpdatesGridGlanceWidget().updateAll(context)
                    UpdatesGridCoverScreenGlanceWidget().updateAll(context)
                } catch (e: Exception) {
                    this.logcat(LogPriority.ERROR, e) { "Failed to update widget" }
                }
            }
            .flowOn(Dispatchers.Default)
            .launchIn(scope)

        // The library and continue-reading widgets change with the library (entries added, reading progress),
        // not with new chapters, so they listen to it on their own.
        getLibraryManga.subscribe()
            .map { library -> library.map { Triple(it.manga.id, it.lastRead, it.readCount) }.toSet() }
            .distinctUntilChanged()
            .onEach {
                try {
                    LibraryGridGlanceWidget().updateAll(context)
                    ContinueReadingGridGlanceWidget().updateAll(context)
                } catch (e: Exception) {
                    this.logcat(LogPriority.ERROR, e) { "Failed to update library widgets" }
                }
            }
            .flowOn(Dispatchers.Default)
            .launchIn(scope)
    }
}

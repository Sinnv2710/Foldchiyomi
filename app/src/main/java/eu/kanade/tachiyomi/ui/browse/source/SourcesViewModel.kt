package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.interactor.CheckSourceHealth
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.SourceHealth
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.presentation.browse.SourceUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.isLocal
import java.util.TreeMap
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SourcesViewModel(
    private val getEnabledSources: GetEnabledSources,
    private val toggleSource: ToggleSource,
    private val toggleSourcePin: ToggleSourcePin,
    private val checkSourceHealth: CheckSourceHealth,
) : ViewModel() {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    private val dialog = MutableStateFlow<Dialog?>(null)
    private val health = MutableStateFlow<Map<Long, SourceHealth>>(emptyMap())
    private val checking = MutableStateFlow<Set<Long>>(emptySet())

    private val enabledSources = getEnabledSources.subscribe()
        .catch {
            logcat(LogPriority.ERROR, it)
            _events.send(Event.FailedFetchingSources)
        }
        .map(::toSourceUiModels)

    val state: StateFlow<State> = combine(
        enabledSources,
        dialog,
        health,
        checking,
    ) { items, dialog, health, checking ->
        State(dialog = dialog, isLoading = false, items = items, health = health, checking = checking)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    private fun toSourceUiModels(sources: List<Source>): List<SourceUiModel> {
        val map = TreeMap<String, MutableList<Source>> { d1, d2 ->
            // Sources without a lang defined will be placed at the end
            when {
                d1 == LAST_USED_KEY && d2 != LAST_USED_KEY -> -1
                d2 == LAST_USED_KEY && d1 != LAST_USED_KEY -> 1
                d1 == PINNED_KEY && d2 != PINNED_KEY -> -1
                d2 == PINNED_KEY && d1 != PINNED_KEY -> 1
                d1 == "" && d2 != "" -> 1
                d2 == "" && d1 != "" -> -1
                else -> d1.compareTo(d2)
            }
        }
        val byLang = sources.groupByTo(map) {
            when {
                it.isUsedLast -> LAST_USED_KEY
                Pin.Actual in it.pin -> PINNED_KEY
                else -> it.lang
            }
        }

        return byLang.flatMap {
            listOf(
                SourceUiModel.Header(it.key),
                *it.value.map { source ->
                    SourceUiModel.Item(source)
                }.toTypedArray(),
            )
        }
    }

    fun toggleSource(source: Source) {
        toggleSource.await(source)
    }

    fun togglePin(source: Source) {
        toggleSourcePin.await(source)
    }

    fun showSourceDialog(source: Source) {
        dialog.update { Dialog(source) }
    }

    fun closeDialog() {
        dialog.update { null }
    }

    /** Checks one source now. */
    fun checkSource(source: Source) {
        if (source.id in checking.value) return
        viewModelScope.launch { runCheck(source) }
    }

    /** Checks every enabled online source one after the other, so the sites see a trickle, not a burst. */
    fun checkAllSources() {
        if (checking.value.isNotEmpty()) return
        val sources = state.value.items
            .filterIsInstance<SourceUiModel.Item>()
            .map { it.source }
            .filterNot { it.isLocal() }
            .distinctBy { it.id }
        if (sources.isEmpty()) return
        checking.update { sources.map { it.id }.toSet() }
        viewModelScope.launch {
            sources.forEachIndexed { index, source ->
                if (index > 0) delay(CHECK_GAP_MS)
                runCheck(source)
            }
        }
    }

    private suspend fun runCheck(source: Source) {
        checking.update { it + source.id }
        val result = checkSourceHealth.await(source.id)
        health.update { it + (source.id to result) }
        checking.update { it - source.id }
    }

    sealed interface Event {
        data object FailedFetchingSources : Event
    }

    data class Dialog(val source: Source)

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val items: List<SourceUiModel> = listOf(),
        val health: Map<Long, SourceHealth> = emptyMap(),
        val checking: Set<Long> = emptySet(),
    ) {
        val isEmpty = items.isEmpty()
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
        private const val CHECK_GAP_MS = 500L
    }
}

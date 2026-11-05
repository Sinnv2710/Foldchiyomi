package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.rounded.TravelExplore
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.sourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<SourcesViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = MaterialSymbols.Rounded.TravelExplore,
                onClick = { navigator.push(GlobalSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_check_sources),
                icon = MaterialSymbols.Rounded.Sync,
                onClick = viewModel::checkAllSources,
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = MaterialSymbols.Rounded.FilterList,
                onClick = { navigator.push(SourcesFilterScreen()) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    navigator.push(BrowseSourceScreen(source.id, listing.query))
                },
                onClickPin = viewModel::togglePin,
                onLongClickItem = viewModel::showSourceDialog,
            )

            state.dialog?.let { dialog ->
                val source = dialog.source
                SourceOptionsDialog(
                    source = source,
                    onClickPin = {
                        viewModel.togglePin(source)
                        viewModel.closeDialog()
                    },
                    onClickCheck = {
                        viewModel.checkSource(source)
                        viewModel.closeDialog()
                    },
                    onClickDisable = {
                        viewModel.toggleSource(source)
                        viewModel.closeDialog()
                    },
                    onDismiss = viewModel::closeDialog,
                )
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            val allWorkingString = stringResource(MR.strings.sources_check_all_working)
            val brokenString = stringResource(MR.strings.sources_check_some_broken)
            val disableString = stringResource(MR.strings.action_disable_broken_sources)
            val migrateString = stringResource(MR.strings.action_migrate_broken_source)
            var brokenResult by remember { mutableStateOf<SourcesViewModel.Event.CheckFinished?>(null) }
            brokenResult?.let { result ->
                // The broken source with the most entries in the library is the one worth migrating first.
                val target = result.broken
                    .filter { (result.libraryEntries[it.id] ?: 0) > 0 }
                    .maxByOrNull { result.libraryEntries[it.id] ?: 0 }
                AlertDialog(
                    onDismissRequest = { brokenResult = null },
                    title = { Text(brokenString.format(result.broken.size, result.total)) },
                    text = {
                        Text(
                            result.broken.joinToString("\n") { source ->
                                val count = result.libraryEntries[source.id] ?: 0
                                if (count > 0) "${source.name} ($count)" else source.name
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.disableSources(result.broken)
                                brokenResult = null
                            },
                        ) { Text(disableString) }
                    },
                    dismissButton = {
                        if (target != null) {
                            TextButton(
                                onClick = {
                                    brokenResult = null
                                    navigator.push(MigrateMangaScreen(target.id))
                                },
                            ) { Text(migrateString.format(target.name)) }
                        } else {
                            TextButton(onClick = { brokenResult = null }) { Text(stringResource(MR.strings.action_cancel)) }
                        }
                    },
                )
            }
            LaunchedEffect(Unit) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        SourcesViewModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                                        is SourcesViewModel.Event.CheckFinished -> launch {
                            if (event.broken.isEmpty()) {
                                snackbarHostState.showSnackbar(
                                    message = allWorkingString.format(event.total),
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Long,
                                )
                            } else {
                                brokenResult = event
                            }
                        }
                    }
                }
            }
        },
    )
}

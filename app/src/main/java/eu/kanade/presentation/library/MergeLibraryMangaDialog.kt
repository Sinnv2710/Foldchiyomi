package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Asks which of the selected [manga] to keep; the rest are folded into it.
 */
@Composable
fun MergeLibraryMangaDialog(
    manga: List<Manga>,
    sourceName: (Manga) -> String,
    onDismissRequest: () -> Unit,
    onConfirm: (keep: Manga) -> Unit,
) {
    var keep by remember { mutableStateOf(manga.first()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm(keep)
                },
            ) {
                Text(text = stringResource(MR.strings.action_merge_duplicates))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_merge_duplicates)) },
        text = {
            Column {
                Text(
                    text = stringResource(MR.strings.merge_duplicates_summary),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier
                        .padding(top = MaterialTheme.padding.small)
                        .selectableGroup(),
                ) {
                    manga.forEach { item ->
                        Row(
                            modifier = Modifier
                                .selectable(
                                    selected = item.id == keep.id,
                                    onClick = { keep = item },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = item.id == keep.id, onClick = null)
                            Column(modifier = Modifier.padding(start = MaterialTheme.padding.small)) {
                                Text(text = item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = sourceName(item),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

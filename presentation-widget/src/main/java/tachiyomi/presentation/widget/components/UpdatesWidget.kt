package tachiyomi.presentation.widget.components

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.presentation.widget.util.calculateRowAndColumnCount
import tachiyomi.presentation.widget.util.fitCovers

@Composable
fun UpdatesWidget(
    data: List<Pair<Long, Bitmap?>>?,
    contentColor: ColorProvider,
    topPadding: Dp,
    bottomPadding: Dp,
    emptyText: dev.icerock.moko.resources.StringResource = MR.strings.information_no_recent,
    scaled: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        if (data == null) {
            CircularProgressIndicator(color = contentColor)
        } else if (data.isEmpty()) {
            Text(
                text = LocalContext.current.stringResource(emptyText),
                style = TextStyle(color = contentColor),
            )
        } else {
            // The scaled grid (Library, Continue reading) sizes the covers to the widget; the updates widget keeps its own.
            val grid = if (scaled) LocalSize.current.fitCovers(data.size, topPadding, bottomPadding) else null
            val (rowCount, columnCount) = grid?.let { it.rows to it.columns }
                ?: LocalSize.current.calculateRowAndColumnCount(topPadding, bottomPadding)
            if (grid != null && (grid.columns * grid.rows < data.size || grid.coverWidth.value < MIN_STATIC_COVER_WIDTH)) {
                // More entries than fit at a readable size: scroll. The covers keep a steady size across the width instead of shrinking.
                val columns = (LocalSize.current.width.value / SCROLL_COVER_TARGET_WIDTH).toInt().coerceIn(1, 8)
                val coverWidth = ((LocalSize.current.width.value - columns * 6f) / columns).coerceAtMost(140f).dp
                LazyVerticalGrid(
                    gridCells = GridCells.Fixed(columns),
                    modifier = GlanceModifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(data.size) { index ->
                        val (mangaId, cover) = data[index]
                        Box(
                            modifier = GlanceModifier.padding(horizontal = 3.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CoverTile(mangaId, cover, coverWidth, coverWidth * 1.5f)
                        }
                    }
                }
                return@Box
            }
            Column(
                modifier = GlanceModifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                (0..<rowCount).forEach { i ->
                    val coverRow = (0..<columnCount).mapNotNull { j ->
                        data.getOrNull(j + (i * columnCount))
                    }
                    if (coverRow.isNotEmpty()) {
                        Row(
                            modifier = GlanceModifier
                                .padding(vertical = 4.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            coverRow.forEach { (mangaId, cover) ->
                                Box(
                                    modifier = GlanceModifier
                                        .padding(horizontal = 3.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CoverTile(
                                        mangaId = mangaId,
                                        cover = cover,
                                        width = grid?.coverWidth ?: CoverWidth,
                                        height = grid?.coverHeight ?: CoverHeight,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One tappable cover: opens the entry in the app. */
@Composable
private fun CoverTile(mangaId: Long, cover: Bitmap?, width: Dp, height: Dp) {
    val intent = Intent(
        LocalContext.current,
        Class.forName(Constants.MAIN_ACTIVITY),
    ).apply {
        action = Constants.SHORTCUT_MANGA
        putExtra(Constants.MANGA_EXTRA, mangaId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // https://issuetracker.google.com/issues/238793260
        addCategory(mangaId.toString())
    }
    UpdatesMangaCover(
        cover = cover,
        modifier = GlanceModifier.clickable(actionStartActivity(intent)),
        width = width,
        height = height,
    )
}

private const val SCROLL_COVER_TARGET_WIDTH = 96f

/** Below this width a cover is hard to see, so the grid scrolls rather than shrinking further. */
private const val MIN_STATIC_COVER_WIDTH = 80f

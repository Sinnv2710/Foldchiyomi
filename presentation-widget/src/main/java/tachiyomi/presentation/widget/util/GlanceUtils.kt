package tachiyomi.presentation.widget.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import tachiyomi.presentation.widget.R

fun GlanceModifier.appWidgetBackgroundRadius(): GlanceModifier {
    return this.cornerRadius(R.dimen.appwidget_background_radius)
}

fun GlanceModifier.appWidgetInnerRadius(): GlanceModifier {
    return this.cornerRadius(R.dimen.appwidget_inner_radius)
}

/**
 * Calculates row-column count.
 *
 * Row
 * Numerator: Container height - container vertical padding
 * Denominator: Cover height + cover vertical padding
 *
 * Column
 * Numerator: Container width - container horizontal padding
 * Denominator: Cover width + cover horizontal padding
 *
 * @return pair of row and column count
 */
/** How a scaled grid lays out: [columns] x [rows] covers of [coverWidth] x [coverHeight]. */
data class CoverGrid(val columns: Int, val rows: Int, val coverWidth: Dp, val coverHeight: Dp)

/**
 * Picks the largest covers (2:3) that still fit [count] of them in this size, so a wide widget shows big
 * covers rather than a few tiny ones. When they cannot all fit, it takes the layout that shows the most.
 */
fun DpSize.fitCovers(count: Int, topPadding: Dp, bottomPadding: Dp): CoverGrid {
    val availWidth = width.value
    val availHeight = (height - topPadding - bottomPadding).value
    var best: CoverGrid? = null
    var bestShown = -1
    for (columns in 1..10) {
        val coverWidth = ((availWidth - columns * COVER_GAP_X) / columns).coerceAtMost(MAX_COVER_WIDTH)
        if (coverWidth < MIN_COVER_WIDTH) continue
        val coverHeight = coverWidth * 1.5f
        val rows = (availHeight / (coverHeight + COVER_GAP_Y)).toInt().coerceIn(1, 10)
        val shown = minOf(rows * columns, count)
        val better = shown > bestShown || (shown == bestShown && coverWidth > (best?.coverWidth?.value ?: 0f))
        if (better) {
            bestShown = shown
            best = CoverGrid(columns, minOf(rows, (count + columns - 1) / columns).coerceAtLeast(1), coverWidth.dp, coverHeight.dp)
        }
    }
    return best ?: CoverGrid(1, 1, MIN_COVER_WIDTH.dp, (MIN_COVER_WIDTH * 1.5f).dp)
}

private const val COVER_GAP_X = 6f
private const val COVER_GAP_Y = 8f
private const val MIN_COVER_WIDTH = 44f
private const val MAX_COVER_WIDTH = 140f

fun DpSize.calculateRowAndColumnCount(
    topPadding: Dp,
    bottomPadding: Dp,
): Pair<Int, Int> {
    // Hack: Size provided by Glance manager is not reliable so take at least 1 row and 1 column
    // Set max to 10 children each direction because of Glance limitation
    val height = this.height - topPadding - bottomPadding
    val rowCount = (height.value / 95).toInt().coerceIn(1, 10)
    val columnCount = (width.value / 64).toInt().coerceIn(1, 10)
    return Pair(rowCount, columnCount)
}

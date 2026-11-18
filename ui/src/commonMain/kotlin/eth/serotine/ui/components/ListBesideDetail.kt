package eth.serotine.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The width of the list's pane beside its detail on a wide window: Material's list pane. */
val LIST_PANE_WIDTH = 360.dp

/**
 * A list and its detail side by side (docs/wallet-ux.md, *Navigation*): while [listShown], the [list] in
 * a pane [LIST_PANE_WIDTH] wide, a divider, and the [detail] in the rest; otherwise the [detail] alone,
 * over the whole width. The [detail] keeps its place in the composition whether the list is shown or
 * not, so what it holds (`App`'s NavHost) survives the window crossing the width that shows the list.
 */
@Composable
fun ListBesideDetail(
    listShown: Boolean,
    list: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit,
) {
    Row(modifier) {
        if (listShown) {
            Box(Modifier.width(LIST_PANE_WIDTH).fillMaxHeight()) { list() }
            VerticalDivider()
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}

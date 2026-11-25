package eth.serotine.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/** Test tag of the column beside an account that shows an Activity row's detail, for UI tests. */
const val ACTIVITY_PANE_TAG = "activity-pane"

/**
 * An account and, while [detailShown], an Activity row's detail beside it, in two columns of half the width each with
 * a divider between (docs/wallet-ux.md, *Navigation*, from 1200 dp on); otherwise the [account] alone. Drawn by
 * `App`'s account destination and by the tablet's previews. The [account] keeps its place in the composition whether
 * the detail is shown or not, so it stays as it was, scrolled where it was.
 */
@Composable
fun DetailBesideAccount(
    detailShown: Boolean,
    account: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit,
) {
    Row(modifier) {
        Box(Modifier.weight(1f).fillMaxHeight()) { account() }
        if (detailShown) {
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight().testTag(ACTIVITY_PANE_TAG)) { detail() }
        }
    }
}

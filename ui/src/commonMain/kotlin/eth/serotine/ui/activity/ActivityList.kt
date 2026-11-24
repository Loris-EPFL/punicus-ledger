package eth.serotine.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.GHOST_ROWS_UNKNOWN
import eth.serotine.ui.components.GhostCircle
import eth.serotine.ui.components.GhostLine
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.components.GhostRows
import eth.serotine.ui.components.SerotineIcons

/** Test tag of each transaction row, for UI tests. */
const val ACTIVITY_ROW_TAG = "activity-row"

/** Test tag of each card of a swap whose next step waits, for UI tests. */
const val WAITING_STEP_TAG = "waiting-step"

/** What reads the addr1 index's next page on the user's tap. */
private const val LOAD_OLDER = "Load older entries"

/** Test tag of each ghost row of an addr1 entry still being checked, for UI tests. */
const val ACTIVITY_PENDING_TAG = "activity-pending"

/** What a screen reader says of a ghost row: an entry of the addr1 index being checked. */
private const val CHECKING_ENTRY = "Checking an entry against the chain"

/** Test tag of each day's heading between the rows, for UI tests. */
const val ACTIVITY_DAY_TAG = "activity-day"

/** Test tag of each row's signed amount, for UI tests. */
const val ACTIVITY_AMOUNT_TAG = "activity-amount"

/** Test tag of the addr1 index's lines above the rows, for UI tests. */
const val ACTIVITY_INDEX_TAG = "activity-index"

/** Test tag of the addr1 index's footer: loading, more, the end of its coverage or why nothing more comes. */
const val ACTIVITY_INDEX_FOOTER_TAG = "activity-index-footer"

/**
 * An account's Activity (docs/wallet-ux.md, screen 25) as items of the list it is drawn in, its
 * detail's Activity tab: what the list covers, then each transaction this device sent for it and
 * each event on chain, newest first, with how it ended as this device saw it; a tap on a row opens
 * its detail ([onOpen], by its key). Above them, a card for each swap whose next step waits for the
 * user (docs/simple-ui.md, H5): Continue opens Swap with it ([onContinue], by the token it sells), greyed
 * out unless [continueAvailable] as Swap is (a tap still goes to [onContinue], which says why), and
 * Dismiss lets it go ([onDismiss]). Before everything, each transfer queued in lenient mode, whose
 * tap opens its status ([onOpen] with its key). One item per row, so that a long history is
 * composed as it scrolls into view. While it is read, ghost rows in the rows' shape hold their room.
 * Where the addr1 index adds rows ([ActivityUiState.Shown.index]), its lines follow what the list
 * covers, and its footer the rows: once the list nears its end and the footer is composed, the
 * next page is asked for ([onMore]), and Try again after a passing refusal asks again.
 */
fun LazyListScope.activityItems(
    state: ActivityUiState,
    onOpen: (ActivityKey) -> Unit,
    onContinue: (sell: String) -> Unit = {},
    onDismiss: (sell: String) -> Unit = {},
    continueAvailable: Boolean = true,
    onMore: () -> Unit = {},
) {
    when (state) {
        // What the list covers and a few rows, in their shapes, until it is read: no spinner the rows push down.
        ActivityUiState.Loading -> item {
            Column {
                GhostLine(240.dp, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
                GhostRows(GHOST_ROWS_UNKNOWN, "Reading the activity") { ActivityGhost() }
            }
        }
        is ActivityUiState.Unavailable -> item {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        is ActivityUiState.Shown -> {
            // What waits in the outbox, first whichever way the rows run: a tap opens its status.
            items(state.queued, key = { it.key }) { row ->
                ActivityItem(row, onClick = { onOpen(row.opens) })
            }
            items(state.waiting, key = { "waiting-${it.sell}" }) { waiting ->
                WaitingStepCard(
                    waiting,
                    continueAvailable,
                    onContinue = { onContinue(waiting.sell) },
                    onDismiss = { onDismiss(waiting.sell) },
                )
            }
            item {
                Text(
                    state.coverage,
                    Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val index = state.index
            if (index != null) item(key = "index-lines") { IndexLines(index) }
            if (index?.footerFirst == true) {
                item(key = "index-footer") { IndexFooterItem(index.footer, onMore, automatic = false) }
            }
            if (state.rows.isEmpty()) {
                item { Text(state.empty, style = MaterialTheme.typography.bodyMedium) }
            }
            // A heading wherever the day changes: the rows of a day are consecutive, whichever way they run.
            var heading: String? = null
            state.rows.forEach { row ->
                val day = row.day
                if (day != null && day != heading) item(key = "day:${row.key}") { DayHeading(day) }
                heading = day
                item(key = row.key) {
                    // An entry still being checked holds its row's room, so that its row, or its leaving, moves
                    // nothing else when it comes.
                    if (row.pending) PendingItem() else ActivityItem(row, onClick = { onOpen(row.opens) })
                }
            }
            if (index != null && !index.footerFirst) {
                item(key = "index-footer") { IndexFooterItem(index.footer, onMore) }
            }
        }
    }
}

/**
 * What the addr1 index adds above the rows: what it is, what it rests on and what it does not cover; then how many
 * entries it left out, and apart, as a warning, how many the chain contradicts.
 */
@Composable
private fun IndexLines(index: IndexSection) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(ACTIVITY_INDEX_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        index.lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        index.leftOut?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        index.contradicted?.let {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * The addr1 index's footer: a ghost row while a page is read, which holds its room; nothing to see while more lies
 * below, but its being composed (the list near its end) asks for the next page ([onMore]); the end of its coverage;
 * or why nothing more comes, with Try again where asking again may answer. Above the rows ([automatic] false, when
 * they run oldest first) it is in sight from the start and stays there as pages load below it: more is then read
 * only on Load older, never by itself.
 */
@Composable
private fun IndexFooterItem(footer: IndexFooter, onMore: () -> Unit, automatic: Boolean = true) {
    val more by rememberUpdatedState(onMore)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(ACTIVITY_INDEX_FOOTER_TAG)) {
        when (footer) {
            is IndexFooter.Loading -> {
                ActivityGhost()
                Text(
                    footer.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The entries still being checked are ghost rows in their places: the footer only counts them.
            is IndexFooter.Checking -> Text(
                footer.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IndexFooter.More -> if (automatic) {
                LaunchedEffect(Unit) { more() }
            } else {
                TextButton(onClick = onMore) { Text(LOAD_OLDER) }
            }
            // The last page added no row: read on only when the user asks.
            is IndexFooter.Paused -> {
                Text(
                    footer.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onMore) { Text(LOAD_OLDER) }
            }
            is IndexFooter.End -> Text(
                footer.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is IndexFooter.Refused -> {
                Text(
                    footer.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (footer.warning) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                if (footer.retry) {
                    TextButton(onClick = onMore) { Text("Try again") }
                }
            }
        }
    }
}

/** A swap whose next step waits: what it swaps, where it stands, and Continue or Dismiss. */
@Composable
private fun WaitingStepCard(step: WaitingStep, available: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag(WAITING_STEP_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            Text(step.message, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.unavailable(!available),
                    colors = if (available) ButtonDefaults.buttonColors() else Unavailable.buttonColors(),
                ) { Text("Continue") }
            }
        }
    }
}

/** An entry of the addr1 index being checked: a ghost of a row's height, named for a screen reader. */
@Composable
private fun PendingItem() {
    Box(
        Modifier.testTag(ACTIVITY_PENDING_TAG).semantics { contentDescription = CHECKING_ENTRY },
    ) { ActivityGhost() }
}

/** An [ActivityItem] not read yet, of its height. */
@Composable
private fun ActivityGhost() {
    ListItem(
        leadingContent = { GhostCircle(40.dp) },
        overlineContent = { GhostLine(96.dp) },
        headlineContent = { GhostLine(140.dp) },
        supportingContent = {
            Column {
                GhostLine(180.dp)
                GhostLine(120.dp, style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

/** The heading of a day's rows: "Today · Monday, November 23, 2026". */
@Composable
private fun DayHeading(day: String) {
    Text(
        day,
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).testTag(ACTIVITY_DAY_TAG).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One row; a tap opens its detail ([onClick]). Which way it moved value is an arrow on the blockie's corner, green in
 * and red out, and what it moved is on the right, signed; its time of day where the list is grouped by day.
 */
@Composable
private fun ActivityItem(row: ActivityRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag(ACTIVITY_ROW_TAG).clickable(onClick = onClick),
        leadingContent = { DirectedAvatar(row.about, row.direction, muted = !row.settled) },
        // When, above what it did: beside it, the date would squeeze each row into narrow lines.
        overlineContent = (row.clock ?: row.time).takeIf { it.isNotEmpty() }?.let { time -> { Text(time) } },
        headlineContent = { Text(row.headline ?: row.title) },
        supportingContent = {
            Column {
                Text(row.detail)
                Text(row.status, style = MaterialTheme.typography.labelSmall, color = statusColor(row.state))
            }
        },
        trailingContent = row.amount?.let { amount ->
            {
                Text(
                    amount,
                    Modifier.testTag(ACTIVITY_AMOUNT_TAG),
                    style = MaterialTheme.typography.titleMedium,
                    color = amountColor(row.direction, settled = row.settled),
                    textAlign = TextAlign.End,
                )
            }
        },
    )
}

/**
 * The blockie of a row's other side, with an arrow on its corner for the way value went ([direction]): green in and
 * red out, or [muted] while what moved is not settled.
 */
@Composable
private fun DirectedAvatar(about: String, direction: ActivityDirection, muted: Boolean) {
    Box {
        AccountAvatar(about)
        if (direction != ActivityDirection.NONE) {
            val incoming = direction == ActivityDirection.IN
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(20.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (incoming) SerotineIcons.SouthWest else SerotineIcons.NorthEast,
                    contentDescription = if (incoming) "Incoming" else "Outgoing",
                    modifier = Modifier.size(16.dp),
                    tint = when {
                        muted -> MaterialTheme.colorScheme.onSurfaceVariant
                        incoming -> incomingColor()
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/**
 * Whether what this row moved is settled: done on chain, and proven to have moved. Not while it waits, is unconfirmed
 * or unknown, calls for care (a caution, a warning), or its outcome is not checked: its amount and arrow are muted.
 */
private val ActivityRow.settled: Boolean
    get() = state == ActivityState.DONE && !unchecked

/**
 * The colour a row's amount is said in: green for value that came in, the text's own for value that went out, both
 * only once [settled]; muted while not.
 */
@Composable
private fun amountColor(direction: ActivityDirection, settled: Boolean): Color = when {
    !settled -> MaterialTheme.colorScheme.onSurfaceVariant
    direction == ActivityDirection.IN -> incomingColor()
    else -> MaterialTheme.colorScheme.onSurface
}

/** Green, for value that came in: darker on a light theme, lighter on a dark one, for contrast either way. */
@Composable
private fun incomingColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF81C995) else Color(0xFF1E7E34)

/**
 * The colour a row's status is said in, here and on its detail: an error's for a revert or a
 * warning, muted for what is not known yet or calls for care.
 */
@Composable
internal fun statusColor(state: ActivityState): Color = when (state) {
    ActivityState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    ActivityState.FAILED, ActivityState.WARNING -> MaterialTheme.colorScheme.error
    ActivityState.WAITING, ActivityState.UNCONFIRMED, ActivityState.UNKNOWN, ActivityState.CAUTION ->
        MaterialTheme.colorScheme.tertiary
}

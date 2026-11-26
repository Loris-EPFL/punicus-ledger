package eth.serotine.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The tag of the first start's notice, for tests. */
const val FIRST_RUN_DIALOG_TAG = "first-run-dialog"

/**
 * The notice of the app's first start (owner's request, 2026-11-26): [text]'s points, and OK, which dismisses it
 * ([onOk]). Tapping outside or Back dismisses it too: the user has seen it.
 */
@Composable
fun FirstRunDialog(text: FirstRunNoticeText, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        modifier = Modifier.testTag(FIRST_RUN_DIALOG_TAG),
        title = { Text(text.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                text.points.forEach { (heading, line) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(heading, style = MaterialTheme.typography.titleSmall)
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } },
    )
}

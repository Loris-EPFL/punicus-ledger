package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressLookalikes
import eth.serotine.domain.usecase.PlantKind
import eth.serotine.domain.usecase.RecipientCheck

/** Tag of the recipient warning's text, and of the look-alike comparison's two addresses. */
const val RECIPIENT_WARNING_TAG = "recipient-warning"
const val KNOWN_ADDRESS_TAG = "recipient-known-address"
const val RECIPIENT_ACKNOWLEDGE_TAG = "recipient-acknowledge"

/**
 * What the pre-send check says about the recipient (address poisoning protection), display-ready.
 * [blocking]: nothing is signed while it stands. [known], when a look-alike was found, is the EIP-55
 * address the recipient imitates, [knownLabel] how the user knows it ("Savings", "an address you
 * sent to").
 */
data class RecipientWarning(
    val blocking: Boolean,
    val headline: String,
    val detail: String,
    val known: String? = null,
    val knownLabel: String? = null,
    val overridable: Boolean = false,
)

/** The warning a [check] of [recipient] shows, or null when it says nothing. [name] is the wallet's name for the known address. */
internal fun recipientWarning(
    check: RecipientCheck,
    acknowledged: Boolean = false,
    name: (Address) -> String?,
): RecipientWarning? = when (check) {
    RecipientCheck.Familiar -> null
    is RecipientCheck.NewRecipient -> RecipientWarning(
        blocking = false,
        headline = "New recipient",
        detail = "You have not sent to this address before. Check every character against where you got it." +
            if (check.historyRead) "" else " Your history is not read yet, so a sender who planted it cannot be ruled out.",
    )
    is RecipientCheck.Lookalike -> RecipientWarning(
        blocking = true,
        headline = "Looks like an address you know, but it is not",
        detail = "It starts and ends like an address you sent to or own, yet the middle differs. " +
            "This is how address poisoning works: nothing is signed until you change the recipient.",
        known = Eip55.format(check.known),
        knownLabel = name(check.known) ?: "An address you know",
    )
    is RecipientCheck.PlantedSender -> RecipientWarning(
        headline = "This address sent you ${
            when (check.kind) {
                PlantKind.ZERO_VALUE -> "a transfer of nothing"
                PlantKind.DUST -> "a dust amount"
                PlantKind.UNLISTED_TOKEN -> "an unlisted token"
            }
        }",
        detail = "Attackers do that to get their look-alike address into your history so you copy it. " +
            if (acknowledged) "You chose to send to it anyway." else "Nothing is signed until you change the recipient.",
        blocking = !acknowledged,
        overridable = !acknowledged,
    )
}

/**
 * What the screen calls when the user chooses to send to a planted sender anyway, or null where
 * that is not offered. Provided by the Send route; a callback, never state.
 */
internal val LocalAcknowledgeRecipient = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Whether the warning stops the send. */
internal val RecipientWarning?.blocks: Boolean
    get() = this?.blocking == true

/**
 * [address] (EIP-55) as the confirm screen shows it: `0x`, then the 40 digits in groups of four,
 * all of them, never shortened.
 */
fun chunkedAddress(address: String): String =
    "0x " + address.removePrefix("0x").removePrefix("0X").chunked(4).joinToString(" ")

/**
 * [address] chunked, with the digits in which it differs from [other] (the middle, between the
 * leading and trailing digits both share) marked: bold, in the error colour.
 */
@Composable
internal fun differingChunked(address: String, other: String): AnnotatedString {
    val a = Address.parse(address)
    val b = Address.parse(other)
    val split = if (a != null && b != null) AddressLookalikes.split(a, b) else null
    val digits = address.removePrefix("0x").removePrefix("0X")
    val mark = SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
    return buildAnnotatedString {
        append("0x ")
        digits.forEachIndexed { i, digit ->
            if (i > 0 && i % 4 == 0) append(' ')
            val differs = split != null && i >= split.prefix && i < digits.length - split.suffix
            if (differs) withStyle(mark) { append(digit) } else append(digit)
        }
    }
}

/** The warning, and for a look-alike both addresses with the differing middle highlighted. */
@Composable
internal fun RecipientWarningCard(recipient: RecipientItem, warning: RecipientWarning) {
    Surface(
        Modifier.fillMaxWidth().testTag(RECIPIENT_WARNING_TAG),
        shape = MaterialTheme.shapes.medium,
        color = if (warning.blocking) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(warning.headline, style = MaterialTheme.typography.titleSmall)
            Text(warning.detail, style = MaterialTheme.typography.bodySmall)
            val acknowledge = LocalAcknowledgeRecipient.current
            if (warning.overridable && acknowledge != null) {
                TextButton(onClick = acknowledge, Modifier.testTag(RECIPIENT_ACKNOWLEDGE_TAG)) {
                    Text("I checked it: send anyway")
                }
            }
            warning.known?.let { known ->
                DisableSelection {
                    Text(warning.knownLabel ?: "Known address", style = MaterialTheme.typography.labelMedium)
                    Text(
                        differingChunked(known, recipient.address),
                        Modifier.testTag(KNOWN_ADDRESS_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("This recipient", style = MaterialTheme.typography.labelMedium)
                    Text(
                        differingChunked(recipient.address, known),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

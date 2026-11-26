package eth.serotine.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.BiometricUnusable
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.ui.ledger.ledgerProblemText
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How a review signs: with a connected [ledger], or with the wallet [password] or [biometrics].
 * [error] says why the last try signed nothing. The password is held only
 * while the field is shown (CLAUDE.md, *Trust*); its ViewModel drops it once used and whenever the
 * screen is not shown.
 */
data class UnlockStep(
    val password: WalletPassword = WalletPassword.EMPTY,
    val biometrics: Boolean = false,
    val error: String? = null,
    val ledger: Boolean = false,
) {
    /** Whether this review can request a signature without a biometric prompt. */
    val canAuthorize: Boolean get() = ledger || password.text.isNotEmpty()
}

/** Apply the custody of the actual sender or owner; never carry a local credential into a Ledger review. */
internal fun UnlockStep.forCustody(custody: AccountCustody?, localBiometrics: Boolean = false): UnlockStep = when {
    custody is AccountCustody.Ledger -> if (ledger) this else UnlockStep(ledger = true)
    ledger -> UnlockStep(biometrics = localBiometrics)
    else -> this
}

/** A tap on the review's button. A Ledger approval cannot outlive this review's deadline. */
internal fun UnlockStep.approval(notAfter: TimeMark? = null): Unlock? = when {
    ledger -> Unlock.WithLedger(notAfter ?: (TimeSource.Monotonic.markNow() + QUOTE_MAX_AGE))
    password.text.isNotEmpty() -> Unlock.WithPassword(password)
    else -> null
}

internal fun UnlockStep.promptTooLate(): String = if (ledger) LEDGER_PROMPT_TOO_LATE else PROMPT_TOO_LATE

internal fun UnlockStep.promptTimedOut(): String = if (ledger) LEDGER_PROMPT_TIMED_OUT else PROMPT_TIMED_OUT

/**
 * A transaction signed but not broadcast: [message] why, and [canRetry] when sending the same signed
 * bytes again may get it out (no peer took it), never a new signature.
 */
data class NotSentItem(val message: String, val canRetry: Boolean)

/**
 * The unlock of a signing step: a fingerprint or face where the user turned it on, the wallet
 * password always, and the button that signs ([signLabel], [signingLabel] while [signing]). While it
 * is shown, the window is kept out of screen capture where the platform allows
 * ([protectFromScreenCapture]: rule 8's protections of a screen that takes the password); where it
 * does not, it says so. [passwordTag] and [signTag] tag the field and the button for UI tests.
 */
@Composable
internal fun UnlockFields(
    unlock: UnlockStep,
    signing: Boolean,
    canSign: Boolean,
    canSignWithBiometrics: Boolean,
    signLabel: String,
    signingLabel: String,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    passwordTag: String,
    signTag: String,
) {
    if (unlock.ledger) {
        Text("Connect your Ledger by USB, unlock it, and open the Ethereum app.")
        Text(
            "The Ledger may show only a hash or ask for blind signing. Check the details in Punicus and on the " +
                "Ledger before approving. Enable blind signing on the device only if you accept that it " +
                "cannot show the full request.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        unlock.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SigningButton(
            onSign, canSign, signing, "Approve on Ledger", "Waiting for Ledger…", signTag,
        )
        return
    }
    // Only while the password field is shown: rule 8's protections of a screen that takes it.
    val captureBlocked = protectFromScreenCapture()
    if (unlock.biometrics) {
        FilledTonalButton(
            onClick = onSignWithBiometrics,
            enabled = canSignWithBiometrics,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign with fingerprint or face")
        }
        Text(
            "Or with the wallet password:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedTextField(
        value = unlock.password.text,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth().testTag(passwordTag),
        enabled = !signing,
        label = { Text("Wallet password") },
        supportingText = unlock.error?.let { error -> { Text(error) } },
        isError = unlock.error != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSign() }),
    )
    SigningButton(onSign, canSign, signing, signLabel, signingLabel, signTag)
    if (!captureBlocked) {
        Text(
            "Screenshots and screen recordings of this window cannot be blocked here. Make sure nothing is " +
                "recording your screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SigningButton(
    onSign: () -> Unit,
    canSign: Boolean,
    signing: Boolean,
    signLabel: String,
    signingLabel: String,
    signTag: String,
) {
    Button(onClick = onSign, enabled = canSign, modifier = Modifier.fillMaxWidth().testTag(signTag)) {
        if (signing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(if (signing) signingLabel else signLabel)
    }
}

/** Why a signed transaction was not broadcast, and Try again where sending its bytes again may help. */
@Composable
internal fun NotSentFields(notSent: NotSentItem, retrying: Boolean, onRetry: () -> Unit) {
    Text(notSent.message, color = MaterialTheme.colorScheme.error)
    if (notSent.canRetry) {
        Button(onClick = onRetry, enabled = !retrying) {
            if (retrying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Try again")
        }
    }
}

/** Whether [description] has the biometric prompt on for this wallet: what a signing step offers it on. */
internal fun biometricsOn(description: KeyStoreDescription): Boolean =
    (description as? KeyStoreDescription.Ready)?.biometrics == BiometricUnlock.On

/**
 * Why [signing] signed nothing, display-ready; [failed] for an answer that is no refusal (a
 * signature handed back as one).
 */
internal fun notSignedText(signing: Signing, failed: String): String = when (signing) {
    Signing.WrongPassword -> "That is not the wallet password. Nothing was signed."
    Signing.Cancelled ->
        "The fingerprint or face check was dismissed. Nothing was signed: try again, or use the wallet password."
    is Signing.BiometricsUnusable -> when (signing.reason) {
        BiometricUnusable.OFF -> "A fingerprint or face cannot open this account's key: use the wallet password."
        BiometricUnusable.INVALIDATED ->
            "Biometric unlock stopped working: the enrolled fingerprints or faces changed. Use the wallet " +
                "password; Settings → Security turns it on again."
        BiometricUnusable.LOCKED_OUT ->
            "Too many failed attempts: biometrics are locked for now. Use the wallet password."
        BiometricUnusable.SENSOR_UNAVAILABLE -> "The sensor cannot be used right now: use the wallet password."
        BiometricUnusable.NO_PROMPT -> "The fingerprint or face check could not be shown: use the wallet password."
    }
    Signing.TimedOut -> PROMPT_TIMED_OUT
    is Signing.LedgerFailed -> ledgerProblemText(signing.problem)
    Signing.NoKey -> "This device holds no key for this account, so nothing was signed."
    Signing.Unavailable -> "The key store cannot open this account's key on this device. Nothing was signed."
    Signing.NotEnoughMemory ->
        "Not enough free memory to open the key: close other apps and try again. Nothing was signed."
    is Signing.Signed -> failed // a signature is not a reason not to have signed
}

/** Whether the biometric prompt is still worth offering after [signing] refused one. */
internal fun biometricsStillOffered(signing: Signing): Boolean = when (signing) {
    is Signing.BiometricsUnusable -> signing.reason == BiometricUnusable.SENSOR_UNAVAILABLE ||
        signing.reason == BiometricUnusable.NO_PROMPT
    else -> true
}

/**
 * Why a [what] ("transfer", "approval") signed was not broadcast, from [broadcast]'s verdict:
 * [unverified] when a retry's rejection could not be checked against its receipt, so whether the
 * first broadcast reached a block is not known. Retry is offered only where the same bytes may
 * still get out, never a new signature.
 */
internal fun notSentItem(
    broadcast: Broadcast,
    unverified: Boolean,
    what: String,
    maybeSent: Boolean = false,
): NotSentItem = when {
    // A rejection whose receipt could not be checked says so, whatever the record.
    unverified -> notSentItemOf(broadcast, unverified = true, what)
    // A retry of one maybe sent turned away: the peer's word on this try, not on the first.
    maybeSent && (broadcast is Broadcast.Rejected || broadcast is Broadcast.Refused) -> turnedAway(broadcast, what)
    else -> notSentItemOf(broadcast, unverified, what)
}

/**
 * Why a retry of a [what] recorded as maybe sent was turned away ([broadcast], a rejection or a
 * refusal), said as the peer's word: it is no verdict on the first broadcast, which may be in a
 * pool or in a block past the light client's head, so the record is kept and Try again stays.
 */
private fun turnedAway(broadcast: Broadcast, what: String): NotSentItem {
    val said = when (broadcast) {
        is Broadcast.Rejected -> broadcast.detail
        is Broadcast.Refused -> broadcast.detail
        else -> null
    }
    return NotSentItem(
        "Turned away this time" + (said?.let { " (the peer said: $it)" } ?: "") + ". That says nothing sure of " +
            "the first broadcast, which may be on its way or in a block past the light client's head, so the " +
            "signed $what is kept, and nothing new is sent from this account until a block has it or three hours " +
            "have passed. Try again sends the same signed $what.",
        canRetry = true,
    )
}

private fun notSentItemOf(broadcast: Broadcast, unverified: Boolean, what: String): NotSentItem = when (broadcast) {
    // Not "can never be mined" when unverified: that is a verified verdict, and this one could not be checked.
    is Broadcast.Rejected -> if (unverified) {
        NotSentItem(
            "Rejected this time (${broadcast.detail}), and whether the first broadcast reached a block after " +
                "all cannot be verified right now. Try again sends the same signed $what and checks again; " +
                "do not send a new one meanwhile.",
            canRetry = true,
        )
    } else {
        NotSentItem(
            "Signed, but not sent: judged on verified state, it can never be mined as signed " +
                "(${broadcast.detail}). Sending it again would not change that.",
            canRetry = false,
        )
    }
    is Broadcast.Refused -> NotSentItem(
        "Signed, but not sent: the light client does not relay it (${broadcast.detail}).",
        canRetry = false,
    )
    is Broadcast.Unavailable -> NotSentItem(
        "Signed, but not sent: no peer took it right now" + (broadcast.detail?.let { " ($it)" } ?: "") +
            ". Try again sends the same signed $what, not a new one.",
        canRetry = true,
    )
    is Broadcast.Sent -> NotSentItem("Sent.", canRetry = false) // not a NotSent verdict
}

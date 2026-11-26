package eth.serotine.ui.firstrun

/**
 * The notice of the app's first start (owner's request, 2026-11-26; docs/wallet-ux.md, *The first start*): hidden,
 * or shown with its [Shown.text].
 */
sealed interface FirstRunUiState {
    /** Nothing to show: not a first start, dismissed, or not decided yet. */
    data object Hidden : FirstRunUiState

    /** Shown, until OK. */
    data class Shown(val text: FirstRunNoticeText = FirstRunNoticeText.DEFAULT) : FirstRunUiState
}

/**
 * What the notice says: its [title], and its [points], each a heading and a line. The headings are the website's
 * (website/guide.html, *When you run Punicus for the first time*): a change to one changes the other.
 */
data class FirstRunNoticeText(val title: String, val points: List<Pair<String, String>>) {
    companion object {
        /** What to expect while Punicus finds its first peers. */
        val DEFAULT = FirstRunNoticeText(
            title = "Your first start",
            points = listOf(
                "Give it a few minutes" to "Punicus talks to the network directly, with no company in between. On " +
                    "its first start it has to find computers of the network to talk to. It remembers them, so " +
                    "the next start is quicker.",
                "While a bar or a banner is red, wait" to "Red means Punicus has not checked that network yet: " +
                    "its balances are not confirmed, and sending waits. It turns green once the network is checked.",
                "Try again often helps" to "Punicus keeps working in the background. If something does not load, " +
                    "tap Try again, or pull down to refresh, a little later.",
            ),
        )
    }
}

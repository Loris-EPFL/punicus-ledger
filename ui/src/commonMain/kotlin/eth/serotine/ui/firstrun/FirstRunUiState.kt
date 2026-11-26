package eth.serotine.ui.firstrun

/**
 * The notice of the app's first start (owner's request, 2026-11-26; docs/wallet-ux.md, *The first start*): what to
 * expect while Punicus finds its first peers, in [points], each a heading and a line. Shown once, until OK.
 */
data class FirstRunNoticeText(val title: String, val points: List<Pair<String, String>>) {
    companion object {
        /** The words, the same as the website's *When you run Punicus for the first time*. */
        val DEFAULT = FirstRunNoticeText(
            title = "Your first start",
            points = listOf(
                "Give it a few minutes" to "Punicus talks to the network directly, with no company in between. On " +
                    "its first start it has to find computers of the network to talk to, and it remembers them, " +
                    "so the next start is quicker.",
                "While a bar or a banner is red, wait" to "Red means Punicus has not checked that network yet: " +
                    "balances are not confirmed and sending waits. It turns green once the network is checked.",
                "Try again often helps" to "Punicus keeps working in the background. If something could not be " +
                    "read, tap Try again or pull down to refresh a little later.",
            ),
        )
    }
}

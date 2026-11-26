package eth.serotine.ui.firstrun

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** [FirstRunDialog]: the three points, and OK. */
class FirstRunDialogTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun itSaysWhatToExpectAndOkDismissesIt() {
        var oks = 0
        rule.setContent { SerotineTheme { FirstRunDialog(FirstRunNoticeText.DEFAULT, onOk = { oks++ }) } }

        rule.onNodeWithText("Give it a few minutes").assertIsDisplayed()
        rule.onNodeWithText("Until a network is checked, wait").assertIsDisplayed()
        rule.onNodeWithText("Try again often helps").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()

        assertEquals(1, oks)
    }
}

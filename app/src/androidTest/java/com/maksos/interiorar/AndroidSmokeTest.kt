package com.maksos.interiorar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Compose semantic-tree smoke; camera/AR tracking is intentionally not used in emulator. */
class AndroidSmokeTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test fun mainScreenShowsProjectAndSampleActions() {
        composeTestRule.onNodeWithText("Загрузить GLB").assertIsDisplayed()
        composeTestRule.onNodeWithText("Тестовая кухня").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Кухня из твоего референса · остров + 3 стула"
        ).assertIsDisplayed()
    }
}

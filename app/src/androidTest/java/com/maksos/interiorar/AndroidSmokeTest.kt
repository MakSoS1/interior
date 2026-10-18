package com.maksos.interiorar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Ignore

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
    @Ignore("SceneView 3D hardware renderer crashes headless CI AVD; run on an actual phone with GPU")
    @Test fun openingReferenceKitchenShowsActualMetricDimensionsInPreview() {
        composeTestRule.onNodeWithText(
            "Кухня из твоего референса · остров + 3 стула"
        ).performClick()
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithText("Разместить в AR · 1:1")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Разместить в AR · 1:1").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Ширина × высота × глубина:", substring = true
        ).assertIsDisplayed()
    }
}

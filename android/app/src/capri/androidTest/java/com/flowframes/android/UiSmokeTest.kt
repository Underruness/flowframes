package com.flowframes.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test for the Capri edition: the studio renders and the settings tab opens. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun studioAndSettingsRender() {
        rule.onNodeWithText("CAPRI").assertExists()
        rule.onNodeWithText("No video in the queue").assertExists()
        rule.onNodeWithText("Add videos").assertExists()
        rule.onNodeWithText("Interpolate").assertExists()

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Output rate").assertExists()
        rule.onNodeWithText("Engine").assertExists()
        rule.onNodeWithText("Output video").assertExists()

        rule.onNodeWithText("Studio").performClick()
        rule.onNodeWithText("Add videos").assertExists()
    }
}

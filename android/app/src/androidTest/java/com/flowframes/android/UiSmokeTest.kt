package com.flowframes.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test: the Compose UI inflates, the queue screen renders and settings open/close. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun queueAndSettingsScreensRender() {
        rule.onNodeWithText("FlowFrames").assertExists()
        rule.onNodeWithText("No video in the queue").assertExists()
        rule.onNodeWithText("Add videos").assertExists()
        rule.onNodeWithText("Start").assertExists()

        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithText("Multiplier").assertExists()
        rule.onNodeWithText("Model quality").assertExists()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Add videos").assertExists()
    }
}

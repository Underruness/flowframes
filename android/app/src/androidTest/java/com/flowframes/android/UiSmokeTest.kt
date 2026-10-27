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
        rule.onNodeWithText("Aucune vidéo en file").assertExists()
        rule.onNodeWithText("Ajouter").assertExists()
        rule.onNodeWithText("Démarrer").assertExists()

        rule.onNodeWithContentDescription("Réglages").performClick()
        rule.onNodeWithText("Réglages").assertExists()
        rule.onNodeWithText("Facteur").assertExists()
        rule.onNodeWithText("Qualité du modèle").assertExists()

        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("Ajouter").assertExists()
    }
}

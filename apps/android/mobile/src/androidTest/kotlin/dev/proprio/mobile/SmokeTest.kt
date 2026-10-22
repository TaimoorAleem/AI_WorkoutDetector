package dev.proprio.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real app on a phone emulator. No watch is attached, so only the first-run screen
 * (left-wrist reminder, participant id, consent) and a clean startup are verified.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndShowsFirstRunSetup() {
        compose.onNodeWithTag("wrist_reminder").assertExists()
        compose.onNodeWithTag("participant_id").assertExists()
        compose.onNodeWithTag("consent").assertExists()
    }
}

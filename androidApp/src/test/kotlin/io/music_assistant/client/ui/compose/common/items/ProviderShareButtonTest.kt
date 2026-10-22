package io.music_assistant.client.ui.compose.common.items

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.music_assistant.client.data.model.client.AppMediaItemFixtures
import io.music_assistant.client.data.model.server.ProviderMapping
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ProviderShareButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `sharing is hidden when provider mappings are absent`() {
        val item = AppMediaItemFixtures.track().copy(providerMappings = null)

        composeTestRule.setContent { ProviderShareButton(item) }

        composeTestRule.onNodeWithContentDescription("Share link").assertDoesNotExist()
    }

    @Test
    fun `sharing is hidden when no provider has a usable public link`() {
        val item = AppMediaItemFixtures.track().copy(
            providerMappings = listOf(
                mapping("ytmusic", null),
                mapping("spotify", ""),
                mapping("other", "other://track/123"),
                mapping("filesystem_local", "https://example.com/music/123.flac"),
            ),
        )

        composeTestRule.setContent { ProviderShareButton(item) }

        composeTestRule.onNodeWithContentDescription("Share link").assertDoesNotExist()
    }

    @Test
    fun `sharing is visible for another provider when youtube has no link`() {
        val item = AppMediaItemFixtures.track().copy(
            providerMappings = listOf(
                mapping("ytmusic", null),
                mapping("spotify", "https://open.spotify.com/track/123"),
            ),
        )

        composeTestRule.setContent { ProviderShareButton(item) }

        composeTestRule.onNodeWithContentDescription("Share link").assertIsDisplayed()
    }

    @Test
    fun `sharing disappears when the public link is removed`() {
        var item by mutableStateOf(
            AppMediaItemFixtures.track().copy(
                providerMappings = listOf(mapping("spotify", "https://open.spotify.com/track/123")),
            ),
        )
        composeTestRule.setContent { ProviderShareButton(item) }
        composeTestRule.onNodeWithContentDescription("Share link").assertIsDisplayed()

        composeTestRule.runOnIdle {
            item = item.copy(providerMappings = listOf(mapping("spotify", null)))
        }

        composeTestRule.onNodeWithContentDescription("Share link").assertDoesNotExist()
    }

    @Test
    fun `choosing youtube from multiple providers shares its link`() {
        assertProviderChoiceShares("Share YouTube Music link", "https://music.youtube.com/watch?v=123")
    }

    @Test
    fun `choosing spotify from multiple providers shares its link`() {
        assertProviderChoiceShares("Share spotify link", "https://open.spotify.com/track/123")
    }

    private fun assertProviderChoiceShares(choice: String, expectedUrl: String) {
        val item = AppMediaItemFixtures.track().copy(
            providerMappings = listOf(
                mapping("ytmusic", "https://music.youtube.com/watch?v=123"),
                mapping("spotify", "https://open.spotify.com/track/123"),
                mapping("ytmusic", "https://music.youtube.com/watch?v=123")
                    .copy(providerInstance = "ytmusic--other-account"),
                mapping("other", null),
            ),
        )
        val startedIntents = mutableListOf<Intent>()
        composeTestRule.setContent {
            val baseContext = LocalContext.current
            val context = remember(baseContext) {
                object : ContextWrapper(baseContext) {
                    override fun startActivity(intent: Intent) {
                        startedIntents.add(intent)
                    }
                }
            }
            CompositionLocalProvider(LocalContext provides context) {
                ProviderShareButton(item)
            }
        }

        composeTestRule.onNodeWithContentDescription("Share link").performClick()

        composeTestRule.onAllNodesWithText("Share YouTube Music link").assertCountEquals(1)
        composeTestRule.onNodeWithText("Share YouTube Music link").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share spotify link").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share other link").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(startedIntents.isEmpty()) }

        composeTestRule.onNodeWithText(choice).performClick()

        composeTestRule.runOnIdle {
            val chooser = startedIntents.single()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val sendIntent = assertNotNull(
                IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java),
            )
            assertEquals(Intent.ACTION_SEND, sendIntent.action)
            assertEquals("text/plain", sendIntent.type)
            assertEquals(expectedUrl, sendIntent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(item.name, sendIntent.getStringExtra(Intent.EXTRA_SUBJECT))
        }
    }

    private fun mapping(provider: String, url: String?) = ProviderMapping(
        itemId = "123",
        providerDomain = provider,
        providerInstance = "$provider--account",
        url = url,
    )
}

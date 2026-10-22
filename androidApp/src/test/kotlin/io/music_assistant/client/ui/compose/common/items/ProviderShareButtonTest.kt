package io.music_assistant.client.ui.compose.common.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.music_assistant.client.data.model.client.AppMediaItemFixtures
import io.music_assistant.client.data.model.server.ProviderMapping
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    private fun mapping(provider: String, url: String?) = ProviderMapping(
        itemId = "123",
        providerDomain = provider,
        providerInstance = "$provider--account",
        url = url,
    )
}

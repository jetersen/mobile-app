package io.music_assistant.client.ui.compose.common.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.music_assistant.client.data.model.client.items.AppMediaItem
import io.music_assistant.client.data.model.client.providerLinks
import io.music_assistant.client.ui.compose.common.OverflowMenuButton
import io.music_assistant.client.ui.compose.common.OverflowMenuOption
import musicassistantclient.composeapp.generated.resources.Res
import musicassistantclient.composeapp.generated.resources.action_share_link
import musicassistantclient.composeapp.generated.resources.action_share_provider_link
import org.jetbrains.compose.resources.stringResource

@Composable
expect fun rememberShareProviderLink(): (url: String, title: String) -> Unit

@Composable
fun ProviderShareButton(item: AppMediaItem) {
    val links = remember(item.providerMappings) { providerLinks(item.providerMappings.orEmpty()) }
    if (links.isEmpty()) return
    val share = rememberShareProviderLink()
    if (links.size == 1) {
        IconButton(onClick = { share(links.single().url, item.name) }) {
            Icon(Icons.Default.Share, stringResource(Res.string.action_share_link))
        }
        return
    }
    val options = links.map { link ->
        val provider = when (link.provider) {
            "ytmusic" -> "YouTube Music"
            else -> link.provider
        }
        OverflowMenuOption(
            title = stringResource(Res.string.action_share_provider_link, provider),
            icon = Icons.Default.Share,
            onClick = { share(link.url, item.name) },
        )
    }
    OverflowMenuButton(options = options) { onClick ->
        IconButton(onClick = onClick) {
            Icon(Icons.Default.Share, stringResource(Res.string.action_share_link))
        }
    }
}

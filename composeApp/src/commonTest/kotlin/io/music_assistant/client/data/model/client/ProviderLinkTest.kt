package io.music_assistant.client.data.model.client

import io.music_assistant.client.data.model.server.ProviderMapping
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderLinkTest {
    private fun mapping(url: String?, domain: String = "ytmusic") = ProviderMapping(
        itemId = "meuR1l4j-xs",
        providerDomain = domain,
        providerInstance = "$domain--account",
        url = url,
    )

    @Test
    fun `server mapping retains the optional public url`() {
        val json = """{"item_id":"x","provider_domain":"ytmusic","provider_instance":"ytmusic--a"} """
        assertNull(Json.decodeFromString<ProviderMapping>(json).url)
        val url = "https://music.youtube.com/watch?v=meuR1l4j-xs"
        assertEquals(url, Json.decodeFromString<ProviderMapping>(Json.encodeToString(mapping(url))).url)
    }

    @Test
    fun `track shares its source link once across provider accounts`() {
        val musicUrl = "https://music.youtube.com/watch?v=meuR1l4j-xs&si=tracking"
        assertEquals(
            listOf(
                ProviderLink(musicUrl, "ytmusic"),
            ),
            providerLinks(listOf(mapping(musicUrl), mapping(musicUrl).copy(providerInstance = "ytmusic--other"))),
        )
    }

    @Test
    fun `only absolute website urls are shareable`() {
        listOf(
            "",
            " ",
            "relative/path",
            "/track/123",
            "//music.youtube.com/watch?v=x",
            "https:///track/123",
            "http://",
            "https://?track=123",
            "https://:443/track/123",
            "https://@/track/123",
            "https://user@/track/123",
        ).forEach { url ->
            assertTrue(providerLinks(listOf(mapping(url))).isEmpty(), url)
        }
    }

    @Test
    fun `valid website urls are trimmed and retained across providers`() {
        val youtube = "https://music.youtube.com/watch?v=meuR1l4j-xs"
        val other = "http://example.com/track/123"
        assertEquals(
            listOf(ProviderLink(youtube, "ytmusic"), ProviderLink(other, "other")),
            providerLinks(listOf(mapping(" $youtube "), mapping(other, "other"))),
        )
    }

    @Test
    fun `album browse id does not become a watch link`() {
        val url = "https://music.youtube.com/browse/MPREb_pRx4LBJiFWR"
        assertEquals(listOf(ProviderLink(url, "ytmusic")), providerLinks(listOf(mapping(url))))
    }

    @Test
    fun `unshareable mappings are excluded`() {
        assertTrue(
            providerLinks(
                listOf(
                    mapping(null),
                    mapping("javascript:alert(1)"),
                    mapping("ytmusic://track/x"),
                    mapping("https://music.youtube.com/playlist?list=None"),
                    mapping("https://server/music/file.flac", "filesystem_local"),
                ),
            ).isEmpty(),
        )
    }
}

package io.music_assistant.client.ui.compose.home

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.music_assistant.client.api.APICommands
import io.music_assistant.client.api.Answer
import io.music_assistant.client.api.ConnectionInfo
import io.music_assistant.client.api.Request
import io.music_assistant.client.api.ServiceClient
import io.music_assistant.client.data.factory.MediaItemFactory
import io.music_assistant.client.data.factory.QueueFactory
import io.music_assistant.client.data.model.client.MediaType
import io.music_assistant.client.data.model.client.PlayerDataFixtures
import io.music_assistant.client.data.model.client.PlayerDataFixtures.toQueue
import io.music_assistant.client.data.model.client.QueueTrack
import io.music_assistant.client.data.model.client.items.AppMediaItem
import io.music_assistant.client.data.model.server.GrantedScopes
import io.music_assistant.client.data.model.server.Scope
import io.music_assistant.client.data.model.server.ServerInfo
import io.music_assistant.client.data.model.server.ServerMediaItem
import io.music_assistant.client.data.model.server.ServerProviderInstance
import io.music_assistant.client.data.model.server.ServerQueueItem
import io.music_assistant.client.data.model.server.ServerShareItem
import io.music_assistant.client.data.model.server.ServerShareLink
import io.music_assistant.client.data.repository.ProviderRepository
import io.music_assistant.client.data.repository.ShareRepository
import io.music_assistant.client.support.FakeServiceClient
import io.music_assistant.client.ui.compose.common.DataState
import io.music_assistant.client.ui.compose.common.LocalGrantedScopes
import io.music_assistant.client.ui.compose.common.PlayerColors
import io.music_assistant.client.ui.compose.common.action.QueueAction
import io.music_assistant.client.ui.compose.home.players.FullPlayerItem
import io.music_assistant.client.ui.compose.provider.ProviderViewModel
import io.music_assistant.client.ui.theme.AppTheme
import io.music_assistant.client.utils.ConnectionData
import io.music_assistant.client.utils.SessionState
import io.music_assistant.client.utils.myJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.test.KoinTestRule
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class QueueSharingTest {
    @get:Rule
    val koinRule = KoinTestRule.create {}

    @get:Rule
    val composeTestRule = createComposeRule()

    private val client = QueueShareClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scopes = mutableStateOf(GrantedScopes(setOf(Scope.LIBRARY_READ)))
    private val first = queueItem("Playing", "library://track/1")
    private val selected = queueItem("Selected", "library://track/2")
    private val queue = mutableStateOf(listOf(first, selected).toQueue())
    private val intents = mutableListOf<Intent>()
    private val actions = mutableListOf<QueueAction>()
    private val playlistItems = mutableListOf<AppMediaItem>()

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `touch long press shares a played row without playback or queue editing`() {
        val played = queueItem("Previously played", "library://track/0")
        queue.value = queue.value.copy(items = DataState.Data(listOf(played, first, selected)))
        client.response = { multipleLinks() }
        showQueue()

        composeTestRule.onNodeWithText("Previously played").assertIsDisplayed().performTouchInput { longClick() }
        composeTestRule.runOnIdle { assertTrue(actions.isEmpty(), "Long press dispatched $actions") }
        assertRequested("library://track/0")
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add to playlist").assertDoesNotExist()
        composeTestRule.onNodeWithText("Share").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Share YouTube Music link").assertIsDisplayed().performClick()
        assertPayload("https://example.com/youtube")
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    fun `touch taps play played and upcoming rows but not the current row`() {
        val played = queueItem("Previously played", "library://track/0")
        queue.value = queue.value.copy(items = DataState.Data(listOf(played, first, selected)))
        showQueue()

        composeTestRule.onNodeWithText("Previously played").performTouchInput { click() }
        composeTestRule.onNodeWithText("Playing").performTouchInput { click() }
        composeTestRule.onNodeWithText("Selected").performTouchInput { click() }
        composeTestRule.runOnIdle {
            assertEquals(
                listOf<QueueAction>(
                    QueueAction.PlayQueueItem(queue.value.info.id, played.id),
                    QueueAction.PlayQueueItem(queue.value.info.id, selected.id),
                ),
                actions,
            )
        }
        assertRequested()
        assertNoSharing()
    }

    @Test
    fun `touch long press shares a played station from the side queue`() {
        val station = queueItem("Played station", "radio_provider://radio/previous", "radio")
        queue.value = queue.value.copy(items = DataState.Data(listOf(station, first, selected)))
        showQueue(sideQueue = true)

        composeTestRule.onNodeWithText("Played station").assertIsDisplayed().performTouchInput { longClick() }
        composeTestRule.runOnIdle { assertTrue(actions.isEmpty()) }
        assertRequested("radio_provider://radio/previous")
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
        composeTestRule.onNodeWithText("Share").assertIsDisplayed().performClick()
        assertPayload("Server text for radio_provider://radio/previous")
        composeTestRule.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    fun `played rows without sharing permission or URI have no menu or playback on long press`() {
        val played = queueItem("Previously played", "library://track/0")
        queue.value = queue.value.copy(items = DataState.Data(listOf(played, first, selected)))
        scopes.value = GrantedScopes.NONE
        showQueue(shareDependencies = false, providerDependencies = false)

        composeTestRule.onNodeWithText("Previously played").performTouchInput { longClick() }
        assertNoSharing()
        composeTestRule.onNode(isPopup()).assertDoesNotExist()
        for (uri in listOf(null, "  ")) {
            composeTestRule.runOnIdle {
                scopes.value = GrantedScopes(setOf(Scope.LIBRARY_READ))
                queue.value = queue.value.copy(
                    items = DataState.Data(listOf(queueItem("Previously played", uri), first, selected)),
                )
            }
            composeTestRule.onNodeWithText("Previously played").performTouchInput { longClick() }
            assertNoSharing()
            composeTestRule.onNode(isPopup()).assertDoesNotExist()
        }
        assertRequested()
        composeTestRule.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    fun `playback advance removes current row edits without changing its share target`() {
        showQueue()
        composeTestRule.onNodeWithText("Playing").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Delete").assertIsEnabled()
        composeTestRule.onNodeWithText("Add to playlist").assertIsEnabled()

        composeTestRule.runOnIdle {
            queue.value = queue.value.copy(info = queue.value.info.copy(currentItem = selected))
        }
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add to playlist").assertDoesNotExist()
        composeTestRule.onNodeWithText("Share").performClick()
        assertRequested("library://track/1")
        assertPayload("Server text for library://track/1")
        composeTestRule.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    fun `selected queue item picker survives menu dismissal and shares one repository-named provider URL`() {
        client.response = { multipleLinks() }
        showQueue()
        composeTestRule.runOnIdle { assertTrue(client.requests.isEmpty()) }
        openMenu()
        assertRequested("library://track/2")
        composeTestRule.onNodeWithText("Share").performClick()
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
        composeTestRule.onNodeWithText("Share My Spotify link").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share YouTube Music link").assertIsDisplayed().performClick()
        assertPayload("https://example.com/youtube")
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `single provider URL shares directly from the queue menu`() {
        client.response = { ServerShareItem("Server title", "Ignore this text", multipleLinks().links.take(1)) }
        showQueue()
        openMenu()
        composeTestRule.onNodeWithText("Share").performClick()
        assertPayload("https://example.com/spotify")
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `local queue item shares only the server text`() {
        replaceSelected(queueItem("Selected", "filesystem://track/private.flac"))
        client.response = { ServerShareItem("Server title", "Song by Artist", emptyList()) }
        showQueue()
        openMenu()
        assertRequested("filesystem://track/private.flac")
        composeTestRule.onNodeWithText("Share").performClick()
        assertPayload("Song by Artist")
    }

    @Test
    fun `playlist and delete actions remain usable while share loads and cancel the request`() {
        val pending = CompletableDeferred<Unit>()
        client.beforeShare = { pending.await() }
        showQueue()
        openMenu()
        composeTestRule.onNodeWithText("Loading sharing options…").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Add to playlist").assertIsEnabled().performClick()
        composeTestRule.runOnIdle {
            assertEquals(selected.track as AppMediaItem, playlistItems.single())
            assertEquals(listOf("library://track/2"), client.cancelled)
        }
        openMenu()
        composeTestRule.onNodeWithText("Loading sharing options…").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Delete").assertIsEnabled().performClick()
        composeTestRule.runOnIdle {
            assertEquals<QueueAction>(
                QueueAction.RemoveItems(queue.value.info.id, listOf(selected.id)),
                actions.single(),
            )
            assertEquals(2, client.cancelled.size)
            pending.complete(Unit)
        }
        assertNoSharing()
    }

    @Test
    fun `failed sharing stays disabled without hiding other queue actions`() {
        client.response = { null }
        showQueue()
        openMenu()
        composeTestRule.onNodeWithText("Sharing unavailable").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Share").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add to playlist").assertIsEnabled()
        composeTestRule.onNodeWithText("Delete").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertTrue(intents.isEmpty()) }
    }

    @Test
    fun `denied and unresolved scopes hide sharing before resolving dependencies`() {
        scopes.value = GrantedScopes(setOf("players.control"))
        showQueue(shareDependencies = false, providerDependencies = false)
        openMenu()
        assertNoSharing()
        composeTestRule.runOnIdle { scopes.value = GrantedScopes.NONE }
        assertNoSharing()
        composeTestRule.onNodeWithText("Delete").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertTrue(client.requests.isEmpty()) }
    }

    @Test
    fun `old and unknown schema hide sharing without resolving provider dependencies`() {
        client.schema(99)
        showQueue(providerDependencies = false)
        openMenu()
        assertNoSharing()
        composeTestRule.runOnIdle { client.schema(null) }
        assertNoSharing()
        composeTestRule.onNodeWithText("Delete").assertIsEnabled()
        composeTestRule.runOnIdle { assertTrue(client.requests.isEmpty()) }
    }

    @Test
    fun `missing and blank media URI have no share action request or dependency lookup`() {
        replaceSelected(queueItem("Selected", null))
        showQueue(shareDependencies = false, providerDependencies = false)
        openMenu()
        assertNoSharing()
        composeTestRule.runOnIdle { replaceSelected(queueItem("Selected", "  ")) }
        assertNoSharing()
        composeTestRule.onNodeWithText("Delete").assertIsEnabled()
        composeTestRule.runOnIdle { assertTrue(client.requests.isEmpty()) }
    }

    @Test
    fun `changing the selected row cancels loading and never shares the old payload`() {
        val pending = CompletableDeferred<Unit>()
        client.beforeShare = { if (it == "library://track/2") pending.await() }
        showQueue()
        openMenu()
        composeTestRule.onNodeWithText("Loading sharing options…").assertIsNotEnabled()
        openMenu("Playing")
        composeTestRule.runOnIdle {
            assertEquals(listOf("library://track/2"), client.cancelled)
            pending.complete(Unit)
        }
        composeTestRule.onNodeWithText("Share").performClick()
        assertPayload("Server text for library://track/1")
        assertRequested("library://track/2", "library://track/1")
    }

    @Test
    fun `changing selected media URI cancels loading and discards its response`() {
        val pending = CompletableDeferred<Unit>()
        client.beforeShare = { if (it == "library://track/2") pending.await() }
        showQueue()
        openMenu()
        composeTestRule.onNodeWithText("Loading sharing options…").assertIsNotEnabled()
        composeTestRule.runOnIdle { replaceSelected(queueItem("Selected", "library://track/3")) }
        composeTestRule.onNodeWithText("Share").assertIsEnabled()
        composeTestRule.runOnIdle {
            assertEquals(listOf("library://track/2"), client.cancelled)
            pending.complete(Unit)
        }
        composeTestRule.onNodeWithText("Share").performClick()
        assertPayload("Server text for library://track/3")
        assertRequested("library://track/2", "library://track/3")
    }

    @Test
    fun `selected item removal closes its provider picker without sharing stale links`() {
        client.response = { multipleLinks() }
        showQueue()
        openMenu()
        composeTestRule.onNodeWithText("Share").performClick()
        composeTestRule.onNodeWithText("Share My Spotify link").assertIsDisplayed()
        composeTestRule.runOnIdle { queue.value = queue.value.copy(items = DataState.Data(listOf(first))) }
        composeTestRule.onNodeWithText("Share My Spotify link").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(intents.isEmpty()) }
    }

    @Test
    fun `revoking library scope cancels the queue share load`() {
        val pending = CompletableDeferred<Unit>()
        client.beforeShare = { pending.await() }
        showQueue()
        openMenu()
        composeTestRule.onNodeWithText("Loading sharing options…").assertIsNotEnabled()
        composeTestRule.runOnIdle { scopes.value = GrantedScopes.NONE }
        assertNoSharing()
        composeTestRule.runOnIdle {
            assertEquals(listOf("library://track/2"), client.cancelled)
            pending.complete(Unit)
        }
        composeTestRule.onNodeWithText("Delete").assertIsEnabled()
    }

    @Test
    fun `radio queue shares the station media URI while the player displays a different ICY song`() {
        val station = queueItem("Station", "radio_provider://radio/station", "radio")
        queue.value = listOf(station).toQueue()
        showQueue(icySong = "Different ICY Song")
        composeTestRule.onNodeWithContentDescription("Playing Different ICY Song").assertIsDisplayed()
        openMenu("Station")
        assertRequested("radio_provider://radio/station")
        composeTestRule.onNodeWithText("Share").performClick()
        assertPayload("Server text for radio_provider://radio/station")
    }

    private fun showQueue(
        shareDependencies: Boolean = true,
        providerDependencies: Boolean = true,
        icySong: String? = null,
        sideQueue: Boolean = false,
    ) {
        val dependencies = module {
            if (shareDependencies) single { ShareRepository(client) }
            if (providerDependencies) {
                single { ProviderRepository(client, scope) }
                viewModel { ProviderViewModel(client, get()) }
            }
        }
        koinRule.koin.loadModules(listOf(dependencies))
        composeTestRule.setContent {
            val baseContext = LocalContext.current
            val context = remember(baseContext) {
                object : ContextWrapper(baseContext) {
                    override fun startActivity(intent: Intent) { intents.add(intent) }
                }
            }
            CompositionLocalProvider(LocalContext provides context, LocalGrantedScopes provides scopes.value) {
                AppTheme {
                    Column(Modifier.fillMaxSize()) {
                        icySong?.let { title ->
                            val player = PlayerDataFixtures.playerData(queue.value)
                            val currentMedia = player.player.currentMedia?.copy(
                                title = title,
                                artist = "ICY Artist",
                                mediaType = MediaType.TRACK,
                                uri = "https://example.com/icy-song",
                                queueId = queue.value.info.id,
                                queueItemId = queue.value.info.currentItem?.id,
                            )
                            FullPlayerItem(
                                modifier = Modifier.weight(1f),
                                item = player.copy(player = player.player.copy(currentMedia = currentMedia)),
                                colors = PlayerColors(Color.Gray, Color.White),
                                playerAction = { _, _ -> },
                                onFavoriteChange = null,
                                livePositionFlow = null,
                            )
                        }
                        if (sideQueue) {
                            Queue(
                                modifier = Modifier.weight(1f),
                                queue = DataState.Data(queue.value),
                                isQueueExpanded = true,
                                isCurrentPage = true,
                                onGoToLibrary = {},
                                queueAction = { actions.add(it) },
                                onAddToPlaylist = { playlistItems.add(it) },
                                contentPadding = PaddingValues(),
                            )
                        } else {
                            CollapsibleQueue(
                                modifier = Modifier.weight(1f),
                                queue = DataState.Data(queue.value),
                                isQueueExpanded = true,
                                onQueueExpandedSwitch = {},
                                onGoToLibrary = {},
                                queueAction = { actions.add(it) },
                                onAddToPlaylist = { playlistItems.add(it) },
                                tint = Color.Gray,
                                contentPadding = PaddingValues(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openMenu(name: String = "Selected") {
        composeTestRule.onNodeWithText(name).performSemanticsAction(SemanticsActions.OnLongClick)
    }

    private fun assertNoSharing() {
        composeTestRule.onNodeWithText("Share").assertDoesNotExist()
        composeTestRule.onNodeWithText("Loading sharing options…").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sharing unavailable").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(intents.isEmpty()) }
    }

    private fun assertRequested(vararg uris: String) {
        composeTestRule.runOnIdle {
            assertEquals(uris.toList(), client.requests.map { it.args?.get("uri")?.jsonPrimitive?.content })
        }
    }

    private fun assertPayload(expected: String) {
        composeTestRule.runOnIdle {
            val chooser = intents.single()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = assertNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals("Server title", send.getStringExtra(Intent.EXTRA_SUBJECT))
        }
    }

    private fun replaceSelected(item: QueueTrack) {
        queue.value = queue.value.copy(items = DataState.Data(listOf(first, item)))
    }

    private fun queueItem(name: String, uri: String?, mediaType: String = "track"): QueueTrack {
        val mediaItem = ServerMediaItem(
            itemId = name,
            provider = "library",
            name = name,
            mediaType = mediaType,
            uri = uri,
            isPlayable = true,
        )
        return assertNotNull(QueueFactory(MediaItemFactory(client)).createTrack(ServerQueueItem(name, mediaItem)))
    }

    private fun multipleLinks() = ServerShareItem(
        "Server title",
        null,
        listOf(
            ServerShareLink("spotify", "spotify--account", "https://example.com/spotify"),
            ServerShareLink("ytmusic", "missing-instance", "https://example.com/youtube"),
        ),
    )

    private class QueueShareClient : ServiceClient by FakeServiceClient() {
        override val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
        override val isReadyForCommands = MutableStateFlow(true)
        val requests = mutableListOf<Request>()
        val cancelled = mutableListOf<String>()
        var response: (String) -> ServerShareItem? = { uri ->
            ServerShareItem("Server title", "Server text for $uri", emptyList())
        }
        var beforeShare: suspend (String) -> Unit = {}

        init {
            schema(100)
        }

        fun schema(version: Int?) {
            sessionState.value = SessionState.Connected.Direct(
                ConnectionInfo("localhost", 8095, false),
                ConnectionData(serverInfo = ServerInfo("test", schemaVersion = version)),
            )
        }

        override suspend fun sendRequest(request: Request): Result<Answer> = when (request.command) {
            APICommands.PROVIDERS -> {
                val providers = listOf(
                    ServerProviderInstance("spotify--account", "My Spotify", "spotify", "music"),
                    ServerProviderInstance("ytmusic--account", "YouTube Music", "ytmusic", "music"),
                )
                Result.success(Answer(JsonObject(mapOf("result" to myJson.encodeToJsonElement(providers)))))
            }
            "music/share_item" -> {
                requests.add(request)
                val uri = request.args?.get("uri")?.jsonPrimitive?.content.orEmpty()
                try {
                    beforeShare(uri)
                } catch (error: CancellationException) {
                    cancelled.add(uri)
                    throw error
                }
                val item = response(uri)
                Result.success(Answer(JsonObject(mapOf("result" to myJson.encodeToJsonElement(item)))))
            }
            else -> error("Unexpected request: ${request.command}")
        }
    }
}

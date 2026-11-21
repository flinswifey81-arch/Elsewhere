package com.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.screens.AutoScrollingChatColumn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ChatAutoScrollTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loadedSoloOrGroupHistoryOpensAtNewestActiveMessage() {
        composeTestRule.setContent {
            MaterialTheme {
                AutoScrollingChatColumn(
                    conversationKey = "existing-chat",
                    historyLoaded = true,
                    itemCount = MESSAGE_COUNT,
                    isStreaming = false,
                    streamContentKey = null,
                    modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT)
                ) {
                    items(MESSAGE_COUNT) { index -> messageRow(index) }
                }
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Message 79").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message 0").assertDoesNotExist()
    }

    @Test
    fun latestMessagePositionSignalsThatOldPagesMayBeReleased() {
        val callbackCount = java.util.concurrent.atomic.AtomicInteger(0)
        composeTestRule.setContent {
            MaterialTheme {
                AutoScrollingChatColumn(
                    conversationKey = "return-to-latest",
                    historyLoaded = true,
                    itemCount = MESSAGE_COUNT,
                    isStreaming = false,
                    streamContentKey = null,
                    onReturnedToLatest = { callbackCount.incrementAndGet() },
                    modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT)
                ) {
                    items(MESSAGE_COUNT) { index -> messageRow(index) }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Message 79").assertIsDisplayed()
        org.junit.Assert.assertTrue(callbackCount.get() > 0)
    }

    @Test
    fun streamingGrowthFollowsWhenReaderRemainsNearBottom() {
        val streamContent = mutableStateOf<String?>(null)
        setChatList(streamContent)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { streamContent.value = "first chunk" }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Stream end: first chunk").assertIsDisplayed()

        composeTestRule.runOnIdle { streamContent.value = "second chunk" }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Stream end: second chunk").assertIsDisplayed()
    }

    @Test
    fun streamingDoesNotYankReaderBackAfterDeliberateScrollUp() {
        val streamContent = mutableStateOf<String?>(null)
        setChatList(streamContent)
        composeTestRule.waitForIdle()

        repeat(3) {
            composeTestRule.onNode(hasScrollAction()).performTouchInput {
                swipeDown(durationMillis = 300)
            }
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithText("Message 79").assertDoesNotExist()

        composeTestRule.runOnIdle { streamContent.value = "new chunk" }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Stream end: new chunk").assertDoesNotExist()
        composeTestRule.onNodeWithText("Message 79").assertDoesNotExist()
    }

    @Test
    fun olderHistoryCanBeLoadedWithoutJumpingBackToLatestReply() {
        val loadedEarlier = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                val loaded = loadedEarlier.value
                val ids = if (loaded) (20..179).toList() else (100..179).toList()
                AutoScrollingChatColumn(
                    conversationKey = "long-chat",
                    historyLoaded = true,
                    itemCount = ids.size + if (loaded) 0 else 1,
                    isStreaming = false,
                    streamContentKey = null,
                    hasEarlierHistory = !loaded,
                    onLoadEarlier = { loadedEarlier.value = true },
                    modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT)
                ) {
                    if (!loaded) {
                        item(key = "load-earlier") {
                            TextButton(onClick = { loadedEarlier.value = true }) {
                                Text("Load earlier messages")
                            }
                        }
                    }
                    items(ids, key = { it }) { id -> messageRow(id) }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Message 179").assertIsDisplayed()

        // A user who scrolls toward the top either triggers the automatic
        // edge callback or can explicitly press the accessible load control.
        // LazyColumn doesn't compose the distant header until its index
        // enters the viewport. Scroll the list itself to index zero, then
        // invoke the accessible button if auto-pagination hasn't fired.
        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(0)
        composeTestRule.waitForIdle()
        if (!loadedEarlier.value) {
            composeTestRule.onNodeWithText("Load earlier messages").performClick()
        }
        composeTestRule.waitForIdle()
        org.junit.Assert.assertTrue(loadedEarlier.value)
        composeTestRule.onNodeWithText("Message 179").assertDoesNotExist()
    }

    private fun setChatList(streamContent: MutableState<String?>) {
        composeTestRule.setContent {
            MaterialTheme {
                val currentStream = streamContent.value
                AutoScrollingChatColumn(
                    conversationKey = "streaming-chat",
                    historyLoaded = true,
                    itemCount = MESSAGE_COUNT + if (currentStream == null) 0 else 1,
                    isStreaming = currentStream != null,
                    streamContentKey = currentStream,
                    modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT)
                ) {
                    items(MESSAGE_COUNT) { index -> messageRow(index) }
                    if (currentStream != null) {
                        item(key = "stream") {
                            val streamHeight = if (currentStream == "first chunk") 640.dp else 960.dp
                            Column(
                                modifier = Modifier.fillMaxWidth().height(streamHeight),
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Text("Stream end: $currentStream")
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MESSAGE_COUNT = 80
        val LIST_HEIGHT = 320.dp
    }
}

@Composable
private fun messageRow(index: Int) {
    Text(
        text = "Message $index",
        modifier = Modifier.fillMaxWidth().height(48.dp)
    )
}

package com.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

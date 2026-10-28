package ai.kodif.chat

import androidx.core.content.FileProvider

/** Shares photos taken for the chat with the camera app. Its own class so it can't clash with the app's provider. */
internal class KodifChatFileProvider : FileProvider()

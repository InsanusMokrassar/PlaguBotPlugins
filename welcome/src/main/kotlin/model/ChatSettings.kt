package dev.inmo.plagubot.plugins.welcome.model

import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageIdentifier
import kotlinx.serialization.Serializable

@Serializable
internal data class ChatSettings(
    val targetChatId: ChatId,
    val sourceChatId: ChatId,
    val sourceMessageId: MessageIdentifier
)

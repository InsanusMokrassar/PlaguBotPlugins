package dev.inmo.plagubot.plugins.common

import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.ChatIdWithThreadId
import dev.inmo.tgbotapi.types.Identifier
import dev.inmo.tgbotapi.types.MessageThreadId

inline fun IdChatIdentifier(
    chatId: Identifier,
    threadId: MessageThreadId?
) = threadId ?.let {
    ChatIdWithThreadId(chatId, threadId)
} ?: ChatId(chatId)

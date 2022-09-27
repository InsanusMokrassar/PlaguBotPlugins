package dev.inmo.plagubot.plugins.inline.buttons.utils

import dev.inmo.tgbotapi.extensions.utils.types.buttons.InlineKeyboardRowBuilder
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.types.ChatId

fun extractChatIdAndData(data: String): Pair<ChatId, String> {
    val (chatIdString, valuableData) = data.split(" ")
    val chatId = ChatId(chatIdString.toLong())
    return chatId to valuableData
}

fun createInlineButtonData(chatId: ChatId, data: String) = "${chatId.chatId} $data"

fun InlineKeyboardRowBuilder.inlineDataButton(text: String, chatId: ChatId, data: String) = dataButton(
    text,
    createInlineButtonData(chatId, data)
)

package dev.inmo.plagubot.plugins.inline.buttons.utils

import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.tgbotapi.extensions.utils.types.buttons.InlineKeyboardBuilder
import dev.inmo.tgbotapi.extensions.utils.types.buttons.InlineKeyboardRowBuilder
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.toChatId
import dev.inmo.tgbotapi.utils.row

fun extractChatIdAndDataAndThread(data: String): Triple<IdChatIdentifier, MessageThreadId, String>? {
    return runCatching {
        val (chatIdString, messageThreadId, valuableData) = data.split(" ")
        val chatId = chatIdString.toLong().toChatId()
        return Triple(
            chatId,
            messageThreadId.toLongOrNull() ?.let(::MessageThreadId) ?: return@runCatching null,
            valuableData
        )
    }.getOrNull()
}

fun extractChatIdAndData(data: String): Pair<IdChatIdentifier, String>? {
    return runCatching {
        val (chatIdString, valuableData) = data.split(" ")
        val chatId = chatIdString.toLong().toChatId()
        return chatId to valuableData
    }.getOrNull()
}

fun createChatIdAndDataInlineButtonData(chatId: IdChatIdentifier, data: String) = "${chatId.chatId} $data"

fun InlineKeyboardRowBuilder.inlineDataButton(text: String, chatId: IdChatIdentifier, data: String) = dataButton(
    text,
    createChatIdAndDataInlineButtonData(chatId, data)
)

fun InlineKeyboardRowBuilder.drawerDataButton(drawer: InlineButtonsDrawer, chatId: IdChatIdentifier) = dataButton(
    drawer.name,
    createChatIdAndDataInlineButtonData(chatId, drawer.id)
)

fun InlineKeyboardBuilder.drawerDataButtonRow(drawer: InlineButtonsDrawer, chatId: IdChatIdentifier) = row {
 drawerDataButton(drawer, chatId)
}

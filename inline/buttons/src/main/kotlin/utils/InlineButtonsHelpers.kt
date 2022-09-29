package dev.inmo.plagubot.plugins.inline.buttons.utils

import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.types.ChatId

fun extractChatIdAndData(data: String): Pair<ChatId, String>? {
    return runCatching {
        val (chatIdString, valuableData) = data.split(" ")
        val chatId = ChatId(chatIdString.toLong())
        return chatId to valuableData
    }.getOrNull()
}

fun createChatIdAndDataInlineButtonData(chatId: ChatId, data: String) = "${chatId.chatId} $data"

fun InlineKeyboardRowBuilder.inlineDataButton(text: String, chatId: ChatId, data: String) = dataButton(
    text,
    createChatIdAndDataInlineButtonData(chatId, data)
)

fun InlineKeyboardRowBuilder.drawerDataButton(drawer: InlineButtonsDrawer, chatId: ChatId) = dataButton(
    drawer.name,
    createChatIdAndDataInlineButtonData(chatId, drawer.id)
)

fun InlineKeyboardBuilder.drawerDataButtonRow(drawer: InlineButtonsDrawer, chatId: ChatId) = row {
    drawerDataButton(drawer, chatId)
}

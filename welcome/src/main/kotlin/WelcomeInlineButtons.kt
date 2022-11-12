package dev.inmo.plagubot.plugins.welcome

import dev.inmo.micro_utils.coroutines.runCatchingSafely
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.plagubot.plugins.inline.buttons.utils.*
import dev.inmo.plagubot.plugins.welcome.db.WelcomeTable
import dev.inmo.plagubot.plugins.welcome.model.ChatSettings
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.extensions.sameChat
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.extensions.utils.withContentOrNull
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.libraries.cache.admins.doIfAdmin
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.InlineKeyboardButton
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.utils.RowBuilder
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.koin.core.Koin

internal class WelcomeInlineButtons(
    private val backDrawer: InlineButtonsDrawer,
    private val welcomeTable: WelcomeTable
) : InlineButtonsDrawer {
    override val name: String
        get() = "Welcome"
    override val id: String
        get() = "welcome"

    override suspend fun BehaviourContext.drawInlineButtons(
        chatId: IdChatIdentifier,
        userId: UserId,
        messageId: MessageId,
        key: String?
    ) {
        edit(
            userId,
            messageId,
            replyMarkup = inlineKeyboard {
                val currentMessageInfo = welcomeTable.get(chatId)
                row {
                    inlineDataButton("Set new", chatId, setMessageData)
                    inlineDataButton("Unset", chatId, unsetMessageData)
                }
                if (currentMessageInfo != null) {
                    row {
                        inlineDataButton("Get message", chatId, getMessageData)
                    }
                }
                row {
                    inlineDataButton("Back", chatId, backDrawer.id)
                }
            }
        )
    }

    override suspend fun BehaviourContext.setupReactions(koin: Koin) {
        val adminsCacheApi = koin.get<AdminsCacheAPI>()

        onMessageDataCallbackQuery {
            val (chatId, data) = extractChatIdAndData(it.data) ?: return@onMessageDataCallbackQuery

            if (data !in welcomeData) {
                return@onMessageDataCallbackQuery
            }

            adminsCacheApi.doIfAdmin(chatId, it.user.id) {
                when (data) {
                    getMessageData -> {
                        welcomeTable.get(chatId) ?.let { settings ->
                            reply(
                                it.message,
                                fromChatId = settings.sourceChatId,
                                messageId = settings.sourceMessageId
                            )
                        } ?: let { _ ->
                            reply(it.message) {
                                +"Currently welcome message is not set"
                            }
                        }
                        answer(it)
                    }
                    setMessageData -> {
                        answer(it)

                        val sent = send(
                            it.user.id,
                            "Ok, send me new welcome message or /cancel"
                        )

                        val sentByUser = waitContentMessage().filter {
                            it.sameChat(sent)
                        }.first()

                        sentByUser.withContentOrNull<TextContent>() ?.let {
                            if (it.content.text == "/cancel") {
                                edit(sent, "Set request has been cancelled")
                                return@onMessageDataCallbackQuery
                            }
                        }

                        val success = welcomeTable.set(
                            ChatSettings(
                                chatId,
                                sentByUser.chat.id,
                                sentByUser.messageId
                            )
                        )
                        drawInlineButtons(chatId, it.user.id, it.message.messageId, InlineButtonsKeys.Settings)
                        edit(sent) {
                            if (success) {
                                +"Set request has been cancelled"
                            } else {
                                +"For some reason I am unable to "
                            }
                        }
                    }
                    unsetMessageData -> {
                        val deletedSettings = welcomeTable.unset(chatId)

                        reply(it.message) {
                            if (deletedSettings != null) {
                                +"Set request has been cancelled"
                            } else {
                                +"For some reason I am unable to "
                            }
                        }

                        drawInlineButtons(chatId, it.user.id, it.message.messageId, InlineButtonsKeys.Settings)

                        deletedSettings ?.let {
                            runCatchingSafely {
                                copyMessage(
                                    it.targetChatId,
                                    it.sourceChatId,
                                    it.sourceMessageId
                                )
                            }
                        }

                        answer(it)
                    }
                }
                Unit
            }
        }
    }

    companion object {
        private const val welcomePrefix = "welcome"
        private const val getMessageData = "${welcomePrefix}_gm"
        private const val setMessageData = "${welcomePrefix}_s"
        private const val unsetMessageData = "${welcomePrefix}_us"

        private val welcomeData = arrayOf(getMessageData, setMessageData, unsetMessageData)
    }
}

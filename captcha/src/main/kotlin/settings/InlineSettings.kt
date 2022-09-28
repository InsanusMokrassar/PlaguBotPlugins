package dev.inmo.plagubot.plugins.captcha.settings

import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.extensions.sameMessage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import kotlinx.coroutines.flow.*
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.update
import org.koin.core.Koin

class InlineSettings(
    private val backDrawer: InlineButtonsDrawer,
    private val chatsSettingsRepo: CaptchaChatsSettingsRepo,
    stringFormat: StringFormat,
    database: Database
) : InlineButtonsDrawer {
    override val name: String
        get() = "Captcha"
    override val id: String
        get() = "captcha"

    private val internalRepo = ExposedKeyValueRepo(
        database,
        { text("messageInfo") },
        { long("chatId") },
        "captcha_inline_settings_associations"
    ).let {
        val pairSerializer = PairSerializer(UserId.serializer(), MessageId.serializer())

        it.withMapper<Pair<UserId, MessageId>, ChatId, String, Long>(
            { stringFormat.encodeToString(pairSerializer, this) },
            { chatId },
            { stringFormat.decodeFromString(pairSerializer, this) },
            { ChatId(this) },
        )
    }

    override suspend fun BehaviourContext.drawInlineButtons(
        chatId: ChatId,
        userId: UserId,
        messageId: MessageId,
        key: String?
    ) {
        var chatSettings = chatsSettingsRepo.getById(chatId) ?: ChatSettings(chatId)
        val editedMessage = edit(
            userId,
            messageId,
            replyMarkup = inlineKeyboard {
                if (chatSettings.enabled) {
                    row {
                        dataButton("Enabled", disableData)
                    }
                    listOf(
                        CallbackDataInlineKeyboardButton(
                            "Remove events${if (chatSettings.autoRemoveEvents) successfulSymbol else unsuccessfulSymbol}",
                            if (chatSettings.autoRemoveEvents) {
                                disableAutoRemoveEventsData
                            } else {
                                enableAutoRemoveEventsData
                            }
                        ),
                        CallbackDataInlineKeyboardButton(
                            "Remove commands${if (chatSettings.autoRemoveCommands) successfulSymbol else unsuccessfulSymbol}",
                            if (chatSettings.autoRemoveCommands) {
                                disableAutoRemoveCommandsData
                            } else {
                                enableAutoRemoveCommandsData
                            }
                        ),
                        CallbackDataInlineKeyboardButton(
                            "Kick${if (chatSettings.kickOnUnsuccess) successfulSymbol else unsuccessfulSymbol}",
                            if (chatSettings.kickOnUnsuccess) {
                                disableKickOnUnsuccessData
                            } else {
                                enableKickOnUnsuccessData
                            }
                        ),
                        CallbackDataInlineKeyboardButton(
                            "CAS${if (chatSettings.kickOnUnsuccess) successfulSymbol else unsuccessfulSymbol}",
                            if (chatSettings.casEnabled) {
                                disableCASData
                            } else {
                                enableCASData
                            }
                        ),
                        CallbackDataInlineKeyboardButton(
                            backDrawer.name,
                            backDrawer.id
                        )
                    ).chunked(2).forEach(::add)
                } else {
                    row {
                        dataButton("Enabled", enableData)
                    }
                }
            }
        )

        internalRepo.set(userId to messageId, chatId)
    }

    suspend fun BehaviourContext.setupListeners() {
        suspend fun defaultListener(
            data: String,
            onTrigger: ChatSettings.() -> ChatSettings
        ) {
            onMessageDataCallbackQuery(initialFilter = { it.data == data }) {
                val chatId = internalRepo.get(it.message.chat.id to it.message.messageId) ?: return@onMessageDataCallbackQuery

                val chatSettings = chatsSettingsRepo.getById(chatId)

                val newChatSettings = onTrigger(chatSettings ?: ChatSettings(chatId))

                if (chatSettings == null) {
                    chatsSettingsRepo.create(newChatSettings)
                } else {
                    chatsSettingsRepo.update(chatId, newChatSettings)
                }
            }
        }

        defaultListener(enableData) {
            copy(enabled = true)
        }
        defaultListener(disableData) {
            copy(enabled = false)
        }

        defaultListener(enableAutoRemoveEventsData) {
            copy(autoRemoveEvents = true)
        }
        defaultListener(disableAutoRemoveEventsData) {
            copy(autoRemoveEvents = false)
        }

        defaultListener(enableAutoRemoveCommandsData) {
            copy(autoRemoveCommands = true)
        }
        defaultListener(disableAutoRemoveCommandsData) {
            copy(autoRemoveCommands = false)
        }

        defaultListener(enableKickOnUnsuccessData) {
            copy(kickOnUnsuccess = true)
        }
        defaultListener(disableKickOnUnsuccessData) {
            copy(kickOnUnsuccess = false)
        }

        defaultListener(enableCASData) {
            copy(casEnabled = true)
        }
        defaultListener(disableCASData) {
            copy(casEnabled = false)
        }

        onMessageDataCallbackQuery(initialFilter = { it.data == backDrawer.id }) {
            val key = it.message.chat.id to it.message.messageId
            val chatId = internalRepo.get(key) ?: return@onMessageDataCallbackQuery

            internalRepo.unset(key)

            with(backDrawer) {
                drawInlineButtons(chatId, it.message.chat.id, it.message.messageId)
            }
        }
    }

    companion object {
        private const val captchaPrefix = "captcha_"

        private const val enableData = "${captchaPrefix}_enable"
        private const val disableData = "${captchaPrefix}_disable"

        private const val enableAutoRemoveEventsData = "${captchaPrefix}_rm_e_e"
        private const val disableAutoRemoveEventsData = "${captchaPrefix}_rm_e_d"

        private const val enableAutoRemoveCommandsData = "${captchaPrefix}_rm_c_e"
        private const val disableAutoRemoveCommandsData = "${captchaPrefix}_rm_c_d"

        private const val enableKickOnUnsuccessData = "${captchaPrefix}_kick_e"
        private const val disableKickOnUnsuccessData = "${captchaPrefix}_kick_d"

        private const val enableCASData = "${captchaPrefix}_cas"
        private const val disableCASData = "${captchaPrefix}_cas"

        private const val successfulSymbol = "✅"
        private const val unsuccessfulSymbol = "❌"
    }
}

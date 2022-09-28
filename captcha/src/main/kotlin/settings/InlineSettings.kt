package dev.inmo.plagubot.plugins.captcha.settings

import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.plagubot.plugins.inline.buttons.utils.*
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.Database

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
        val chatSettings = chatsSettingsRepo.getById(chatId) ?: ChatSettings(chatId)
        edit(
            userId,
            messageId,
            replyMarkup = inlineKeyboard {
                if (chatSettings.enabled) {
                    row {
                        dataButton("Enabled$successfulSymbol", disableData)
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
                            "CAS${if (chatSettings.casEnabled) successfulSymbol else unsuccessfulSymbol}",
                            if (chatSettings.casEnabled) {
                                disableCASData
                            } else {
                                enableCASData
                            }
                        )
                    ).chunked(2).forEach(::add)
                } else {
                    row {
                        dataButton("Enabled$unsuccessfulSymbol", enableData)
                    }
                }
                row {
                    drawerDataButton(backDrawer, chatId)
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

                drawInlineButtons(chatId, it.message.chat.id, it.message.messageId, InlineButtonsKeys.Settings)
                answer(it)
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

        onMessageDataCallbackQuery {
            val (_, data) = extractChatIdAndData(it.data) ?: return@onMessageDataCallbackQuery
            if (data == backDrawer.id) {
                internalRepo.unset(it.message.chat.id to it.message.messageId)
            }
            answer(it)
        }
    }

    companion object {
        private const val captchaPrefix = "captcha_"
        private const val captchaEnablePrefix = "${captchaPrefix}e"
        private const val captchaDisablePrefix = "${captchaPrefix}d"

        private const val enableData = captchaEnablePrefix
        private const val disableData = captchaDisablePrefix

        private const val enableAutoRemoveEventsData = "${captchaEnablePrefix}_rm_e"
        private const val disableAutoRemoveEventsData = "${captchaDisablePrefix}_rm_e"

        private const val enableAutoRemoveCommandsData = "${captchaEnablePrefix}_rm_c"
        private const val disableAutoRemoveCommandsData = "${captchaDisablePrefix}_rm_c"

        private const val enableKickOnUnsuccessData = "${captchaEnablePrefix}_kick"
        private const val disableKickOnUnsuccessData = "${captchaDisablePrefix}_kick"

        private const val enableCASData = "${captchaEnablePrefix}_cas"
        private const val disableCASData = "${captchaDisablePrefix}_cas"

        private const val successfulSymbol = "✅"
        private const val unsuccessfulSymbol = "❌"
    }
}

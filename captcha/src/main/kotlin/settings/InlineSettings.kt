package dev.inmo.plagubot.plugins.captcha.settings

import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.AbstractExposedKeyValueRepo
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.micro_utils.repos.exposed.onetomany.AbstractExposedKeyValuesRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.captcha.provider.*
import dev.inmo.plagubot.plugins.common.IdChatIdentifier
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.plagubot.plugins.inline.buttons.utils.*
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.delete
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.extensions.sameChat
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.InlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.flow.*
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ISqlExpressionBuilder
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateBuilder
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

    private class InternalRepo(
        database: Database,
        private val stringFormat: StringFormat
    ) : AbstractExposedKeyValueRepo<Pair<UserId, MessageId>, IdChatIdentifier>(
        database,
        "captcha_inline_settings_associations"
    ) {
        val pairSerializer = PairSerializer(UserId.serializer(), MessageId.serializer())

        override val keyColumn = text("messageInfo")
        private val chatIdColumn = long("chatId")
        private val threadIdColumn = long("threadId").nullable().default(null)
        override val selectById: ISqlExpressionBuilder.(Pair<UserId, MessageId>) -> Op<Boolean> = {
            keyColumn.eq(stringFormat.encodeToString(pairSerializer, it))
        }
        override val selectByValue: ISqlExpressionBuilder.(IdChatIdentifier) -> Op<Boolean> = {
            chatIdColumn.eq(it.chatId).and(threadIdColumn.eq(it.threadId))
        }
        override val ResultRow.asKey: Pair<UserId, MessageId>
            get() = stringFormat.decodeFromString(pairSerializer, get(keyColumn))
        override val ResultRow.asObject: IdChatIdentifier
            get() = IdChatIdentifier(
                get(chatIdColumn),
                get(threadIdColumn)
            )

        override fun update(
            k: Pair<UserId, MessageId>,
            v: IdChatIdentifier,
            it: UpdateBuilder<Int>
        ) {
            it[chatIdColumn] = v.chatId
            it[threadIdColumn] = v.threadId
        }

        override fun insertKey(k: Pair<UserId, MessageId>, v: IdChatIdentifier, it: InsertStatement<Number>) {
            it[keyColumn] = stringFormat.encodeToString(pairSerializer, k)
        }

    }

    private val internalRepo = InternalRepo(database, stringFormat)

    override suspend fun BehaviourContext.drawInlineButtons(
        chatId: IdChatIdentifier,
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
                    row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                        dataButton("Enabled$successfulSymbol", disableData)
                    })
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
                        ),
                        CallbackDataInlineKeyboardButton(
                            "Provider settings",
                            providerSettingsData
                        )
                    ).chunked(2).forEach(::add)
                } else {
                    row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                        dataButton("Enabled$unsuccessfulSymbol", enableData)
                    })
                }
                drawerDataButtonRow(backDrawer, chatId)
            }
        )

        internalRepo.set(userId to messageId, chatId)
    }

    private fun drawProviderSettings(
        it: ChatSettings
    ): InlineKeyboardMarkup {
        return inlineKeyboard {
            row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                dataButton("Use expressions${successfulSymbol.takeIf { _ -> it.captchaProvider is ExpressionCaptchaProvider } ?: ""}",
                    useExpressionData)
            })
            row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                dataButton("Use slots${successfulSymbol.takeIf { _ -> it.captchaProvider is SlotMachineCaptchaProvider } ?: ""}",
                    useSlotsData)
            })
            row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                dataButton("Use simple button${successfulSymbol.takeIf { _ -> it.captchaProvider is SimpleCaptchaProvider } ?: ""}",
                    useSimpleData)
            })
            row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                dataButton("Captcha time: ${it.captchaProvider.checkTimeSpan.seconds} seconds", providerCaptchaTimeData)
            })
            when (val provider = it.captchaProvider) {
                is ExpressionCaptchaProvider -> {
                    row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                        dataButton("Operand max: ${provider.maxPerNumber}", expressionOperandMaxData)
                        dataButton("Operations: ${provider.operations}", expressionOperationsData)
                    })
                    row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                        dataButton("Answers: ${provider.answers}", expressionAnswersData)
                        dataButton("Attempts: ${provider.attempts}", expressionAttemptsData)
                    })
                }

                is SimpleCaptchaProvider -> {}
                is SlotMachineCaptchaProvider -> {}
            }
            row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                inlineDataButton("Back", it.chatId, id)
            })
        }
    }

    override suspend fun BehaviourContext.setupReactions(koin: Koin) {
        suspend fun defaultListener(
            data: String,
            onComplete: suspend BehaviourContext.(ChatSettings, MessageDataCallbackQuery) -> Unit = { it, query ->
                drawInlineButtons(it.chatId, query.message.chat.id, query.message.messageId, InlineButtonsKeys.Settings)
            },
            onTrigger: suspend ChatSettings.() -> ChatSettings
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

                onComplete(newChatSettings, it)

                answer(it)
            }
        }
        suspend fun defaultSubmenuListener(
            data: String,
            onTrigger: (ChatSettings) -> InlineKeyboardMarkup
        ) {
            onMessageDataCallbackQuery(initialFilter = { it.data == data }) {
                val chatId = internalRepo.get(it.message.chat.id to it.message.messageId) ?: return@onMessageDataCallbackQuery

                val chatSettings = chatsSettingsRepo.getById(chatId)

                edit(it.message, replyMarkup = onTrigger(chatSettings ?: ChatSettings(chatId)))

                answer(it)
            }
        }
        suspend fun defaultProviderNumberEditListener(
            data: String,
            title: String,
            minMax: IntRange? = null,
            onComplete: suspend BehaviourContext.(ChatSettings, MessageDataCallbackQuery) -> Unit = { it, query ->
                edit(
                    query.message,
                    replyMarkup = drawProviderSettings(it)
                )
            },
            onTrigger: suspend ChatSettings.(Int) -> ChatSettings
        ) {
            onMessageDataCallbackQuery(initialFilter = { it.data == data }) {
                val chatId = internalRepo.get(it.message.chat.id to it.message.messageId) ?: return@onMessageDataCallbackQuery

                val chatSettings = chatsSettingsRepo.getById(chatId)

                val shouldMessage by lazy {
                    "$title: You should type number${if (minMax == null) "" else " in range $minMax"} or use /cancel"
                }

                val sentMessage = reply(it.message) {
                    +"$title: Type number${if (minMax == null) "" else " in range $minMax"} or use /cancel"
                }

                answer(it)

                val newAmount = waitTextMessage().filter { message ->
                    message.sameChat(sentMessage) && (
                        message.content.text.let {
                            it == "/cancel" || (it.toIntOrNull() != null).also {
                                if (!it) {
                                    reply(message, shouldMessage)
                                }
                            }
                        }
                        )
                }.map { message ->
                    message.content.text.toIntOrNull()
                }.first()

                if (newAmount == null) {
                    delete(sentMessage)
                    return@onMessageDataCallbackQuery
                }

                val newChatSettings = onTrigger(chatSettings ?: ChatSettings(chatId), newAmount)

                if (chatSettings == null) {
                    chatsSettingsRepo.create(newChatSettings)
                } else {
                    chatsSettingsRepo.update(chatId, newChatSettings)
                }

                onComplete(newChatSettings, it)

                edit(sentMessage) {
                    +"Edition done with $newAmount"
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

        defaultListener(
            useExpressionData,
            { settings, query ->
                edit(
                    query.message,
                    replyMarkup = drawProviderSettings(settings)
                )
            }
        ) {
            copy(captchaProvider = ExpressionCaptchaProvider())
        }
        defaultListener(
            useSlotsData,
            { settings, query ->
                edit(
                    query.message,
                    replyMarkup = drawProviderSettings(settings)
                )
            }
        ) {
            copy(captchaProvider = SlotMachineCaptchaProvider())
        }
        defaultListener(
            useSimpleData,
            { settings, query ->
                edit(
                    query.message,
                    replyMarkup = drawProviderSettings(settings)
                )
            }
        ) {
            copy(captchaProvider = SimpleCaptchaProvider())
        }

        defaultProviderNumberEditListener(
            providerCaptchaTimeData,
            "Captcha solution time (in seconds)",
            15 .. 300
        ) {
            copy(
                captchaProvider = when (captchaProvider) {
                    is ExpressionCaptchaProvider -> captchaProvider.copy(
                        checkTimeSeconds = it
                    )
                    is SimpleCaptchaProvider -> captchaProvider.copy(
                        checkTimeSeconds = it
                    )
                    is SlotMachineCaptchaProvider -> captchaProvider.copy(
                        checkTimeSeconds = it
                    )
                }
            )
        }

        defaultProviderNumberEditListener(
            expressionOperandMaxData,
            "Max operand value",
            1 .. 1000
        ) {
            copy(
                captchaProvider = when (captchaProvider) {
                    is ExpressionCaptchaProvider -> captchaProvider.copy(
                        maxPerNumber = it
                    )
                    is SimpleCaptchaProvider -> captchaProvider
                    is SlotMachineCaptchaProvider -> captchaProvider
                }
            )
        }

        defaultProviderNumberEditListener(
            expressionOperationsData,
            "Operations amount",
            2 .. 10
        ) {
            copy(
                captchaProvider = when (captchaProvider) {
                    is ExpressionCaptchaProvider -> captchaProvider.copy(
                        operations = it
                    )
                    is SimpleCaptchaProvider -> captchaProvider
                    is SlotMachineCaptchaProvider -> captchaProvider
                }
            )
        }

        defaultProviderNumberEditListener(
            expressionAnswersData,
            "Presented answers amount",
            1 .. 10
        ) {
            copy(
                captchaProvider = when (captchaProvider) {
                    is ExpressionCaptchaProvider -> captchaProvider.copy(
                        answers = it
                    )
                    is SimpleCaptchaProvider -> captchaProvider
                    is SlotMachineCaptchaProvider -> captchaProvider
                }
            )
        }

        defaultProviderNumberEditListener(
            expressionAttemptsData,
            "Available attempts amount",
            1 .. 10
        ) {
            copy(
                captchaProvider = when (captchaProvider) {
                    is ExpressionCaptchaProvider -> captchaProvider.copy(
                        attempts = it
                    )
                    is SimpleCaptchaProvider -> captchaProvider
                    is SlotMachineCaptchaProvider -> captchaProvider
                }
            )
        }

        defaultSubmenuListener(providerSettingsData, ::drawProviderSettings)

        onMessageDataCallbackQuery {
            val (_, data) = extractChatIdAndData(it.data) ?: return@onMessageDataCallbackQuery
            if (data == backDrawer.id) {
                internalRepo.unset(it.message.chat.id to it.message.messageId)
            }
            answer(it)
        }
    }

    companion object {
        private const val captchaPrefix = "captcha"
        private const val captchaEnablePrefix = "${captchaPrefix}_e"
        private const val captchaDisablePrefix = "${captchaPrefix}_d"

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

        private const val providersPrefix = "${captchaPrefix}_p"
        private const val providerSettingsData = "${providersPrefix}_sp"
        private const val useExpressionData = "${providerSettingsData}_e"
        private const val useSlotsData = "${providerSettingsData}_sl"
        private const val useSimpleData = "${providerSettingsData}_s"

        private const val providerCaptchaTimeData = "${providersPrefix}_ct"

        private const val expressionOperandMaxData = "${useExpressionData}_om"
        private const val expressionOperationsData = "${useExpressionData}_o"
        private const val expressionAnswersData = "${useExpressionData}_an"
        private const val expressionAttemptsData = "${useExpressionData}_a"

        private const val successfulSymbol = "✅"
        private const val unsuccessfulSymbol = "❌"
    }
}

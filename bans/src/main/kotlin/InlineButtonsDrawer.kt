package dev.inmo.plagubot.plugins.bans

import dev.inmo.micro_utils.coroutines.runCatchingSafely
import dev.inmo.micro_utils.repos.set
import dev.inmo.plagubot.plugins.bans.db.ChatsSettingsTable
import dev.inmo.plagubot.plugins.bans.models.ChatSettings
import dev.inmo.plagubot.plugins.bans.models.WorkMode
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.plagubot.plugins.inline.buttons.utils.*
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.InlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.BotCommandTextSource
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.botCommand
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.flow.*
import org.koin.core.Koin

internal class BansInlineButtonsDrawer(
    private val backDrawer: InlineButtonsDrawer,
    private val adminsApi: AdminsCacheAPI,
    private val chatsSettings: ChatsSettingsTable
) : InlineButtonsDrawer {
    override val name: String
        get() = "BanPlugin"
    override val id: String
        get() = "BanPlugin"
    val Boolean.enabledSymbol
        get() = if (this) {
            "✅"
        } else {
            "❌"
        }
    private val banDataPrefix = "ban_"
    private val toggleForUserData = "${banDataPrefix}userToggle"
    private val toggleForAdminData = "${banDataPrefix}adminToggle"
    private val allowWarnAdminsData = "${banDataPrefix}allowWarnAdmins"
    private val warnsCountData = "${banDataPrefix}warns"


    internal suspend fun BehaviourContext.performMessageDataCallbackQuery(
        query: MessageDataCallbackQuery
    ): Boolean {
        val (chatId, data) = extractChatIdAndData(query.data) ?.takeIf { it.second.startsWith(banDataPrefix) } ?: return false
        val userId = query.user.id

        if (!adminsApi.isAdmin(chatId, userId)) {
            return false
        }

        var needNewMessage = false

        val settings = chatsSettings.get(chatId) ?: ChatSettings()

        chatsSettings.set(
            chatId,
            settings.copy(
                workMode = when (data) {
                    toggleForUserData -> when (settings.workMode) {
                        WorkMode.Disabled -> WorkMode.EnabledForUsers
                        WorkMode.Enabled -> WorkMode.EnabledForAdmins
                        WorkMode.EnabledForAdmins -> WorkMode.Enabled
                        WorkMode.EnabledForUsers -> WorkMode.Disabled
                    }
                    toggleForAdminData -> when (settings.workMode) {
                        WorkMode.Disabled -> WorkMode.EnabledForAdmins
                        WorkMode.Enabled -> WorkMode.EnabledForUsers
                        WorkMode.EnabledForAdmins -> WorkMode.Disabled
                        WorkMode.EnabledForUsers -> WorkMode.Enabled
                    }
                    else -> settings.workMode
                },
                warningsUntilBan = when (data) {
                    warnsCountData -> {
                        needNewMessage = true
                        oneOf(
                            parallel {
                                waitTextMessage (
                                    SendTextMessage(
                                        userId,
                                        buildEntities {
                                            +"Type count of warns until ban or "
                                            botCommand("cancel")
                                        }
                                    )
                                ).filter { message ->
                                    (message.content.text.toIntOrNull() != null).also { passed ->
                                        if (!passed) {
                                            reply(
                                                message
                                            ) {
                                                +"You should type some number instead or "
                                                botCommand("cancel")
                                                +" instead of \""
                                                +message.content.textSources
                                                +"\""
                                            }
                                        }
                                    }
                                }.first().content.text.toIntOrNull()
                            },
                            parallel {
                                waitText().filter {
                                    it.textSources.any {
                                        it is BotCommandTextSource && it.command == "cancel"
                                    }
                                }.first()
                                sendMessage(userId, "Canceled")
                                null // if received command with cancel - just return null and next ?: will cancel everything
                            }
                        ) ?: return false
                    }
                    else -> settings.warningsUntilBan
                },
                allowWarnAdmins = when (data) {
                    allowWarnAdminsData -> {
                        !settings.allowWarnAdmins
                    }
                    else -> settings.allowWarnAdmins
                }
            )
        )

        if (needNewMessage) {
            reply(query.message, "Updated")
        }

        runCatchingSafely { drawInlineButtons(chatId, query.user.id, query.message.messageId, InlineButtonsKeys.Settings) }

        answer(query)

        return true
    }

    override suspend fun BehaviourContext.drawInlineButtons(
        chatId: IdChatIdentifier,
        userId: UserId,
        messageId: MessageIdentifier,
        key: String?
    ) {
        val settings = chatsSettings.get(chatId) ?: ChatSettings()

        if (!adminsApi.isAdmin(chatId, userId)) {
            editMessageText(userId, messageId, "Ban settings are not supported for common users")
            return
        }

        runCatchingSafely {
            editMessageReplyMarkup(
                userId,
                messageId,
                replyMarkup = inlineKeyboard {
                    row {
                        val forUsersEnabled = settings.workMode is WorkMode.EnabledForUsers
                        val usersEnabledSymbol = forUsersEnabled.enabledSymbol
                        inlineDataButton(
                            "$usersEnabledSymbol Users",
                            chatId,
                            toggleForUserData
                        )
                        val forAdminsEnabled = settings.workMode is WorkMode.EnabledForAdmins
                        val adminsEnabledSymbol = forAdminsEnabled.enabledSymbol
                        inlineDataButton(
                            "$adminsEnabledSymbol Admins",
                            chatId,
                            toggleForAdminData
                        )
                    }
                    row {
                        inlineDataButton(
                            "${settings.allowWarnAdmins.enabledSymbol} Warn admins",
                            chatId,
                            allowWarnAdminsData
                        )
                    }
                    row {
                        inlineDataButton(
                            "Warns count: ${settings.warningsUntilBan}",
                            chatId,
                            warnsCountData
                        )
                    }
                    row {
                        drawerDataButton(backDrawer, chatId)
                    }
                }
            )
        }
    }

    override suspend fun BehaviourContext.setupReactions(koin: Koin) {
        onMessageDataCallbackQuery {
            if (performMessageDataCallbackQuery(it) != null) {
                answer(it)
            }
        }
    }
}

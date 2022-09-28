package dev.inmo.plagubot.plugins.bans

import com.benasher44.uuid.uuid4
import dev.inmo.micro_utils.common.*
import dev.inmo.micro_utils.coroutines.*
import dev.inmo.micro_utils.koin.singleWithBinds
import dev.inmo.micro_utils.repos.*
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.bans.db.*
import dev.inmo.plagubot.plugins.bans.db.ChatsSettingsTable
import dev.inmo.plagubot.plugins.bans.db.WarningsTable
import dev.inmo.plagubot.plugins.bans.db.warningsTable
import dev.inmo.plagubot.plugins.bans.models.ChatSettings
import dev.inmo.plagubot.plugins.bans.models.WorkMode
import dev.inmo.plagubot.plugins.bans.utils.checkBanPluginEnabled
import dev.inmo.plagubot.plugins.commands.BotCommandFullInfo
import dev.inmo.plagubot.plugins.commands.CommandsKeeperKey
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.plagubot.plugins.inline.buttons.inlineButtonsPlugin
import dev.inmo.plagubot.plugins.inline.buttons.utils.*
import dev.inmo.tgbotapi.abstracts.FromUser
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.utils.marker_factories.ByUserMessageMarkerFactory
import dev.inmo.tgbotapi.extensions.utils.asUser
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.libraries.cache.admins.*
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.message.textsources.*
import dev.inmo.tgbotapi.types.chat.*
import dev.inmo.tgbotapi.types.chat.Bot
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.chat.member.AdministratorChatMember
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.textsources.bold
import dev.inmo.tgbotapi.types.message.textsources.mention
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named

private val banCommandRegex = Regex("ban")
private val setChatWarningsCountCommandRegex = Regex("set_chat_warnings_count")
private val setChatWarningsCountCommand = "set_chat_warnings_count"
private val warningCommands = listOf(
    "warn",
    "warning"
)
private val unwarningCommands = listOf(
    "unwarn",
    "unwarning"
)
private val unwarningCommandRegex = Regex("(${unwarningCommands.joinToString(separator = ")|(", prefix = "(", postfix = ")")})")
private val warningCommandRegex = Regex("(${warningCommands.joinToString(separator = ")|(", prefix = "(", postfix = ")")})")
private const val countWarningsCommand = "ban_count_warns"
internal const val disableCommand = "disable_ban_plugin"
internal const val enableCommand = "enable_ban_plugin"
internal const val banCommand = "ban"

@Serializable
class BanPlugin : Plugin {

    override fun Module.setupDI(database: Database, params: JsonObject) {
        single(named("warningsTable")) { database.warningsTable }
        single(named("chatsSettingsTable")) { database.chatsSettingsTable }
        singleWithBinds (named("BanPluginSettingsProvider")) {
            BansInlineButtonsDrawer(
                get(),
                get(),
                get<ChatsSettingsTable>(named("chatsSettingsTable"))
            )
        }

        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(setChatWarningsCountCommand, "Set group chat warnings per user until his ban")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(disableCommand, "Disable ban plugin for current chat")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableCommand, "Enable ban plugin for current chat")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(banCommand, "Ban user in reply")
            )
        }
        warningCommands.forEach { command ->
            single(named(uuid4().toString())) {
                BotCommandFullInfo(
                    CommandsKeeperKey(BotCommandScope.AllGroupChats),
                    BotCommand(command, "Warn user about some violation")
                )
            }
        }
        unwarningCommands.forEach { command ->
            single(named(uuid4().toString())) {
                BotCommandFullInfo(
                    CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                    BotCommand(command, "Remove warning for user")
                )
            }
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllGroupChats),
                BotCommand(countWarningsCommand, "Use with reply (or just call to get know about you) to get warnings count")
            )
        }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val warningsRepository = koin.get<WarningsTable>(named("warningsTable"))
        val chatsSettings = koin.get<ChatsSettingsTable>(named("chatsSettingsTable"))
        val settingsProvider = koin.get<BansInlineButtonsDrawer>(named("BanPluginSettingsProvider"))
        val adminsApi = koin.get<AdminsCacheAPI>()

        suspend fun sayUserHisWarnings(message: Message, userInReply: Either<User, ChannelChat>, settings: ChatSettings, warnings: Long) {
            reply(
                message
            ) {
                userInReply.onFirst { userInReply ->
                    mention("${userInReply.lastName}  ${userInReply.firstName}", userInReply)
                }.onSecond {
                    +it.title
                }
                regular(" You have ")
                bold("${settings.warningsUntilBan - warnings}")
                regular(" warnings until ban")
            }
        }
        suspend fun BehaviourContext.getChatSettings(
            fromMessage: Message,
            sentByAdmin: Boolean
        ): ChatSettings? {
            val chatSettings = chatsSettings.get(fromMessage.chat.id) ?: ChatSettings()
            return if (!checkBanPluginEnabled(fromMessage, chatSettings, sentByAdmin)) {
                null
            } else {
                chatSettings
            }
        }

        onMessageDataCallbackQuery {
            with(settingsProvider) {
                if (performMessageDataCallbackQuery(it) != null) {
                    answer(it)
                }
            }
        }

        onCommand(
            warningCommandRegex,
            requireOnlyCommandInMessage = false
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val sentByAdmin = adminsApi.verifyMessageFromAdmin(commandMessage)
                val chatSettings = getChatSettings(commandMessage, sentByAdmin) ?: return@onCommand

                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user
                val channelInReply = (commandMessage.replyTo as? UnconnectedFromChannelGroupContentMessage<*>) ?.channel
                val chatId = userInReply ?.id ?: channelInReply ?.id ?: run {
                    reply(
                        commandMessage
                    ) {
                        regular("You should reply some message to warn the user or channel who sent it")
                    }
                    return@onCommand
                }
                val userInReplyIsAnAdmin = let {
                    userInReply != null && (admins.any { it.user.id == userInReply.id } || (userInReply is Bot && getChatMember(commandMessage.chat.id, userInReply.id) is AdministratorChatMember))
                }

                if (sentByAdmin) {
                    if (!chatSettings.allowWarnAdmins && userInReply != null && userInReplyIsAnAdmin) {
                        reply(commandMessage, regular("User ") + mention(userInReply) + " can't be warned - he is an admin")
                        return@onCommand
                    }
                    val key = commandMessage.chat.id to chatId
                    warningsRepository.add(key, commandMessage.messageId)
                    val warnings = warningsRepository.count(key)
                    if (warnings >= chatSettings.warningsUntilBan) {
                        when {
                            userInReply != null -> {
                                val banned = safelyWithResult {
                                    banChatMember(commandMessage.chat, userInReply)
                                }.isSuccess
                                reply(
                                    commandMessage,
                                    " "
                                ) {
                                    +"User" + mention(
                                        userInReply
                                    ) + "has${if (banned) " " else " not "}been banned"
                                }
                            }
                            channelInReply != null -> {
                                val banned = safelyWithResult {
                                    banChatSenderChat(commandMessage.chat, channelInReply.id)
                                }.isSuccess
                                reply(
                                    commandMessage,
                                    " "
                                ) {
                                    +"Channel ${channelInReply.title} has${if (banned) " " else " not "}been banned"
                                }
                            }
                        }
                    } else {
                        sayUserHisWarnings(commandMessage, (userInReply ?: channelInReply) ?.either() ?: return@onCommand, chatSettings, warnings)
                    }
                } else {
                    reply(
                        commandMessage
                    ) {
                        admins.filter {
                            it.user !is Bot
                        }.let { usersAdmins ->
                            usersAdmins.mapIndexed { i, it ->
                                mention("${it.user.lastName} ${it.user.firstName}", it.user)
                                if (usersAdmins.lastIndex != i) {
                                    regular(", ")
                                }
                            }
                        }
                    }
                }
            }
        }
        onCommand(
            unwarningCommandRegex,
            requireOnlyCommandInMessage = false
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val sentByAdmin = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val chatSettings = getChatSettings(commandMessage, sentByAdmin) ?: return@onCommand

                val userInReply = (commandMessage.replyTo as? CommonGroupContentMessage<*>) ?.user
                val channelInReply = (commandMessage.replyTo as? UnconnectedFromChannelGroupContentMessage<*>) ?.channel
                val chatId = userInReply ?.id ?: channelInReply ?.id ?: return@onCommand // add handling
                val userOrChannel: Either<User, ChannelChat> = (userInReply ?: channelInReply) ?.either() ?: return@onCommand

                if (sentByAdmin) {
                    val key = commandMessage.chat.id to chatId
                    val warnings = warningsRepository.getAll(key)
                    if (warnings.isNotEmpty()) {
                        warningsRepository.clear(key)
                        warningsRepository.add(key, warnings.dropLast(1))
                        sayUserHisWarnings(commandMessage, userOrChannel, chatSettings, warnings.size - 1L)
                    } else {
                        reply(
                            commandMessage,
                            listOf(regular("User or channel have no warns"))
                        )
                    }
                } else {
                    reply(
                        commandMessage,
                        listOf(regular("Sorry, you are not allowed for this action"))
                    )
                }
            }
        }
        onCommand(
            setChatWarningsCountCommandRegex,
            requireOnlyCommandInMessage = false,
            markerFactory = ByUserMessageMarkerFactory
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val admins = adminsApi.getChatAdmins(commandMessage.chat.id) ?: return@onCommand
                val sentByAdmin = commandMessage is AnonymousGroupContentMessage ||
                    (commandMessage is CommonGroupContentMessage && admins.any { it.user.id == commandMessage.user.id })
                val chatSettings = getChatSettings(commandMessage, sentByAdmin) ?: return@onCommand

                var newCount: Int? = null
                for (textSource in commandMessage.content.textSources.dropWhile { it !is BotCommandTextSource || it.command != setChatWarningsCountCommand }) {
                    val potentialCount = textSource.source.trim().toIntOrNull()
                    if (potentialCount != null) {
                        newCount = potentialCount
                        break
                    }
                }
                if (newCount == null || newCount < 1) {
                    reply(
                        commandMessage,
                        listOf(
                            regular("Usage: "),
                            code("/setChatWarningsCountCommand 3"),
                            regular(" (or any other number more than 0)")
                        )
                    )
                    return@onCommand
                }
                if (sentByAdmin) {
                    chatsSettings.set(
                        commandMessage.chat.id,
                        chatSettings.copy(warningsUntilBan = newCount)
                    )
                    reply(
                        commandMessage,
                        listOf(regular("Now warnings count is $newCount"))
                    )
                } else {
                    reply(
                        commandMessage,
                        listOf(regular("Sorry, you are not allowed for this action"))
                    )
                }
            }
        }

        onCommand(
            countWarningsCommand,
            requireOnlyCommandInMessage = true,
            markerFactory = ByUserMessageMarkerFactory
        ) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                val replyMessage = commandMessage.replyTo
                val messageToSearch = replyMessage ?: commandMessage
                val user = when (messageToSearch) {
                    is CommonGroupContentMessage<*> -> messageToSearch.user
                    is UnconnectedFromChannelGroupContentMessage<*> -> messageToSearch.channel
                    else -> {
                        reply(commandMessage) {
                            regular("Only common messages of users are allowed in reply for this command and to be called with this command")
                        }
                        return@onCommand
                    }
                }
                val count = warningsRepository.count(messageToSearch.chat.id to user.id)
                val maxCount = (chatsSettings.get(messageToSearch.chat.id) ?: ChatSettings()).warningsUntilBan
                val mention = (user.asUser()) ?.let {
                    it.mention("${it.firstName} ${it.lastName}")
                } ?: (user as? ChannelChat) ?.title ?.let(::regular) ?: return@onCommand
                reply(
                    commandMessage,
                    regular("User ") + mention + " have " + bold("$count/$maxCount") + " (" + bold("${maxCount - count}") + " left until ban)"
                )
            }
        }

//        onCommand(disableCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
//            if (commandMessage is GroupContentMessage<TextContent>) {
//                commandMessage.doAfterVerification(adminsApi) {
//                    val chatSettings = chatsSettings.get(commandMessage.chat.id) ?: ChatSettings()
//                    when (chatSettings.workMode) {
//                        WorkMode.Disabled -> {
//                            reply(commandMessage, "Ban plugin already disabled for this group")
//                        }
//                        WorkMode.Enabled -> {
//                            val disableForUsers = uuid4().toString().take(12)
//                            val disableForAdmins = uuid4().toString().take(12)
//                            val disableForAll = uuid4().toString().take(12)
//                            val keyboard = inlineKeyboard {
//                                row {
//                                    dataButton("Disable for admins", disableForAdmins)
//                                    dataButton("Disable for all", disableForAll)
//                                    dataButton("Disable for users", disableForUsers)
//                                }
//                            }
//                            val messageWithKeyboard = reply(
//                                commandMessage,
//                                "Choose an option",
//                                replyMarkup = keyboard
//                            )
//                            val answer = oneOf(
//                                async {
//                                    waitMessageDataCallbackQuery(
//                                        count = 1,
//                                        filter = {
//                                            it.message.messageId == messageWithKeyboard.messageId &&
//                                                it.message.chat.id == messageWithKeyboard.chat.id &&
//                                                (if (commandMessage is AnonymousGroupContentMessage<*>) {
//                                                    adminsApi.isAdmin(it.message.chat.id, it.user.id)
//                                                } else {
//                                                    it.user.id == commandMessage.asFromUser() ?.user ?.id
//                                                }).also { userAllowed ->
//                                                    if (!userAllowed) {
//                                                        answer(it, "You are not allowed for this action", showAlert = true)
//                                                    }
//                                                } &&
//                                                (it.data == disableForUsers || it.data == disableForAdmins || it.data == disableForAll)
//                                        }
//                                    ).firstOrNull() ?.data
//                                },
//                                async {
//                                    delay(60_000L)
//                                    null
//                                }
//                            )
//                            when (answer) {
//                                disableForUsers -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForAdmins)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Disabled for users"
//                                    )
//                                }
//                                disableForAdmins -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForUsers)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Disabled for admins"
//                                    )
//                                }
//                                disableForAll -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.Disabled)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Disabled for all"
//                                    )
//                                }
//                                else -> {
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        buildEntities {
//                                            strikethrough(messageWithKeyboard.content.textSources) + "\n" + "It took too much time, dismissed"
//                                        }
//                                    )
//                                }
//                            }
//                        }
//                        WorkMode.EnabledForAdmins,
//                        WorkMode.EnabledForUsers -> {
//                            chatsSettings.set(
//                                commandMessage.chat.id,
//                                chatSettings.copy(workMode = WorkMode.Disabled)
//                            )
//                            reply(commandMessage, "Ban plugin has been disabled for this group")
//                        }
//                    }
//                } ?: reply(commandMessage, "You can't manage settings of ban plugin for this chat")
//            }
//        }
//
//        onCommand(enableCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
//            if (commandMessage is GroupContentMessage<TextContent>) {
//                commandMessage.doAfterVerification(adminsApi) {
//                    val chatId = commandMessage.chat.id
//                    val chatSettings = chatsSettings.get(chatId) ?: ChatSettings()
//                    when (chatSettings.workMode) {
//                        WorkMode.Enabled -> {
//                            reply(commandMessage, "Ban plugin already enabled for this group")
//                        }
//                        WorkMode.Disabled -> {
//                            val enableForUsers = uuid4().toString().take(12)
//                            val enableForAdmins = uuid4().toString().take(12)
//                            val enableForAll = uuid4().toString().take(12)
//                            val keyboard = inlineKeyboard {
//                                row {
//                                    dataButton("Enable for admins", enableForAdmins)
//                                    dataButton("Enable for all", enableForAll)
//                                    dataButton("Enable for users", enableForUsers)
//                                }
//                            }
//                            val messageWithKeyboard = reply(
//                                commandMessage,
//                                "Choose an option",
//                                replyMarkup = keyboard
//                            )
//                            val answer = oneOf(
//                                async {
//                                    waitMessageDataCallbackQuery(
//                                        count = 1,
//                                        filter = {
//                                            it.message.messageId == messageWithKeyboard.messageId &&
//                                                it.message.chat.id == messageWithKeyboard.chat.id &&
//                                                (if (commandMessage is AnonymousGroupContentMessage<*>) {
//                                                    adminsApi.isAdmin(it.message.chat.id, it.user.id)
//                                                } else {
//                                                    it.user.id == commandMessage.asFromUser() ?.user ?.id
//                                                }).also { userAllowed ->
//                                                    if (!userAllowed) {
//                                                        answer(it, "You are not allowed for this action", showAlert = true)
//                                                    }
//                                                } &&
//                                                (it.data == enableForUsers || it.data == enableForAdmins || it.data == enableForAll)
//                                        }
//                                    ).firstOrNull() ?.data
//                                },
//                                async {
//                                    delay(60_000L)
//                                    null
//                                }
//                            )
//                            when (answer) {
//                                enableForUsers -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForUsers)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Enabled for users"
//                                    )
//                                }
//                                enableForAdmins -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.EnabledForAdmins)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Enabled for admins"
//                                    )
//                                }
//                                enableForAll -> {
//                                    chatsSettings.set(
//                                        commandMessage.chat.id,
//                                        chatSettings.copy(workMode = WorkMode.Enabled)
//                                    )
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        "Enabled for all"
//                                    )
//                                }
//                                else -> {
//                                    editMessageText(
//                                        messageWithKeyboard,
//                                        buildEntities {
//                                            strikethrough(messageWithKeyboard.content.textSources) + "\n" + "It took too much time, dismissed"
//                                        }
//                                    )
//                                }
//                            }
//                        }
//                        WorkMode.EnabledForAdmins,
//                        WorkMode.EnabledForUsers -> {
//                            chatsSettings.set(
//                                commandMessage.chat.id,
//                                chatSettings.copy(workMode = WorkMode.Enabled)
//                            )
//                            reply(commandMessage, "Ban plugin has been enabled for this group")
//                        }
//                    }
//                } ?: reply(commandMessage, "You can't manage settings of ban plugin for this chat")
//            }
//        }

        onCommand(banCommandRegex, requireOnlyCommandInMessage = true) { commandMessage ->
            if (commandMessage is GroupContentMessage<TextContent>) {
                commandMessage.doAfterVerification(adminsApi) {
                    val chatId = commandMessage.chat.id
                    val chatSettings = chatsSettings.get(chatId) ?: ChatSettings()
                    if (chatSettings.workMode is WorkMode.EnabledForAdmins) {

                        val userInReply: Either<User, ChannelChat> = when (val reply = commandMessage.replyTo) {
                            is FromUser -> reply.from
                            is UnconnectedFromChannelGroupContentMessage<*> -> reply.channel
                            else -> {
                                reply(commandMessage, "Use with reply to some message for user ban")
                                return@doAfterVerification
                            }
                        }.either()


                        val banned = safelyWithResult {
                            userInReply.onFirst {
                                banChatMember(commandMessage.chat, it.id)
                            }.onSecond {
                                banChatSenderChat(commandMessage.chat, it.id)
                            }
                        }.isSuccess

                        if (banned) {
                            val mention = userInReply.mapOnFirst {
                                mention(it)
                            } ?: userInReply.mapOnSecond {
                                regular(it.title)
                            } ?: return@doAfterVerification
                            reply(commandMessage) {
                                +"User " + mention + " has been banned"
                            }
                        }
                    }
                }
            }
        }
    }
}

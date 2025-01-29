package dev.inmo.plagubot.plugins.bans

import com.benasher44.uuid.uuid4
import dev.inmo.micro_utils.common.Either
import dev.inmo.micro_utils.common.either
import dev.inmo.micro_utils.common.mapOnFirst
import dev.inmo.micro_utils.common.mapOnSecond
import dev.inmo.micro_utils.common.onFirst
import dev.inmo.micro_utils.common.onSecond
import dev.inmo.micro_utils.coroutines.runCatchingSafely
import dev.inmo.micro_utils.koin.singleWithBinds
import dev.inmo.micro_utils.repos.add
import dev.inmo.micro_utils.repos.set
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.database
import dev.inmo.plagubot.plugins.bans.db.ChatsSettingsTable
import dev.inmo.plagubot.plugins.bans.db.WarningsTable
import dev.inmo.plagubot.plugins.bans.db.chatsSettingsTable
import dev.inmo.plagubot.plugins.bans.db.warningsTable
import dev.inmo.plagubot.plugins.bans.models.ChatSettings
import dev.inmo.plagubot.plugins.bans.models.WorkMode
import dev.inmo.plagubot.plugins.bans.utils.checkBanPluginEnabled
import dev.inmo.plagubot.plugins.commands.BotCommandFullInfo
import dev.inmo.plagubot.plugins.commands.CommandsKeeperKey
import dev.inmo.tgbotapi.abstracts.FromUser
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatSenderChat
import dev.inmo.tgbotapi.extensions.api.chat.members.getChatMember
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.utils.marker_factories.ByUserMessageMarkerFactory
import dev.inmo.tgbotapi.extensions.utils.userOrNull
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.libraries.cache.admins.verifyMessageFromAdmin
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.chat.Bot
import dev.inmo.tgbotapi.types.chat.ChannelChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.chat.member.AdministratorChatMember
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.textsources.BotCommandTextSource
import dev.inmo.tgbotapi.types.message.textsources.boldTextSource
import dev.inmo.tgbotapi.types.message.textsources.codeTextSource
import dev.inmo.tgbotapi.types.message.textsources.mentionTextSource
import dev.inmo.tgbotapi.types.message.textsources.plus
import dev.inmo.tgbotapi.types.message.textsources.regularTextSource
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.mention
import dev.inmo.tgbotapi.utils.regular
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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

    override fun Module.setupDI(config: JsonObject) {
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
        val adminsApi = koin.get<AdminsCacheAPI>()

        suspend fun sayUserHisWarnings(message: AccessibleMessage, userInReply: Either<User, ChannelChat>, settings: ChatSettings, warnings: Long) {
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
            fromMessage: AccessibleMessage,
            sentByAdmin: Boolean
        ): ChatSettings? {
            val chatSettings = chatsSettings.get(fromMessage.chat.id) ?: ChatSettings()
            return if (!checkBanPluginEnabled(fromMessage, chatSettings, sentByAdmin)) {
                null
            } else {
                chatSettings
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
                        reply(commandMessage, regularTextSource("User ") + mentionTextSource(userInReply) + " can't be warned - he is an admin")
                        return@onCommand
                    }
                    val key = commandMessage.chat.id to chatId
                    warningsRepository.add(key, commandMessage.messageId)
                    val warnings = warningsRepository.count(key)
                    if (warnings >= chatSettings.warningsUntilBan) {
                        when {
                            userInReply != null -> {
                                val banned = runCatchingSafely {
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
                                val banned = runCatchingSafely {
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
                            listOf(regularTextSource("User or channel have no warns"))
                        )
                    }
                } else {
                    reply(
                        commandMessage,
                        listOf(regularTextSource("Sorry, you are not allowed for this action"))
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
                            regularTextSource("Usage: "),
                            codeTextSource("/setChatWarningsCountCommand 3"),
                            regularTextSource(" (or any other number more than 0)")
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
                        listOf(regularTextSource("Now warnings count is $newCount"))
                    )
                } else {
                    reply(
                        commandMessage,
                        listOf(regularTextSource("Sorry, you are not allowed for this action"))
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
                val mention = (user.userOrNull()) ?.let {
                    it.mentionTextSource("${it.firstName} ${it.lastName}")
                } ?: (user as? ChannelChat) ?.title ?.let(::regularTextSource) ?: return@onCommand
                reply(
                    commandMessage,
                    regularTextSource("User ") + mention + " have " + boldTextSource("$count/$maxCount") + " (" + boldTextSource("${maxCount - count}") + " left until ban)"
                )
            }
        }

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


                        val banned = runCatchingSafely {
                            userInReply.onFirst {
                                banChatMember(commandMessage.chat, it.id)
                            }.onSecond {
                                banChatSenderChat(commandMessage.chat, it.id)
                            }
                        }.isSuccess

                        if (banned) {
                            val mention = userInReply.mapOnFirst {
                                mentionTextSource(it)
                            } ?: userInReply.mapOnSecond {
                                regularTextSource(it.title)
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

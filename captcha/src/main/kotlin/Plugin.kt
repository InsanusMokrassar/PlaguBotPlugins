package dev.inmo.plagubot.plugins.captcha

import com.benasher44.uuid.uuid4
import dev.inmo.micro_utils.coroutines.launchSafelyWithoutExceptions
import dev.inmo.micro_utils.coroutines.runCatchingSafely
import dev.inmo.micro_utils.coroutines.safelyWithoutExceptions
import dev.inmo.micro_utils.koin.singleWithRandomQualifier
import dev.inmo.micro_utils.repos.create
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.database
import dev.inmo.plagubot.plugins.captcha.cas.CASChecker
import dev.inmo.plagubot.plugins.captcha.cas.KtorCASChecker
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.captcha.db.UsersPassInfoRepo
import dev.inmo.plagubot.plugins.captcha.provider.ExpressionCaptchaProvider
import dev.inmo.plagubot.plugins.captcha.provider.SimpleCaptchaProvider
import dev.inmo.plagubot.plugins.captcha.provider.SlotMachineCaptchaProvider
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.plagubot.plugins.captcha.settings.InlineSettings
import dev.inmo.plagubot.plugins.commands.BotCommandFullInfo
import dev.inmo.plagubot.plugins.commands.CommandsKeeperKey
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.approveChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.declineChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.restrictChatMember
import dev.inmo.tgbotapi.extensions.api.delete
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onChatJoinRequest
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.extensions.parseCommandsWithArgs
import dev.inmo.tgbotapi.extensions.utils.groupChatOrNull
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.chat.LeftRestrictionsChatPermissions
import dev.inmo.tgbotapi.types.chat.RestrictionsChatPermissions
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import dev.inmo.tgbotapi.types.message.abstracts.AccessibleMessage
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.link
import dev.inmo.tgbotapi.utils.mention
import io.ktor.client.HttpClient
import korlibs.time.DateTime
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds

private const val enableAutoDeleteCommands = "captcha_auto_delete_commands_on"
private const val disableAutoDeleteCommands = "captcha_auto_delete_commands_off"
private const val enableAutoDeleteServiceMessages = "captcha_auto_delete_events_on"
private const val disableAutoDeleteServiceMessages = "captcha_auto_delete_events_off"

private const val enableSlotMachineCaptcha = "captcha_use_slot_machine"
private const val enableSimpleCaptcha = "captcha_use_simple"
private const val enableExpressionCaptcha = "captcha_use_expression"
private const val disableCaptcha = "disable_captcha"
private const val enableCaptcha = "enable_captcha"

private val enableDisableKickOnUnsuccess = Regex("captcha_(enable|disable)_kick")
private const val enableKickOnUnsuccess = "captcha_enable_kick"
private const val disableKickOnUnsuccess = "captcha_disable_kick"
private const val enableCAS = "captcha_enable_cas"
private const val disableCAS = "captcha_disable_cas"

private val changeCaptchaMethodCommandRegex = Regex(
    "captcha_use_((slot_machine)|(simple)|(expression))"
)

@Serializable
class CaptchaBotPlugin : Plugin {

    override fun Module.setupDI(config: JsonObject) {
        single { CaptchaChatsSettingsRepo(database) }

        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableAutoDeleteCommands, "Enable auto removing of commands addressed to captcha plugin")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(disableAutoDeleteCommands, "Disable auto removing of commands addressed to captcha plugin")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableAutoDeleteServiceMessages, "Enable auto removing of users joined messages")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(disableAutoDeleteServiceMessages, "Disable auto removing of users joined messages")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableSlotMachineCaptcha, "Change captcha method to slot machine")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableSimpleCaptcha, "Change captcha method to simple button")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(disableCaptcha, "Disable captcha for chat")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableCaptcha, "Enable captcha for chat")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableExpressionCaptcha, "Change captcha method to expressions")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableKickOnUnsuccess, "Not solved captcha users will be kicked from the chat")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(disableKickOnUnsuccess, "Not solved captcha users will NOT be kicked from the chat")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(enableCAS, "Users banned in CAS will fail captcha automatically")
            )
        }
        single(named(uuid4().toString())) {
            BotCommandFullInfo(
                CommandsKeeperKey(BotCommandScope.AllChatAdministrators),
                BotCommand(disableCAS, "Users banned in CAS will NOT fail captcha automatically")
            )
        }
        single {
            KtorCASChecker(
                HttpClient(),
                get()
            )
        } binds arrayOf(
            CASChecker::class
        )

        single {
            UsersPassInfoRepo(get())
        }

        single { InlineSettings(get(), get(), get(), get()) }
        singleWithRandomQualifier<InlineButtonsDrawer> { get<InlineSettings>() }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val repo: CaptchaChatsSettingsRepo by koin.inject()
        val adminsAPI = koin.getOrNull<AdminsCacheAPI>()
        val casChecker = koin.get<CASChecker>()
        val usersPassInfoRepo = koin.get<UsersPassInfoRepo>()

        suspend fun Chat.settings() = repo.getById(id) ?: repo.create(ChatSettings(id)).first()

        suspend fun doCaptcha(
            msg: AccessibleMessage?,
            chat: GroupChat,
            users: List<User>,
            joinRequest: Boolean
        ) {
            val defaultChatPermissions = LeftRestrictionsChatPermissions
            val settings = chat.settings()
            if (!settings.enabled) return

            safelyWithoutExceptions {
                if (settings.autoRemoveEvents && msg != null) {
                    deleteMessage(msg)
                }
            }
            var newUsers = users

            if (!joinRequest) {
                newUsers.forEach { user ->
                    restrictChatMember(
                        chat,
                        user,
                        permissions = RestrictionsChatPermissions
                    )
                }
            }

            newUsers = if (settings.casEnabled) {
                newUsers.filterNot { user ->
                    casChecker.isBanned(user.id).also { isBanned ->
                        runCatchingSafely {
                            if (isBanned) {
                                val entities = buildEntities {
                                    +"User " + mention(user) + " is banned in " + link("CAS System", "https://cas.chat/query?u=${user.id.chatId}")
                                }

                                msg ?.let {
                                    reply(it, entities)
                                } ?: send(chat, entities)

                                when {
                                    joinRequest -> runCatchingSafely { declineChatJoinRequest(chat.id, user.id) }
                                    settings.kickOnUnsuccess -> banChatMember(chat.id, user)
                                }
                            }
                        }
                    }
                }
            } else {
                newUsers
            }

            newUsers = if (settings.autoPassKnown) {
                newUsers.filterNot { user ->
                    usersPassInfoRepo.havePassedChats(user.id, settings.captchaProvider.complexity).also {
                        if (it) {
                            runCatchingSafely {
                                val entities = buildEntities {
                                    +"User " + mention(user) + " has passed captcha earlier. Captcha has been cancelled"
                                }

                                msg ?.let {
                                    reply(it, entities)
                                } ?: send(chat, entities)

                                when {
                                    joinRequest -> runCatchingSafely { approveChatJoinRequest(chat.id, user.id) }
                                    else -> restrictChatMember(chat.id, user, permissions = defaultChatPermissions)
                                }
                            }
                        }
                    }
                }
            } else {
                newUsers
            }

            with (settings.captchaProvider) {
                doAction(
                    msg ?.date ?: DateTime.now(),
                    chat,
                    newUsers,
                    defaultChatPermissions,
                    adminsAPI,
                    settings.kickOnUnsuccess,
                    joinRequest,
                    usersPassInfoRepo
                )
            }
        }

        onChatJoinRequest(
            initialFilter = {
                it.chat is GroupChat
            }
        ) { msg ->
            val settings = msg.chat.settings()
            if (settings.reactOnJoinRequest) {
                doCaptcha(
                    msg = null,
                    chat = msg.chat.groupChatOrNull() ?: return@onChatJoinRequest,
                    users = listOf(msg.user),
                    joinRequest = true
                )
            }
        }
        onNewChatMembers(
            initialFilter = {
                it.chat is GroupChat
            }
        ) { msg ->
            val settings = msg.chat.settings()
            if (!settings.reactOnJoinRequest) {
                doCaptcha(
                    msg = msg,
                    chat = msg.chat.groupChatOrNull() ?: return@onNewChatMembers,
                    users = msg.chatEvent.members,
                    joinRequest = false
                )
            }
        }

        if (adminsAPI != null) {
            onCommand(changeCaptchaMethodCommandRegex) {
                it.doAfterVerification(adminsAPI) {
                    val settings = it.chat.settings()
                    if (settings.autoRemoveCommands) {
                        safelyWithoutExceptions { deleteMessage(it) }
                    }
                    val commands = it.parseCommandsWithArgs()
                    val changeCommand = commands.keys.first {
                        println(it)
                        changeCaptchaMethodCommandRegex.matches(it)
                    }
                    println(changeCommand)
                    val captcha = when {
                        changeCommand.startsWith(enableSimpleCaptcha) -> SimpleCaptchaProvider()
                        changeCommand.startsWith(enableExpressionCaptcha) -> ExpressionCaptchaProvider()
                        changeCommand.startsWith(enableSlotMachineCaptcha) -> SlotMachineCaptchaProvider()
                        else -> return@doAfterVerification
                    }
                    val newSettings = settings.copy(captchaProvider = captcha)
                    if (repo.contains(it.chat.id)) {
                        repo.update(it.chat.id, newSettings)
                    } else {
                        repo.create(newSettings)
                    }
                    sendMessage(it.chat, "Settings updated").also { sent ->
                        delay(5000L)

                        if (settings.autoRemoveCommands) {
                            deleteMessage(sent)
                        }
                    }
                }
            }

            onCommand(
                enableAutoDeleteCommands,
                requireOnlyCommandInMessage = false
            ) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveCommands = true)
                    )

                    deleteMessage(message)
                }
            }
            onCommand(
                disableAutoDeleteCommands,
                requireOnlyCommandInMessage = false
            ) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveCommands = false)
                    )
                }
            }

            onCommand(disableCaptcha) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(enabled = false)
                    )

                    reply(message, "Captcha has been disabled")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(enableCaptcha) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(enabled = true)
                    )

                    reply(message, "Captcha has been enabled")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(enableAutoDeleteServiceMessages) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveEvents = true)
                    )

                    reply(message, "Ok, user joined service messages will be deleted")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(disableAutoDeleteServiceMessages) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveEvents = false)
                    )

                    reply(message, "Ok, user joined service messages will not be deleted")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(enableKickOnUnsuccess) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(kickOnUnsuccess = true)
                    )

                    reply(message, "Ok, new users didn't pass captcha will be kicked").apply {
                        launchSafelyWithoutExceptions {
                            delay(5000L)
                            delete(this@apply)
                        }
                    }

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(disableKickOnUnsuccess) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(kickOnUnsuccess = false)
                    )

                    reply(message, "Ok, new users didn't passed captcha will NOT be kicked").apply {
                        launchSafelyWithoutExceptions {
                            delay(5000L)
                            delete(this@apply)
                        }
                    }

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(enableCAS) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(casEnabled = true)
                    )

                    reply(message, "Ok, CAS banned user will automatically fail captcha").apply {
                        launchSafelyWithoutExceptions {
                            delay(5000L)
                            delete(this@apply)
                        }
                    }

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(disableCAS) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(casEnabled = false)
                    )

                    reply(message, "Ok, CAS banned user will NOT automatically fail captcha").apply {
                        launchSafelyWithoutExceptions {
                            delay(5000L)
                            delete(this@apply)
                        }
                    }

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }
        }
    }
}

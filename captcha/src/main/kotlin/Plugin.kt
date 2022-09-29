package dev.inmo.plagubot.plugins.captcha

import com.benasher44.uuid.uuid4
import dev.inmo.micro_utils.coroutines.*
import dev.inmo.micro_utils.koin.singleWithRandomQualifier
import dev.inmo.micro_utils.repos.create
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.captcha.cas.CASChecker
import dev.inmo.plagubot.plugins.captcha.cas.KtorCASChecker
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.captcha.provider.*
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.plagubot.plugins.captcha.settings.InlineSettings
import dev.inmo.plagubot.plugins.commands.BotCommandFullInfo
import dev.inmo.plagubot.plugins.commands.CommandsKeeperKey
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.delete
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.extensions.parseCommandsWithParams
import dev.inmo.tgbotapi.libraries.cache.admins.*
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.chat.*
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import dev.inmo.tgbotapi.utils.link
import dev.inmo.tgbotapi.utils.mention
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
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

    override fun Module.setupDI(database: Database, params: JsonObject) {
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

        single { InlineSettings(get(), get(), get(), get()) }
        singleWithRandomQualifier<InlineButtonsDrawer> { get<InlineSettings>() }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val repo: CaptchaChatsSettingsRepo by koin.inject()
        val adminsAPI = koin.getOrNull<AdminsCacheAPI>()
        val casChecker = koin.get<CASChecker>()

        suspend fun Chat.settings() = repo.getById(id) ?: repo.create(ChatSettings(id)).first()

        onNewChatMembers(
            initialFilter = {
                it.chat is GroupChat
            }
        ) { msg ->
            val settings = msg.chat.settings()
            if (!settings.enabled) return@onNewChatMembers

            safelyWithoutExceptions {
                if (settings.autoRemoveEvents) {
                    deleteMessage(msg)
                }
            }
            val chat = msg.chat.groupChatOrThrow()
            var newUsers = msg.chatEvent.members
            newUsers.forEach { user ->
                restrictChatMember(
                    chat,
                    user,
                    permissions = RestrictionsChatPermissions
                )
            }
            newUsers = if (settings.casEnabled) {
                newUsers.filterNot { user ->
                    casChecker.isBanned(user.id).also { isBanned ->
                        runCatchingSafely {
                            if (isBanned) {
                                reply(
                                    msg
                                ) {
                                    +"User " + mention(user) + " is banned in " + link("CAS System", "https://cas.chat/query?u=${user.id.chatId}")
                                }
                                if (settings.kickOnUnsuccess) {
                                    banChatMember(msg.chat.id, user)
                                }
                            }
                        }
                    }
                }
            } else {
                newUsers
            }
            val defaultChatPermissions = LeftRestrictionsChatPermissions

            with (settings.captchaProvider) {
                doAction(msg.date, chat, newUsers, defaultChatPermissions, adminsAPI, settings.kickOnUnsuccess)
            }
        }

        if (adminsAPI != null) {
            onCommand(changeCaptchaMethodCommandRegex) {
                it.doAfterVerification(adminsAPI) {
                    val settings = it.chat.settings()
                    if (settings.autoRemoveCommands) {
                        safelyWithoutExceptions { deleteMessage(it) }
                    }
                    val commands = it.parseCommandsWithParams()
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

                    reply(message, "Ok, new users didn't passed captcha will be kicked").apply {
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

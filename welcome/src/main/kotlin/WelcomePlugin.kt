package dev.inmo.plagubot.plugins.welcome

import dev.inmo.kslog.common.logger
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.commands.full
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.plagubot.plugins.welcome.WelcomePlugin.Companion.pluginConfigSectionName
import dev.inmo.plagubot.plugins.welcome.WelcomePlugin.Config
import dev.inmo.plagubot.plugins.welcome.db.WelcomeTable
import dev.inmo.plagubot.plugins.welcome.model.ChatSettings
import dev.inmo.plagubot.plugins.welcome.model.sendWelcome
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.delete
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitAnyContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.oneOf
import dev.inmo.tgbotapi.extensions.behaviour_builder.parallel
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.extensions.sameChat
import dev.inmo.tgbotapi.extensions.utils.extensions.sameMessage
import dev.inmo.tgbotapi.extensions.utils.ifCommonGroupContentMessage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.flatInlineKeyboard
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.FullChatIdentifierSerializer
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MilliSeconds
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import dev.inmo.tgbotapi.types.message.abstracts.CommonGroupContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.underline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds

/**
 * This is template of plugin with preset [log]ger, [Config] and template configurations of [setupDI] and [setupBotPlugin].
 * Replace [pluginConfigSectionName] value with your one to customize configuration section name
 */
@Serializable
class WelcomePlugin : Plugin {
    /**
     * Default logger of [WelcomePlugin] got with [logger]
     */
    private val log = logger

    /**
     * Configuration class for current plugin
     *
     * See realization of [setupDI] to get know how this class will be deserialized from global config
     *
     * See realization of [setupBotPlugin] to get know how to get access to this class
     *
     * @param recheckOfAdmin This parameter will be used before setup of
     */
    @Serializable
    private class Config(
        val recheckOfAdmin: MilliSeconds = 60000L,
        @Serializable(FullChatIdentifierSerializer::class)
        val recacheChatId: IdChatIdentifier? = null
    )

    /**
     * DI configuration of current plugin. Here we are decoding [Config] and put it into [Module] receiver
     */
    override fun Module.setupDI(database: Database, params: JsonObject) {
        single { get<Json>().decodeFromJsonElement(Config.serializer(), params[pluginConfigSectionName] ?: return@single Config()) }
        single { WelcomeTable(database) }
        single(named("welcome")) { BotCommand("welcome", "Use to setup welcome message").full(BotCommandScope.AllChatAdministrators) }
        single(named("welcome")) { WelcomeInlineButtons(get(), get(), get<Config>().recacheChatId) } binds arrayOf(
            InlineButtonsDrawer::class
        )
    }

    private suspend fun BehaviourContext.handleWelcomeCommand(
        adminsCacheAPI: AdminsCacheAPI,
        welcomeTable: WelcomeTable,
        config: Config,
        groupMessage: CommonGroupContentMessage<MessageContent>
    ) {
        val user = groupMessage.user

        if (adminsCacheAPI.isAdmin(groupMessage.chat.id, user.id)) {
            val previousMessage = welcomeTable.get(groupMessage.chat.id)
            val sentMessage = send(
                user,
                replyMarkup = flatInlineKeyboard {
                    if (previousMessage != null) {
                        dataButton("Unset", unsetData)
                    }
                    dataButton("Cancel", cancelData)
                }
            ) {
                regular("Ok, send me the message which should be used as welcome message for chat ")
                underline(groupMessage.chat.title)
            }

            oneOf(
                parallel {
                    val query = waitMessageDataCallbackQuery().filter {
                        it.data == unsetData && it.message.sameMessage(sentMessage)
                    }.first()

                    edit(sentMessage) {
                        if (welcomeTable.unset(groupMessage.chat.id) != null) {
                            regular("Welcome message has been removed for chat ")
                            underline(groupMessage.chat.title)
                        } else {
                            regular("Something went wrong on welcome message unsetting for chat ")
                            underline(groupMessage.chat.title)
                        }
                    }

                    answer(query)
                },
                parallel {
                    val query = waitMessageDataCallbackQuery().filter {
                        it.data == cancelData && it.message.sameMessage(sentMessage)
                    }.first()

                    edit(sentMessage) {
                        regular("You have cancelled change of welcome message for chat ")
                        underline(groupMessage.chat.title)
                    }

                    answer(query)
                },
                parallel {
                    val message = waitAnyContentMessage().filter {
                        it.sameChat(sentMessage)
                    }.first()

                    val success = welcomeTable.set(
                        ChatSettings(
                            groupMessage.chat.id,
                            message.chat.id,
                            message.messageId
                        )
                    )

                    reply(message) {
                        if (success) {
                            regular("Welcome message has been changed for chat ")
                            underline(groupMessage.chat.title)
                            regular(".\n\n")
                            bold("Please, do not delete this message if you want it to work and don't stop this bot to keep welcome message works right")
                        } else {
                            regular("Something went wrong on welcome message changing for chat ")
                            underline(groupMessage.chat.title)
                        }
                    }
                    delete(sentMessage)
                },
                parallel {
                    while (isActive) {
                        delay(config.recheckOfAdmin)

                        if (adminsCacheAPI.isAdmin(groupMessage.chat.id, user.id)) {
                            edit(sentMessage, "Sorry, but you are not admin in chat ${groupMessage.chat.title} anymore")
                            break
                        }
                    }
                }
            )
        }
    }

    /**
     * Final configuration of bot. Here we are getting [Config] from [koin]
     */
    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val config = koin.get<Config>()

        val welcomeTable = koin.get<WelcomeTable>()
        val adminsCacheAPI = koin.get<AdminsCacheAPI>()

        onCommand(
            "welcome",
            initialFilter = { it.chat is GroupChat }
        ) {
            it.ifCommonGroupContentMessage { groupMessage ->
                launch {
                    handleWelcomeCommand(adminsCacheAPI, welcomeTable, config, groupMessage)
                }
            }
        }

        onNewChatMembers {
            val chatSettings = welcomeTable.get(it.chat.id) ?: return@onNewChatMembers

            chatSettings.sendWelcome(
                this,
                config.recacheChatId,
                it.chat.id,
                it.messageId
            )
        }
    }

    companion object {
        private const val pluginConfigSectionName = "welcome"
        private const val cancelData = "cancel"
        private const val unsetData = "unset"
    }
}

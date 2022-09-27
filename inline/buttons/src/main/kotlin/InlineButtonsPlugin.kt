package dev.inmo.plagubot.plugins.inline.buttons

import dev.inmo.plagubot.*
import dev.inmo.plagubot.plugins.inline.buttons.utils.extractChatIdAndData
import dev.inmo.plagubot.plugins.inline.buttons.utils.inlineDataButton
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.requireGroupChat
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.extensions.utils.whenFromUser
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.UserId
import dev.inmo.tgbotapi.utils.code
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope

interface InlineButtonsProvider{
    val name: String
    val id: String

    suspend fun BehaviourContext.drawSettings(chatId: ChatId, userId: UserId, messageId: MessageId)
}

@Serializable
class InlineButtonsPlugin : InlineButtonsProvider, Plugin{
    override val id: String = "settings"
    override val name: String = "Settings"
    private val providersMap = mutableMapOf<String, InlineButtonsProvider>()

    fun register(provider: InlineButtonsProvider){
        providersMap[provider.id] = provider
    }

    private fun extractChatIdAndProviderId(data: String): Pair<ChatId, InlineButtonsProvider?> {
        val (chatId, providerId) = extractChatIdAndData(data)
        val provider = providersMap[providerId]
        return chatId to provider
    }
    private fun createProvidersInlineKeyboard(chatId: ChatId) = inlineKeyboard {
        providersMap.values.chunked(4).forEach {
            row {
                it.forEach { provider ->
                    inlineDataButton(provider.name, chatId, provider.id)
                }
            }
        }
    }

    override suspend fun BehaviourContext.drawSettings(chatId: ChatId, userId: UserId, messageId: MessageId){
        println(" Test $chatId $userId ")
        editMessageReplyMarkup(chatId, messageId, replyMarkup = createProvidersInlineKeyboard(chatId))
    }

    override fun Module.setupDI(database: Database, params: JsonObject) {
        single { this@InlineButtonsPlugin }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val adminsApi = koin.get<AdminsCacheAPI>()
        onCommand("settings") { commandMessage ->
            val verified = commandMessage.doAfterVerification(adminsApi) {
                commandMessage.whenFromUser {
                    runCatching {
                        send(
                            it.user.id,
                            replyMarkup = createProvidersInlineKeyboard(commandMessage.chat.id)
                        ) {
                            +"Settings for chat "
                            code(commandMessage.chat.requireGroupChat().title)
                        }
                    }.onFailure {
                        it.printStackTrace()
                        reply(
                            commandMessage
                        ) {
                            +"Looks like you didn't started the bot. Please start bot and try again"
                        }
                    }
                }
                true
            }
            if (verified == true) {
                return@onCommand
            }
            reply(commandMessage, "Only admins may trigger settings")
        }
        onMessageDataCallbackQuery{
            val (chatId, provider) = extractChatIdAndProviderId(it.data)
            if (provider == null) {
                return@onMessageDataCallbackQuery
            }
            with (provider){
                drawSettings(chatId, it.user.id, it.message.messageId)
            }
        }
    }
}

val Scope.inlineButtonsPlugin
    get() = getOrNull<InlineButtonsPlugin>()
val Koin.inlineButtonsPlugin
    get() = getOrNull<InlineButtonsPlugin>()





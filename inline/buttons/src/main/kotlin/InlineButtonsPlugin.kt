package dev.inmo.plagubot.plugins.inline.buttons

import dev.inmo.plagubot.*
import dev.inmo.plagubot.plugins.inline.buttons.utils.*
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
import org.koin.dsl.binds

@Serializable
class InlineButtonsPlugin : InlineButtonsDrawer, Plugin{
    override val id: String = "inline_buttons"
    override val name: String = "Back"
    private val providersMap = mutableMapOf<String, InlineButtonsDrawer>()

    fun register(provider: InlineButtonsDrawer){
        if (provider != this) {
            providersMap[provider.id] = provider
        }
    }

    private fun extractChatIdAndProviderId(data: String): Pair<ChatId, InlineButtonsDrawer?>? {
        val (chatId, providerId) = extractChatIdAndData(data) ?: return null
        val provider = providersMap[providerId] ?: takeIf { id == providerId }
        return chatId to provider
    }
    private fun createProvidersInlineKeyboard(chatId: ChatId, key: String?) = inlineKeyboard {
        providersMap.values.let {
            key ?.let { _ ->
                it.filter {
                    it.keys ?.contains(key) != false
                }
            } ?: it
        }.chunked(4).forEach {
            row {
                it.forEach { drawer ->
                    drawerDataButton(drawer, chatId)
                }
            }
        }
    }

    override suspend fun BehaviourContext.drawInlineButtons(
        chatId: ChatId,
        userId: UserId,
        messageId: MessageId,
        key: String?
    ) {
        editMessageReplyMarkup(
            userId,
            messageId,
            replyMarkup = createProvidersInlineKeyboard(chatId, key)
        )
    }

    override suspend fun BehaviourContext.setupReactions(koin: Koin) {
        onMessageDataCallbackQuery {
            val (chatId, provider) = extractChatIdAndProviderId(it.data) ?: return@onMessageDataCallbackQuery
            if (provider == null) {
                return@onMessageDataCallbackQuery
            }
            with (provider){
                drawInlineButtons(chatId, it.user.id, it.message.messageId, InlineButtonsKeys.Settings)
            }
        }
    }

    override fun Module.setupDI(database: Database, params: JsonObject) {
        single { this@InlineButtonsPlugin } binds arrayOf(
            InlineButtonsDrawer::class
        )
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        koin.getAll<InlineButtonsDrawer>().distinct().forEach {
            register(it)
        }
        val adminsApi = koin.get<AdminsCacheAPI>()
        onCommand("settings") { commandMessage ->
            val verified = commandMessage.doAfterVerification(adminsApi) {
                commandMessage.whenFromUser {
                    runCatching {
                        send(
                            it.user.id,
                            replyMarkup = createProvidersInlineKeyboard(commandMessage.chat.id, InlineButtonsKeys.Settings)
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

        koin.getAll<InlineButtonsDrawer>().distinct().forEach {
            with(it) {
                setupReactions(koin)
            }
        }
        setupReactions(koin)
    }
}

val Scope.inlineButtonsPlugin
    get() = getOrNull<InlineButtonsPlugin>()
val Koin.inlineButtonsPlugin
    get() = getOrNull<InlineButtonsPlugin>()





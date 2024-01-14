package dev.inmo.plagubot.plugins.welcome.model

import dev.inmo.micro_utils.coroutines.runCatchingSafely
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.extensions.api.forwardMessage
import dev.inmo.tgbotapi.extensions.api.send.copyMessage
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MessageIdentifier
import dev.inmo.tgbotapi.types.ReplyParameters
import kotlinx.serialization.Serializable

@Serializable
internal data class ChatSettings(
    val targetChatId: IdChatIdentifier,
    val sourceChatId: IdChatIdentifier,
    val sourceMessageId: MessageIdentifier
)

internal suspend fun ChatSettings.sendWelcome(
    bot: TelegramBot,
    recacheChatId: IdChatIdentifier?,
    targetChatId: IdChatIdentifier = this.targetChatId,
    replyTo: MessageIdentifier? = null
) = runCatchingSafely {
    bot.copyMessage(
        targetChatId,
        sourceChatId,
        sourceMessageId,
        replyParameters = replyTo ?.let { ReplyParameters(targetChatId, it, allowSendingWithoutReply = true) },
    )
}.onFailure {
    recacheChatId ?.let {
        if (it is RequestException && it.plainAnswer.contains("message to copy not found")) {
            return runCatchingSafely {
                val forwarded = bot.forwardMessage(
                    fromChatId = sourceChatId,
                    toChatId = recacheChatId,
                    messageId = sourceMessageId
                )
                bot.copyMessage(
                    targetChatId,
                    forwarded,
                    replyParameters = replyTo ?.let { ReplyParameters(targetChatId, it, allowSendingWithoutReply = true) },
                )
            }.getOrNull()
        }
    }
}.getOrNull()

package dev.inmo.plagubot.plugins.welcome.model

import dev.inmo.micro_utils.coroutines.runCatchingSafely
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.extensions.api.forwardMessage
import dev.inmo.tgbotapi.extensions.api.send.copyMessage
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.ReplyParameters
import kotlinx.serialization.Serializable

@Serializable
internal data class ChatSettings(
    val targetChatId: IdChatIdentifier,
    val sourceChatId: IdChatIdentifier,
    val sourceMessageId: MessageId
)

internal suspend fun ChatSettings.sendWelcome(
    bot: TelegramBot,
    recacheChatId: IdChatIdentifier?,
    targetChatId: IdChatIdentifier = this.targetChatId,
    replyTo: MessageId? = null
) = runCatchingSafely {
    bot.copyMessage(
        sourceChatId,
        sourceMessageId,
        targetChatId,
        replyParameters = replyTo ?.let { ReplyParameters(targetChatId, it, allowSendingWithoutReply = true) },
    )
}.onFailure {
    recacheChatId ?.let {
        if (it is RequestException && it.plainAnswer.contains("message to copy not found")) {
            return runCatchingSafely {
                val forwarded = bot.forwardMessage(
                    fromChatId = sourceChatId,
                    messageId = sourceMessageId,
                    toChatId = recacheChatId
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

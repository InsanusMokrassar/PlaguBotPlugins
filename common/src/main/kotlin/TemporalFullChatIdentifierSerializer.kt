package dev.inmo.plagubot.plugins.common

import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.ChatIdWithThreadId
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.Username
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull


object FullChatIdentifierSerializer : KSerializer<ChatIdentifier> {
    private val internalSerializer = JsonPrimitive.serializer()
    override val descriptor: SerialDescriptor = internalSerializer.descriptor
    override fun deserialize(decoder: Decoder): ChatIdentifier {
        val id = internalSerializer.deserialize(decoder)

        return id.longOrNull ?.let {
            ChatId(it)
        } ?:let {
            val splitted = id.content.split("/")
            if (splitted.size == 2) {
                val (chatId, threadId) = splitted
                ChatIdWithThreadId(
                    chatId.toLongOrNull() ?: return@let null,
                    threadId.toLongOrNull() ?: return@let null
                )
            } else {
                null
            }
        } ?: id.content.let {
            if (!it.startsWith("@")) {
                Username("@$it")
            } else {
                Username(it)
            }
        }
    }

    override fun serialize(encoder: Encoder, value: ChatIdentifier) {
        when (value) {
            is ChatId -> encoder.encodeLong(value.chatId)
            is ChatIdWithThreadId -> encoder.encodeString("${value.chatId}/${value.threadId}")
            is Username -> encoder.encodeString(value.username)
        }
    }
}

package dev.inmo.plagubot.plugins.captcha.cas

import dev.inmo.tgbotapi.types.UserId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KtorCASChecker(
    private val httpClient: HttpClient,
    private val json: Json
) : CASChecker {
    @Serializable
    private data class CheckResponse(
        val ok: Boolean
    )
    override suspend fun isBanned(userId: UserId): Boolean = httpClient.get(
        "https://api.cas.chat/check?user_id=${userId.chatId}"
    ).body<String>().let {
        json.decodeFromString(CheckResponse.serializer(), it)
    }.ok

}

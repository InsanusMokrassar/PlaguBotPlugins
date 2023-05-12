package dev.inmo.plagubot.plugins.captcha.settings

import dev.inmo.plagubot.plugins.captcha.provider.CaptchaProvider
import dev.inmo.plagubot.plugins.captcha.provider.SimpleCaptchaProvider
import dev.inmo.tgbotapi.types.IdChatIdentifier
import kotlinx.serialization.Serializable

@Serializable
data class ChatSettings(
    val chatId: IdChatIdentifier,
    val captchaProvider: CaptchaProvider = SimpleCaptchaProvider(),
    val autoRemoveCommands: Boolean = false,
    val autoRemoveEvents: Boolean = true,
    val kickOnUnsuccess: Boolean = true,
    val enabled: Boolean = false,
    val casEnabled: Boolean = false,
    val reactOnJoinRequest: Boolean = false
)

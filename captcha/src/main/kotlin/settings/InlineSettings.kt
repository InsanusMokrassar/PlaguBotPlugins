package dev.inmo.plagubot.plugins.captcha.settings

import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsDrawer
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.utils.row
import io.ktor.network.tls.NoPrivateKeyException

class InlineSettings(
    private val backDrawer: InlineButtonsDrawer,
    private val chatsSettingsRepo: CaptchaChatsSettingsRepo
) : InlineButtonsDrawer {
    override val name: String
        get() = "Captcha"
    override val id: String
        get() = "captcha"

    override suspend fun BehaviourContext.drawInlineButtons(
        chatId: ChatId,
        userId: UserId,
        messageId: MessageId,
        key: String?
    ) {
        val chatSettings = chatsSettingsRepo.getById(chatId) ?: ChatSettings(chatId)
        edit(
            userId,
            messageId,
            replyMarkup = inlineKeyboard {
                if (chatSettings.enabled) {
                    row {
                        dataButton("Enabled", disableData)
                    }
                }
            }
        )
    }

    companion object {
        const val captchaPrefix = "captcha_"

        const val enableData = "${captchaPrefix}_enable"
        const val disableData = "${captchaPrefix}_disable"

        const val enableAutoRemoveEventsData = "${captchaPrefix}_rm_e_e"
        const val disableAutoRemoveEventsData = "${captchaPrefix}_rm_e_d"

        const val enableAutoRemoveCommandsData = "${captchaPrefix}_rm_c_e"
        const val disableAutoRemoveCommandsData = "${captchaPrefix}_rm_c_d"

        const val enableKickOnUnsuccessData = "${captchaPrefix}_kick_e"
        const val disableKickOnUnsuccessData = "${captchaPrefix}_kick_d"

        const val enableCASData = "${captchaPrefix}_cas"
        const val disableCASData = "${captchaPrefix}_cas"
    }
}

package dev.inmo.plagubot.plugins.bans.utils

import dev.inmo.plagubot.plugins.bans.enableCommand
import dev.inmo.plagubot.plugins.bans.models.ChatSettings
import dev.inmo.plagubot.plugins.bans.models.WorkMode
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.AccessibleMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.utils.botCommand

internal suspend fun BehaviourContext.checkBanPluginEnabled(
    sourceMessage: AccessibleMessage,
    chatSettings: ChatSettings,
    fromAdmin: Boolean
): Boolean {
    return when (chatSettings.workMode) {
        WorkMode.Disabled -> {
            reply(
                sourceMessage,
                " "
            ) {
                +"Ban plugin is disabled in this chat. Use"
                botCommand(enableCommand)
                +"to enable ban plugin for everybody"
            }
            false
        }
        WorkMode.Enabled -> true
        WorkMode.EnabledForAdmins -> {
            if (!fromAdmin) {
                reply(
                    sourceMessage,
                    " "
                ) {
                    +"Ban plugin is disabled for users in this chat. Ask admins to use"
                    botCommand(enableCommand)
                    +"to enable ban plugin for everybody"
                }
                false
            } else {
                true
            }
        }
        WorkMode.EnabledForUsers -> {
            if (fromAdmin) {
                reply(
                    sourceMessage,
                    " "
                ) {
                    +"Ban plugin is disabled for admins in this chat. Use"
                    botCommand(enableCommand)
                    +"to enable ban plugin for everybody"
                }
                false
            } else {
                true
            }
        }
    }
}

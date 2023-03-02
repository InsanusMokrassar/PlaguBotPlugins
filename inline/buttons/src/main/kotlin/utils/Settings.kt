package dev.inmo.plagubot.plugins.inline.buttons.utils

import dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsPlugin
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.utils.formatting.makeUsernameLink
import dev.inmo.tgbotapi.extensions.utils.groupChatOrThrow
import dev.inmo.tgbotapi.extensions.utils.ifFromUser
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.libraries.cache.admins.doAfterVerification
import dev.inmo.tgbotapi.types.chat.ExtendedBot
import dev.inmo.tgbotapi.utils.code
import dev.inmo.tgbotapi.utils.link
import org.koin.core.Koin

suspend fun BehaviourContext.enableSettings(koin: Koin, plugin: InlineButtonsPlugin) {
    val adminsApi = koin.get<AdminsCacheAPI>()
    val me = koin.getOrNull<ExtendedBot>() ?: getMe()
    onCommand("settings") { commandMessage ->
        val verified = commandMessage.doAfterVerification(adminsApi) {
            commandMessage.ifFromUser {
                runCatching {
                    send(
                        it.user.id,
                        replyMarkup = plugin.createProvidersInlineKeyboard(commandMessage.chat.id, InlineButtonsKeys.Settings)
                    ) {
                        +"Settings for chat "
                        code(commandMessage.chat.groupChatOrThrow().title)
                    }
                }.onFailure {
                    it.printStackTrace()
                    reply(
                        commandMessage
                    ) {
                        +"Looks like you didn't started the bot. Please "
                        link("start", makeUsernameLink(me.username.usernameWithoutAt))
                        +" bot and try again"
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
}

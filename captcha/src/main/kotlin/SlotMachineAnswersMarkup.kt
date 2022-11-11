package dev.inmo.plagubot.plugins.captcha

import dev.inmo.plagubot.plugins.captcha.provider.cancelData
import dev.inmo.tgbotapi.extensions.utils.SlotMachineReelImage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.InlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

infix fun String.startingOf(target: String) = target.startsWith(this)

private val buttonsPreset: List<List<InlineKeyboardButton>> = SlotMachineReelImage.values().toList().chunked(2).map {
    it.map {
        CallbackDataInlineKeyboardButton(it.text, it.text)
    }
}

fun slotMachineReplyMarkup(
    adminCancelButton: Boolean = false
): InlineKeyboardMarkup {
    return inlineKeyboard {
        buttonsPreset.forEach(::add)
        if (adminCancelButton) {
            row<InlineKeyboardButton>(fun InlineKeyboardRowBuilder.() {
                dataButton("Cancel (Admins only)", cancelData)
            })
        }
    }
}

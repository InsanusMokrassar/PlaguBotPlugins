package dev.inmo.plagubot.plugins.captcha

import dev.inmo.tgbotapi.types.chat.ChatPermissions

val authorizedUserChatPermissions = ChatPermissions(
    canSendMessages = true,
    canSendMediaMessages = true,
    canSendPolls = true,
    canSendOtherMessages = true,
    canAddWebPagePreviews = true,
    canChangeInfo = true,
    canInviteUsers = true,
    canPinMessages = true,
)

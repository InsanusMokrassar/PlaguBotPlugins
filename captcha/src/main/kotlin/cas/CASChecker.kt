package dev.inmo.plagubot.plugins.captcha.cas

import dev.inmo.tgbotapi.types.UserId

interface CASChecker {
    suspend fun isBanned(userId: UserId): Boolean
}

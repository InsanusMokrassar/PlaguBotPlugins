package dev.inmo.plagubot.plugins.bans.models

import kotlinx.serialization.Serializable

@Serializable
internal data class ChatSettings(
    val warningsUntilBan: Int = 3,
    val allowWarnAdmins: Boolean = true,
    @Deprecated("use workMode instead")
    val enabled: Boolean = true,
    val workMode: WorkMode = if (enabled) WorkMode.Enabled else WorkMode.Disabled,
)

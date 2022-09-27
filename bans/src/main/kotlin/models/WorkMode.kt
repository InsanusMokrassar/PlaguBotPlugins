package dev.inmo.plagubot.plugins.bans.models

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

@Polymorphic
sealed interface WorkMode {
    sealed interface EnabledForAdmins : WorkMode {
        @Serializable
        companion object Default : EnabledForAdmins
    }
    sealed interface EnabledForUsers : WorkMode {
        @Serializable
        companion object Default : EnabledForUsers
    }
    @Serializable
    object Enabled : WorkMode, EnabledForAdmins, EnabledForUsers
    @Serializable
    object Disabled : WorkMode
}

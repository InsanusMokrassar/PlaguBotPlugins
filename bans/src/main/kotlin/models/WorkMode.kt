package dev.inmo.plagubot.plugins.bans.models

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

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

internal val serializationModule = SerializersModule {
    polymorphic(WorkMode::class) {
        subclass(WorkMode.EnabledForAdmins.Default::class, WorkMode.EnabledForAdmins.serializer())
        subclass(WorkMode.EnabledForUsers.Default::class, WorkMode.EnabledForUsers.serializer())
        subclass(WorkMode.Enabled::class, WorkMode.Enabled.serializer())
        subclass(WorkMode.Disabled::class, WorkMode.Disabled.serializer())
    }
}

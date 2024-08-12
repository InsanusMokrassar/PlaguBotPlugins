package dev.inmo.plagubot.plugins.bans.models

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

@Serializable
sealed interface WorkMode {
    @Serializable
    sealed interface EnabledForAdmins : WorkMode {
        @Serializable
        companion object Default : EnabledForAdmins
    }
    @Serializable
    sealed interface EnabledForUsers : WorkMode {
        @Serializable
        companion object Default : EnabledForUsers
    }
    @Serializable
    data object Enabled : WorkMode, EnabledForAdmins, EnabledForUsers
    @Serializable
    data object Disabled : WorkMode
}

//internal val serializationModule = SerializersModule {
//    polymorphic(WorkMode::class) {
//        subclass(WorkMode.EnabledForAdmins.Default::class, WorkMode.EnabledForAdmins.Default.serializer())
//        subclass(WorkMode.EnabledForUsers.Default::class, WorkMode.EnabledForUsers.Default.serializer())
//        subclass(WorkMode.Enabled::class, WorkMode.Enabled.serializer())
//        subclass(WorkMode.Disabled::class, WorkMode.Disabled.serializer())
//    }
//}

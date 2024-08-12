package dev.inmo.plagubot.plugins.bans.utils

//import dev.inmo.plagubot.plugins.bans.models.serializationModule
import kotlinx.serialization.json.Json

internal val banPluginSerialFormat = Json {
    ignoreUnknownKeys = true
//    serializersModule = serializationModule
}

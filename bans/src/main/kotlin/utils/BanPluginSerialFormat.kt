package dev.inmo.plagubot.plugins.bans.utils

import dev.inmo.plagubot.plugins.bans.serializationModule
import kotlinx.serialization.json.Json

internal val banPluginSerialFormat = Json {
    ignoreUnknownKeys = true
    serializersModule = serializationModule
}

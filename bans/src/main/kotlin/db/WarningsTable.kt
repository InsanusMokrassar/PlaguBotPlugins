package dev.inmo.plagubot.plugins.bans.db

import dev.inmo.micro_utils.repos.KeyValuesRepo
import dev.inmo.micro_utils.repos.exposed.onetomany.ExposedKeyValuesRepo
import dev.inmo.micro_utils.repos.mappers.withMapper
import dev.inmo.plagubot.plugins.bans.utils.banPluginSerialFormat
import dev.inmo.tgbotapi.types.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.Database

internal val Database.warningsTable: WarningsTable
    get() = ExposedKeyValuesRepo(
        this,
        { text("chatToUser") },
        { long("messageId") },
        "BanPluginWarningsTable"
    ).withMapper<Pair<ChatId, UserId>, MessageIdentifier, String, Long>(
        keyToToFrom = { banPluginSerialFormat.decodeFromString(this) },
        keyFromToTo = { banPluginSerialFormat.encodeToString(this) },
        valueToToFrom = { this },
        valueFromToTo = { this }
    )
internal typealias WarningsTable = KeyValuesRepo<Pair<ChatId, UserId>, MessageIdentifier>

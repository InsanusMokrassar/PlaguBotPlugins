package dev.inmo.plagubot.plugins.bans.db

import dev.inmo.micro_utils.repos.KeyValueRepo
import dev.inmo.micro_utils.repos.exposed.initTable
import dev.inmo.micro_utils.repos.exposed.keyvalue.AbstractExposedKeyValueRepo
import dev.inmo.plagubot.plugins.bans.models.ChatSettings
import dev.inmo.plagubot.plugins.bans.utils.banPluginSerialFormat
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.RawChatId
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database

internal typealias ChatsSettingsTable = KeyValueRepo<IdChatIdentifier, ChatSettings>

private class ExposedChatsSettingsTable(
    database: Database
) : AbstractExposedKeyValueRepo<IdChatIdentifier, ChatSettings>(
    database,
    "BanPluginChatsSettingsTable"
) {
    override val keyColumn = long("chatId")
    private val threadIdColumn = long("threadId").nullable().default(null)
    private val chatSettingsColumn = text("userId")
    override val selectById: (IdChatIdentifier) -> Op<Boolean> = {
        keyColumn.eq(it.chatId.long).and(it.threadId ?.long ?.let { threadIdColumn.eq(it) } ?: threadIdColumn.isNull())
    }
    override val selectByValue: (ChatSettings) -> Op<Boolean> = {
        chatSettingsColumn.eq(banPluginSerialFormat.encodeToString(ChatSettings.serializer(), it))
    }
    override val ResultRow.asKey: IdChatIdentifier
        get() = IdChatIdentifier(
            RawChatId(get(keyColumn)),
            get(threadIdColumn) ?.let(::MessageThreadId)
        )
    override val ResultRow.asObject: ChatSettings
        get() = banPluginSerialFormat.decodeFromString(ChatSettings.serializer(), get(chatSettingsColumn))

    init {
        initTable()
    }

    override fun update(k: IdChatIdentifier, v: ChatSettings, it: UpdateBuilder<Int>) {
        it[chatSettingsColumn] = banPluginSerialFormat.encodeToString(ChatSettings.serializer(), v)
    }

    override fun insertKey(k: IdChatIdentifier, v: ChatSettings, it: UpdateBuilder<Int>) {
        it[keyColumn] = k.chatId.long
        it[threadIdColumn] = k.threadId ?.long
    }
}

internal val Database.chatsSettingsTable: ChatsSettingsTable
    get() = ExposedChatsSettingsTable(this)

package dev.inmo.plagubot.plugins.bans.db

import dev.inmo.micro_utils.repos.KeyValueRepo
import dev.inmo.micro_utils.repos.exposed.initTable
import dev.inmo.micro_utils.repos.exposed.keyvalue.AbstractExposedKeyValueRepo
import dev.inmo.plagubot.plugins.bans.models.ChatSettings
import dev.inmo.plagubot.plugins.bans.utils.banPluginSerialFormat
import dev.inmo.tgbotapi.types.IdChatIdentifier
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ISqlExpressionBuilder
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateBuilder

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
    override val selectById: ISqlExpressionBuilder.(IdChatIdentifier) -> Op<Boolean> = {
        keyColumn.eq(it.chatId).and(threadIdColumn.eq(it.threadId))
    }
    override val selectByValue: ISqlExpressionBuilder.(ChatSettings) -> Op<Boolean> = {
        chatSettingsColumn.eq(banPluginSerialFormat.encodeToString(ChatSettings.serializer(), it))
    }
    override val ResultRow.asKey: IdChatIdentifier
        get() = IdChatIdentifier(
            get(keyColumn),
            get(threadIdColumn)
        )
    override val ResultRow.asObject: ChatSettings
        get() = banPluginSerialFormat.decodeFromString(ChatSettings.serializer(), get(chatSettingsColumn))

    init {
        initTable()
    }

    override fun update(k: IdChatIdentifier, v: ChatSettings, it: UpdateBuilder<Int>) {
        it[chatSettingsColumn] = banPluginSerialFormat.encodeToString(ChatSettings.serializer(), v)
    }

    override fun insertKey(k: IdChatIdentifier, v: ChatSettings, it: InsertStatement<Number>) {
        it[keyColumn] = k.chatId
        it[threadIdColumn] = k.threadId
    }
}

internal val Database.chatsSettingsTable: ChatsSettingsTable
    get() = ExposedChatsSettingsTable(this)

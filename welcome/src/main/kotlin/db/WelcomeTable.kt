package dev.inmo.plagubot.plugins.welcome.db

import dev.inmo.micro_utils.repos.exposed.ExposedRepo
import dev.inmo.micro_utils.repos.exposed.initTable
import dev.inmo.plagubot.plugins.welcome.model.ChatSettings
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.RawChatId
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction

internal class WelcomeTable(
    override val database: Database
) : Table("welcome"), ExposedRepo {
    val targetChatIdColumn = long("targetChatId").uniqueIndex()
    val targetThreadIdColumn = long("targetThreadId").nullable().default(null)
    val sourceChatIdColumn = long("sourceChatId")
    val sourceThreadIdColumn = long("sourceThreadId").nullable().default(null)
    val sourceMessageIdColumn = long("sourceMessageId")
    override val primaryKey: PrimaryKey = PrimaryKey(targetChatIdColumn)

    init {
        initTable()
    }

    private fun getInTransaction(chatId: IdChatIdentifier) = selectAll().where {
        targetChatIdColumn.eq(chatId.chatId.long).and(
            chatId.threadId ?.long ?.let { targetThreadIdColumn.eq(it) } ?: targetThreadIdColumn.isNull()
        )
    }.limit(1).firstOrNull() ?.let {
        ChatSettings(
            IdChatIdentifier(
                RawChatId(it[targetChatIdColumn]),
                it[targetThreadIdColumn] ?.let(::MessageThreadId)
            ),
            IdChatIdentifier(
                RawChatId(it[sourceChatIdColumn]),
                it[sourceThreadIdColumn] ?.let(::MessageThreadId)
            ),
            it[sourceMessageIdColumn].let(::MessageId)
        )
    }

    fun get(chatId: IdChatIdentifier): ChatSettings? = transaction(database) {
        getInTransaction(chatId)
    }

    fun set(chatSettings: ChatSettings): Boolean = transaction(database) {
        deleteWhere { targetChatIdColumn.eq(chatSettings.targetChatId.chatId.long) }
        insert {
            it[targetChatIdColumn] = chatSettings.targetChatId.chatId.long
            it[targetThreadIdColumn] = chatSettings.targetChatId.threadId ?.long
            it[sourceChatIdColumn] = chatSettings.sourceChatId.chatId.long
            it[sourceThreadIdColumn] = chatSettings.sourceChatId.threadId ?.long
            it[sourceMessageIdColumn] = chatSettings.sourceMessageId.long
        }.insertedCount > 0
    }

    fun unset(chatId: IdChatIdentifier): ChatSettings? = transaction(database) {
        getInTransaction(chatId) ?.also {
            deleteWhere { targetChatIdColumn.eq(chatId.chatId.long).and(chatId.threadId ?.long ?.let { targetThreadIdColumn.eq(it) } ?: targetThreadIdColumn.isNull()) }
        }
    }
}

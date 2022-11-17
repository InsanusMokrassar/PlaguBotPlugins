package dev.inmo.plagubot.plugins.welcome.db

import dev.inmo.micro_utils.repos.exposed.ExposedRepo
import dev.inmo.micro_utils.repos.exposed.initTable
import dev.inmo.plagubot.plugins.welcome.model.ChatSettings
import dev.inmo.tgbotapi.types.IdChatIdentifier
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
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

    private fun getInTransaction(chatId: IdChatIdentifier) = select {
        targetChatIdColumn.eq(chatId.chatId).and(
            targetThreadIdColumn.eq(chatId.threadId)
        )
    }.limit(1).firstOrNull() ?.let {
        ChatSettings(
            IdChatIdentifier(
                it[targetChatIdColumn],
                it[targetThreadIdColumn]
            ),
            IdChatIdentifier(
                it[sourceChatIdColumn],
                it[sourceThreadIdColumn]
            ),
            it[sourceMessageIdColumn]
        )
    }

    fun get(chatId: IdChatIdentifier): ChatSettings? = transaction(database) {
        getInTransaction(chatId)
    }

    fun set(chatSettings: ChatSettings): Boolean = transaction(database) {
        deleteWhere { targetChatIdColumn.eq(chatSettings.targetChatId.chatId) }
        insert {
            it[targetChatIdColumn] = chatSettings.targetChatId.chatId
            it[targetThreadIdColumn] = chatSettings.targetChatId.threadId
            it[sourceChatIdColumn] = chatSettings.sourceChatId.chatId
            it[sourceThreadIdColumn] = chatSettings.sourceChatId.threadId
            it[sourceMessageIdColumn] = chatSettings.sourceMessageId
        }.insertedCount > 0
    }

    fun unset(chatId: IdChatIdentifier): ChatSettings? = transaction(database) {
        getInTransaction(chatId) ?.also {
            deleteWhere { targetChatIdColumn.eq(chatId.chatId).and(targetThreadIdColumn.eq(chatId.threadId)) }
        }
    }
}

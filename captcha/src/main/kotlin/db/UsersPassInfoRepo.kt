package dev.inmo.plagubot.plugins.captcha.db

import dev.inmo.micro_utils.repos.exposed.onetomany.AbstractExposedKeyValuesRepo
import dev.inmo.plagubot.plugins.captcha.provider.Complexity
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.UserId
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ISqlExpressionBuilder
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction

class UsersPassInfoRepo(database: Database) : AbstractExposedKeyValuesRepo<UserId, UsersPassInfoRepo.PassInfo>(
    database,
    "passed_users_info"
) {
    @Serializable
    data class PassInfo(
        val chatId: ChatId,
        val passed: Boolean,
        val complexity: Complexity
    )

    private val userIdColumn = long("user_id")
    private val chatIdColumn = long("chat_id")
    private val passedColumn = bool("passed")
    private val complexityColumn = integer("complexity")

    override val keyColumn: Column<Long>
        get() = userIdColumn
    override val selectById: ISqlExpressionBuilder.(UserId) -> Op<Boolean> = {
        userIdColumn.eq(it.chatId)
    }
    override val selectByValue: ISqlExpressionBuilder.(PassInfo) -> Op<Boolean> = {
        chatIdColumn.eq(it.chatId.chatId).and(passedColumn.eq(it.passed))
    }
    override val ResultRow.asKey: UserId
        get() = UserId(get(userIdColumn))
    override val ResultRow.asObject: PassInfo
        get() = PassInfo(
            ChatId(get(chatIdColumn)),
            get(passedColumn),
            Complexity(get(complexityColumn))
        )

    override fun insert(k: UserId, v: PassInfo, it: InsertStatement<Number>) {
        it[userIdColumn] = k.chatId
        it[chatIdColumn] = v.chatId.chatId
        it[passedColumn] = v.passed
        it[complexityColumn] = v.complexity.weight
    }

    fun getPassedChats(userId: UserId, minComplexity: Complexity? = null): List<ChatId> = transaction(database) {
        select {
            val op = selectById(userId).and(passedColumn.eq(true))

            minComplexity ?.let {
                op.and(complexityColumn.greaterEq(minComplexity.weight))
            } ?: op
        }.map { ChatId(it[chatIdColumn]) }
    }

    fun havePassedChats(userId: UserId, minComplexity: Complexity? = null): Boolean = transaction(database) {
        select {
            val op = selectById(userId).and(passedColumn.eq(true))

            minComplexity ?.let {
                op.and(complexityColumn.greaterEq(minComplexity.weight))
            } ?: op
        }.limit(1).count() > 0
    }
}

package dev.inmo.plagubot.plugins.captcha.db

import dev.inmo.micro_utils.repos.exposed.AbstractExposedCRUDRepo
import dev.inmo.micro_utils.repos.exposed.initTable
import dev.inmo.plagubot.plugins.captcha.provider.CaptchaProvider
import dev.inmo.plagubot.plugins.captcha.provider.SimpleCaptchaProvider
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.toChatId
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ISqlExpressionBuilder
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateBuilder

private val captchaProviderSerialFormat = Json {
    ignoreUnknownKeys = true
}

private val defaultCaptchaProviderValue = captchaProviderSerialFormat.encodeToString(CaptchaProvider.serializer(), SimpleCaptchaProvider())

class CaptchaChatsSettingsRepo(
    override val database: Database
) : AbstractExposedCRUDRepo<ChatSettings, IdChatIdentifier, ChatSettings>(
    tableName = "CaptchaChatsSettingsRepo"
) {
    private val chatIdColumn = long("chatId")
    private val threadIdColumn = long("threadId").nullable().default(null)
    private val captchaProviderColumn = text("captchaProvider").apply {
        default(defaultCaptchaProviderValue)
    }
    private val autoRemoveCommandsColumn = bool("autoRemoveCommands")
    private val autoRemoveEventsColumn = bool("autoRemoveEvents").apply { default(true) }
    private val enabledColumn = bool("enabled").default(true)
    private val kickOnUnsuccessColumn = bool("kick").default(true)
    private val casColumn = bool("cas").default(true)

    override val primaryKey = PrimaryKey(chatIdColumn)

    override val selectByIds: ISqlExpressionBuilder.(List<IdChatIdentifier>) -> Op<Boolean> = {
        chatIdColumn.inList(it.map { it.chatId })
    }

    override fun createAndInsertId(value: ChatSettings, it: InsertStatement<Number>): IdChatIdentifier {
        it[chatIdColumn] = value.chatId.chatId
        it[threadIdColumn] = value.chatId.threadId
        return value.chatId
    }

    override fun update(id: IdChatIdentifier?, value: ChatSettings, it: UpdateBuilder<Int>) {
        it[captchaProviderColumn] = captchaProviderSerialFormat.encodeToString(CaptchaProvider.serializer(), value.captchaProvider)
        it[autoRemoveCommandsColumn] = value.autoRemoveCommands
        it[autoRemoveEventsColumn] = value.autoRemoveEvents
        it[enabledColumn] = value.enabled
        it[kickOnUnsuccessColumn] = value.kickOnUnsuccess
        it[casColumn] = value.casEnabled
    }

    override fun InsertStatement<Number>.asObject(value: ChatSettings): ChatSettings = ChatSettings(
        chatId = get(chatIdColumn).toChatId(),
        captchaProvider = captchaProviderSerialFormat.decodeFromString(CaptchaProvider.serializer(), get(captchaProviderColumn)),
        autoRemoveCommands = get(autoRemoveCommandsColumn),
        autoRemoveEvents = get(autoRemoveEventsColumn),
        enabled = get(enabledColumn),
        kickOnUnsuccess = get(kickOnUnsuccessColumn),
        casEnabled = get(casColumn)
    )

    override val selectById: ISqlExpressionBuilder.(IdChatIdentifier) -> Op<Boolean> = { chatIdColumn.eq(it.chatId) }
    override val ResultRow.asObject: ChatSettings
        get() = ChatSettings(
            chatId = get(chatIdColumn).toChatId(),
            captchaProvider = captchaProviderSerialFormat.decodeFromString(CaptchaProvider.serializer(), get(captchaProviderColumn)),
            autoRemoveCommands = get(autoRemoveCommandsColumn),
            autoRemoveEvents = get(autoRemoveEventsColumn),
            enabled = get(enabledColumn),
            kickOnUnsuccess = get(kickOnUnsuccessColumn),
            casEnabled = get(casColumn)
        )

    init {
        initTable()
    }
}

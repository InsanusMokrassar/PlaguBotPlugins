package dev.inmo.plagubot.plugins.captcha.db

import dev.inmo.micro_utils.repos.exposed.AbstractExposedCRUDRepo
import dev.inmo.micro_utils.repos.exposed.initTable
import dev.inmo.plagubot.plugins.captcha.provider.CaptchaProvider
import dev.inmo.plagubot.plugins.captcha.provider.SimpleCaptchaProvider
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.toChatId
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ISqlExpressionBuilder
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
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
    private val reactOnJoinRequestColumn = bool("react_on_join_request").default(false)
    private val autoPassKnownColumn = bool("auto_pass_known").default(false)

    override val primaryKey = PrimaryKey(chatIdColumn)

    override val selectByIds: ISqlExpressionBuilder.(List<IdChatIdentifier>) -> Op<Boolean> = {
        fun IdChatIdentifier.createEq() = chatIdColumn.eq(chatId.long).and(
            threadId ?.let { threadIdColumn.eq(it.long) } ?: threadIdColumn.isNull()
        )
        it.foldRight(Op.FALSE as Op<Boolean>) { input, acc ->
            acc.or(input.createEq())
        }
    }
    override val ResultRow.asId: IdChatIdentifier
        get() = IdChatIdentifier(RawChatId(get(chatIdColumn)), get(threadIdColumn) ?.let(::MessageThreadId))

    override fun createAndInsertId(value: ChatSettings, it: InsertStatement<Number>): IdChatIdentifier {
        it[chatIdColumn] = value.chatId.chatId.long
        it[threadIdColumn] = value.chatId.threadId ?.long
        return value.chatId
    }

    override fun update(id: IdChatIdentifier?, value: ChatSettings, it: UpdateBuilder<Int>) {
        it[captchaProviderColumn] = captchaProviderSerialFormat.encodeToString(CaptchaProvider.serializer(), value.captchaProvider)
        it[autoRemoveCommandsColumn] = value.autoRemoveCommands
        it[autoRemoveEventsColumn] = value.autoRemoveEvents
        it[enabledColumn] = value.enabled
        it[kickOnUnsuccessColumn] = value.kickOnUnsuccess
        it[casColumn] = value.casEnabled
        it[reactOnJoinRequestColumn] = value.reactOnJoinRequest
        it[autoPassKnownColumn] = value.autoPassKnown
    }

    override fun InsertStatement<Number>.asObject(value: ChatSettings): ChatSettings = ChatSettings(
        chatId = IdChatIdentifier(RawChatId(get(chatIdColumn)), get(threadIdColumn) ?.let(::MessageThreadId)),
        captchaProvider = captchaProviderSerialFormat.decodeFromString(CaptchaProvider.serializer(), get(captchaProviderColumn)),
        autoRemoveCommands = get(autoRemoveCommandsColumn),
        autoRemoveEvents = get(autoRemoveEventsColumn),
        enabled = get(enabledColumn),
        kickOnUnsuccess = get(kickOnUnsuccessColumn),
        casEnabled = get(casColumn),
        reactOnJoinRequest = get(reactOnJoinRequestColumn),
        autoPassKnown = get(autoPassKnownColumn),
    )

    override val selectById: ISqlExpressionBuilder.(IdChatIdentifier) -> Op<Boolean> = { chatIdColumn.eq(it.chatId.long) }
    override val ResultRow.asObject: ChatSettings
        get() = ChatSettings(
            chatId = asId,
            captchaProvider = captchaProviderSerialFormat.decodeFromString(CaptchaProvider.serializer(), get(captchaProviderColumn)),
            autoRemoveCommands = get(autoRemoveCommandsColumn),
            autoRemoveEvents = get(autoRemoveEventsColumn),
            enabled = get(enabledColumn),
            kickOnUnsuccess = get(kickOnUnsuccessColumn),
            casEnabled = get(casColumn),
            reactOnJoinRequest = get(reactOnJoinRequestColumn),
            autoPassKnown = get(autoPassKnownColumn),
        )

    init {
        initTable()
    }
}

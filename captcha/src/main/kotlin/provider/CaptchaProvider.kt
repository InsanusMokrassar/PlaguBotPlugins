package dev.inmo.plagubot.plugins.captcha.provider

import com.benasher44.uuid.uuid4
import com.soywiz.klock.*
import dev.inmo.kslog.common.e
import dev.inmo.kslog.common.logger
import dev.inmo.micro_utils.coroutines.*
import dev.inmo.plagubot.plugins.captcha.slotMachineReplyMarkup
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.delete
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.asSlotMachineReelImage
import dev.inmo.tgbotapi.extensions.utils.calculateSlotMachineResult
import dev.inmo.tgbotapi.extensions.utils.extensions.sameMessage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.InlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.ChatPermissions
import dev.inmo.tgbotapi.types.chat.*
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.dice.SlotMachineDiceAnimationType
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.utils.*
import dev.inmo.tgbotapi.utils.EntitiesBuilder
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.regular
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random

@Serializable
sealed class CaptchaProvider {
    abstract val checkTimeSpan: TimeSpan

    interface CaptchaProviderWorker {
        suspend fun BehaviourContext.doCaptcha(): Boolean

        suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean)
    }

    protected abstract suspend fun allocateWorker(
        eventDateTime: DateTime,
        chat: GroupChat,
        user: User,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    ): CaptchaProviderWorker

    suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        newUsers.map { user ->
            launch {
                createSubContextAndDoWithUpdatesFilter {
                    val worker = allocateWorker(
                        eventDateTime,
                        chat,
                        user,
                        leftRestrictionsPermissions,
                        adminsApi,
                        kickOnUnsuccess
                    )
                    val deferred = async {
                        runCatchingSafely {
                            with(worker) {
                                doCaptcha()
                            }
                        }.onFailure {
                            this@CaptchaProvider.logger.e("Unable to do captcha", it)
                        }.getOrElse { false }
                    }

                    val subscope = LinkedSupervisorScope()
                    subscope.launch {
                        delay((userBanDateTime - eventDateTime).millisecondsLong)
                        subscope.cancel()
                    }
                    subscope.launch {
                        deferred.await()
                        subscope.cancel()
                    }

                    subscope.coroutineContext.job.join()

                    val passed = runCatching {
                        deferred.getCompleted()
                    }.onFailure {
                        deferred.cancel()
                    }.getOrElse { false }

                    when {
                        passed -> {
                            safelyWithoutExceptions {
                                restrictChatMember(
                                    chat,
                                    user,
                                    permissions = leftRestrictionsPermissions
                                )
                            }
                        }
                        else -> {
                            send(chat, " ") {
                                +"User" + mention(user) + underline("didn't pass") + "captcha"
                            }
                            if (kickOnUnsuccess) {
                                banUser(chat, user, leftRestrictionsPermissions)
                            }
                        }
                    }
                    with(worker) {
                        onCloseCaptcha(passed)
                    }
                }
            }
        }.joinAll()
    }
}

internal const val cancelData = "cancel"

private fun EntitiesBuilder.mention(user: User, defaultName: String = "User"): EntitiesBuilder {
    return mention(
        listOfNotNull(
            user.lastName.takeIf { it.isNotBlank() }, user.firstName.takeIf { it.isNotBlank() }
        ).takeIf {
            it.isNotEmpty()
        }?.joinToString(" ") ?: defaultName,
        user
    )
}

private suspend fun BehaviourContext.sendAdminCanceledMessage(
    chat: Chat,
    captchaSolver: User,
    admin: User
) {
    safelyWithoutExceptions {
        send(
            chat
        ) {
            mention(admin, "Admin")
            regular(" cancelled captcha for ")
            mention(captchaSolver)
        }
    }
}

private suspend fun BehaviourContext.banUser(
    chat: PublicChat,
    user: User,
    leftRestrictionsPermissions: ChatPermissions,
    onFailure: suspend BehaviourContext.(Throwable) -> Unit = {
        safelyWithResult {
            send(
                chat
            ) {
                mention(user)
                +"failed captcha"
            }
        }
    }
): Result<Boolean> = safelyWithResult {
    restrictChatMember(chat, user, permissions = leftRestrictionsPermissions)
    banChatMember(chat, user)
}.onFailure {
    onFailure(it)
}

@Serializable
data class SlotMachineCaptchaProvider(
    val checkTimeSeconds: Seconds = 60,
    val captchaText: String = "Solve this captcha: "
) : CaptchaProvider() {
    @Transient
    override val checkTimeSpan = checkTimeSeconds.seconds

    private inner class Worker(
        private val chat: GroupChat,
        private val user: User,
        private val adminsApi: AdminsCacheAPI?
    ) : CaptchaProviderWorker {
        private val messagesToDelete = mutableListOf<Message>()

        override suspend fun BehaviourContext.doCaptcha(): Boolean {
            val baseBuilder: EntitiesBuilderBody = {
                mention(user)
                regular(", $captchaText")
            }
            val sentMessage = send(
                chat
            ) {
                baseBuilder()
                +": ✖✖✖"
            }.also { messagesToDelete.add(it) }
            val sentDice = sendDice(
                sentMessage.chat,
                SlotMachineDiceAnimationType,
                replyToMessageId = sentMessage.messageId,
                replyMarkup = slotMachineReplyMarkup(adminsApi != null)
            ).also { messagesToDelete.add(it) }
            val reels = sentDice.content.dice.calculateSlotMachineResult()!!
            val leftToClick = mutableListOf(
                reels.left.asSlotMachineReelImage.text,
                reels.center.asSlotMachineReelImage.text,
                reels.right.asSlotMachineReelImage.text
            )
            val clicked = mutableListOf<String>()
            fun buildTemplate() = "${clicked.joinToString("")}${leftToClick.joinToString("") { "✖" }}"

            waitMessageDataCallbackQuery().filter {
                when {
                    !it.message.sameMessage(sentDice) -> false
                    it.data == cancelData && adminsApi ?.isAdmin(chat.id, it.user.id) == true -> return@filter true
                    it.data == cancelData && adminsApi ?.isAdmin(chat.id, it.user.id) != true -> {
                        answer(
                            it,
                            "This button is only for admins"
                        )
                        false
                    }
                    it.user.id != user.id -> {
                        answer(it, "This button is not for you")
                        false
                    }
                    it.data != leftToClick.first() -> {
                        answer(it, "Nope")
                        false
                    }
                    else -> {
                        clicked.add(leftToClick.removeFirst())
                        answer(it, "Ok, next one")
                        edit(sentMessage) {
                            baseBuilder()
                            +": ${buildTemplate()}"
                        }
                        leftToClick.isEmpty()
                    }
                }
            }.first()

            return true
        }

        override suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean) {
            while (messagesToDelete.isNotEmpty()) {
                runCatchingSafely { delete(messagesToDelete.removeFirst()) }
            }
        }

    }

    override suspend fun allocateWorker(
        eventDateTime: DateTime,
        chat: GroupChat,
        user: User,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    ): CaptchaProviderWorker = Worker(chat, user, adminsApi)
}

@Serializable
data class SimpleCaptchaProvider(
    val checkTimeSeconds: Seconds = 60,
    val captchaText: String = "press this button to pass captcha:",
    val buttonText: String = "Press me\uD83D\uDE0A"
) : CaptchaProvider() {
    @Transient
    override val checkTimeSpan = checkTimeSeconds.seconds

    private inner class Worker(
        private val chat: GroupChat,
        private val user: User,
        private val adminsApi: AdminsCacheAPI?
    ) : CaptchaProviderWorker {
        private var sentMessage: Message? = null
        override suspend fun BehaviourContext.doCaptcha(): Boolean {
            val callbackData = uuid4().toString()
            val sentMessage = send(
                chat,
                replyMarkup = inlineKeyboard {
                    row {
                        dataButton(buttonText, callbackData)
                    }
                    if (adminsApi != null) {
                        row {
                            dataButton("Cancel (Admins only)", cancelData)
                        })
                    }
                }
            ) {
                mention(user)
                regular(", $captchaText")
            }
            this@Worker.sentMessage = sentMessage

            val pushed = waitMessageDataCallbackQuery().filter {
                when {
                    !it.message.sameMessage(sentMessage) -> false
                    it.data == callbackData && it.user.id == user.id -> true
                    it.data == cancelData && (adminsApi ?.isAdmin(chat.id, it.user.id) == true) -> true
                    it.data == callbackData -> {
                        answer(it, "This button is not for you")
                        false
                    }
                    it.data == cancelData -> {
                        answer(it, "This button is for admins only")
                        false
                    }
                    else -> false
                }
            }.first()

            answer(
                pushed,
                when (pushed.data) {
                    cancelData -> "You have cancelled captcha"
                    else -> "Ok, thanks"
                }
            )

            return true
        }

        override suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean) {
            sentMessage ?.let {
                delete(it)
            }
        }

    }

    override suspend fun allocateWorker(
        eventDateTime: DateTime,
        chat: GroupChat,
        user: User,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    ): CaptchaProviderWorker = Worker(chat, user, adminsApi)
}

private object ExpressionBuilder {
    sealed class ExpressionOperation {
        object PlusExpressionOperation : ExpressionOperation() {
            override fun asString(): String = "+"

            override fun Int.perform(other: Int): Int = plus(other)
        }

        object MinusExpressionOperation : ExpressionOperation() {
            override fun asString(): String = "-"

            override fun Int.perform(other: Int): Int = minus(other)
        }

        abstract fun asString(): String
        abstract fun Int.perform(other: Int): Int
    }

    private val experssions =
        listOf(ExpressionOperation.PlusExpressionOperation, ExpressionOperation.MinusExpressionOperation)

    private fun createNumber(max: Int) = Random.nextInt(max + 1)
    fun generateResult(max: Int, operationsNumber: Int = 1): Int {
        val operations = (0 until operationsNumber).map { experssions.random() }
        var current = createNumber(max)
        operations.forEach {
            val rightOne = createNumber(max)
            current = it.run { current.perform(rightOne) }
        }
        return current
    }

    fun createExpression(max: Int, operationsNumber: Int = 1): Pair<Int, String> {
        val operations = (0 until operationsNumber).map { experssions.random() }
        var current = createNumber(max)
        var numbersString = "$current"
        operations.forEach {
            val rightOne = createNumber(max)
            current = it.run { current.perform(rightOne) }
            numbersString += " ${it.asString()} $rightOne"
        }
        return current to numbersString
    }
}

@Serializable
data class ExpressionCaptchaProvider(
    val checkTimeSeconds: Seconds = 60,
    val captchaText: String = "Solve next captcha:",
    val leftRetriesText: String = "Nope, left retries: ",
    val maxPerNumber: Int = 10,
    val operations: Int = 2,
    val answers: Int = 6,
    val attempts: Int = 3
) : CaptchaProvider() {
    @Transient
    override val checkTimeSpan = checkTimeSeconds.seconds

    private inner class Worker(
        private val chat: GroupChat,
        private val user: User,
        private val adminsApi: AdminsCacheAPI?
    ) : CaptchaProviderWorker {
        private var sentMessage: Message? = null
        override suspend fun BehaviourContext.doCaptcha(): Boolean {
            val callbackData = ExpressionBuilder.createExpression(
                maxPerNumber,
                operations
            )
            val correctAnswer = callbackData.first.toString()
            val answers = (0 until answers - 1).map {
                ExpressionBuilder.generateResult(maxPerNumber, operations)
            }.toMutableList().also { orderedAnswers ->
                val correctAnswerPosition = Random.nextInt(orderedAnswers.size)
                orderedAnswers.add(correctAnswerPosition, callbackData.first)
            }.toList()
            val sentMessage = send(
                chat,
                replyMarkup = inlineKeyboard {
                    answers.map {
                        CallbackDataInlineKeyboardButton(it.toString(), it.toString())
                    }.chunked(3).forEach(::add)
                    if (adminsApi != null) {
                        row {
                            dataButton("Cancel (Admins only)", cancelData)
                        })
                    }
                }
            ) {
                mention(user)
                regular(", $captchaText ")
                bold(callbackData.second)
            }.also {
                sentMessage = it
            }

            var leftAttempts = attempts
            return waitMessageDataCallbackQuery().takeWhile { leftAttempts > 0 }.mapNotNull { query ->
                val baseCheck = query.message.messageId == sentMessage.messageId
                val dataCorrect = (query.user.id == user.id && query.data == correctAnswer)
                val adminCanceled = (query.data == cancelData && (adminsApi?.isAdmin(
                    sentMessage.chat.id,
                    query.user.id
                )) == true)
                baseCheck && if (dataCorrect || adminCanceled) {
                    if (adminCanceled) {
                        sendAdminCanceledMessage(
                            sentMessage.chat,
                            user,
                            query.user
                        )
                    }
                    true
                } else {
                    leftAttempts--
                    if (leftAttempts > 0) {
                        answerCallbackQuery(query, leftRetriesText + leftAttempts)
                        return@mapNotNull null
                    } else {
                        false
                    }
                }
            }.firstOrNull() ?: false
        }

        override suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean) {
            sentMessage ?.let {
                delete(it)
            }
        }
    }

    override suspend fun allocateWorker(
        eventDateTime: DateTime,
        chat: GroupChat,
        user: User,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    ): CaptchaProviderWorker = Worker(chat, user, adminsApi)
}


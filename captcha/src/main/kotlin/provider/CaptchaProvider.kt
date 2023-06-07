package dev.inmo.plagubot.plugins.captcha.provider

import com.benasher44.uuid.uuid4
import korlibs.time.DateTime
import korlibs.time.TimeSpan
import korlibs.time.seconds
import dev.inmo.kslog.common.e
import dev.inmo.kslog.common.logger
import dev.inmo.micro_utils.coroutines.LinkedSupervisorScope
import dev.inmo.micro_utils.coroutines.runCatchingSafely
import dev.inmo.micro_utils.coroutines.safelyWithResult
import dev.inmo.micro_utils.coroutines.safelyWithoutExceptions
import dev.inmo.micro_utils.repos.add
import dev.inmo.plagubot.plugins.captcha.db.UsersPassInfoRepo
import dev.inmo.plagubot.plugins.captcha.slotMachineReplyMarkup
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.approveChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.declineChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.restrictChatMember
import dev.inmo.tgbotapi.extensions.api.delete
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.api.send.sendDice
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.createSubContextAndDoWithUpdatesFilter
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.asSlotMachineReelImage
import dev.inmo.tgbotapi.extensions.utils.calculateSlotMachineResult
import dev.inmo.tgbotapi.extensions.utils.extensions.sameMessage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.types.Seconds
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.ChatPermissions
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.dice.SlotMachineDiceAnimationType
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.toChatId
import dev.inmo.tgbotapi.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random

@Serializable
sealed class CaptchaProvider {
    abstract val checkTimeSpan: TimeSpan
    abstract val complexity: Complexity

    interface CaptchaProviderWorker {
        suspend fun BehaviourContext.doCaptcha(): Boolean?

        suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean?)
    }

    protected abstract suspend fun allocateWorker(
        eventDateTime: DateTime,
        chat: GroupChat,
        user: User,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean,
        reactOnJoinRequest: Boolean
    ): CaptchaProviderWorker

    suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean,
        joinRequest: Boolean,
        usersPassInfoRepo: UsersPassInfoRepo
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
                        kickOnUnsuccess,
                        joinRequest
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
                        passed == null -> {
                            send(chat, " ") {
                                +"User" + mention(user) + underline("blocked me") + ", so, I can't perform check with captcha"
                            }
                        }
                        passed -> {
                            usersPassInfoRepo.add(
                                user.id,
                                UsersPassInfoRepo.PassInfo(
                                    chat.id.toChatId(),
                                    true,
                                    complexity
                                )
                            )
                            if (joinRequest) {
                                safelyWithoutExceptions {
                                    approveChatJoinRequest(chat, user)
                                }
                            } else {
                                safelyWithoutExceptions {
                                    restrictChatMember(
                                        chat,
                                        user,
                                        permissions = leftRestrictionsPermissions
                                    )
                                }
                            }
                        }
                        else -> {
                            usersPassInfoRepo.add(
                                user.id,
                                UsersPassInfoRepo.PassInfo(
                                    chat.id.toChatId(),
                                    false,
                                    complexity
                                )
                            )
                            send(chat, " ") {
                                +"User" + mention(user) + underline("didn't pass") + "captcha"
                            }

                            when {
                                joinRequest -> runCatchingSafely { declineChatJoinRequest(chat.id, user.id) }
                                kickOnUnsuccess -> banChatMember(chat.id, user)
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
    override val complexity: Complexity
        get() = Complexity.Medium

    private inner class Worker(
        private val chat: GroupChat,
        private val user: User,
        private val adminsApi: AdminsCacheAPI?,
        private val writeUserDirectly: Boolean
    ) : CaptchaProviderWorker {
        private val messagesToDelete = mutableListOf<Message>()

        override suspend fun BehaviourContext.doCaptcha(): Boolean? {
            val baseBuilder: EntitiesBuilderBody = {
                mention(user)
                regular(", $captchaText")
            }
            val sentMessage = runCatchingSafely {
                send(
                    if (writeUserDirectly) user else chat,
                ) {
                    baseBuilder()
                    +": ✖✖✖"
                }.also { messagesToDelete.add(it) }
            }.getOrElse {
                return null
            }
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
                        runCatchingSafely {
                            answer(
                                it,
                                "This button is only for admins"
                            )
                        }
                        false
                    }
                    it.user.id != user.id -> {
                        runCatchingSafely { answer(it, "This button is not for you") }
                        false
                    }
                    it.data != leftToClick.first() -> {
                        runCatchingSafely { answer(it, "Nope") }
                        false
                    }
                    else -> {
                        clicked.add(leftToClick.removeFirst())
                        runCatchingSafely { answer(it, "Ok, next one") }
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

        override suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean?) {
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
        kickOnUnsuccess: Boolean,
        reactOnJoinRequest: Boolean
    ): CaptchaProviderWorker = Worker(chat, user, adminsApi, reactOnJoinRequest)
}

@Serializable
data class SimpleCaptchaProvider(
    val checkTimeSeconds: Seconds = 60,
    val captchaText: String = "press this button to pass captcha:",
    val buttonText: String = "Press me\uD83D\uDE0A"
) : CaptchaProvider() {
    @Transient
    override val checkTimeSpan = checkTimeSeconds.seconds
    override val complexity: Complexity
        get() = Complexity.Easy

    private inner class Worker(
        private val chat: GroupChat,
        private val user: User,
        private val adminsApi: AdminsCacheAPI?,
        private val writeUserDirectly: Boolean
    ) : CaptchaProviderWorker {
        private var sentMessage: Message? = null
        override suspend fun BehaviourContext.doCaptcha(): Boolean? {
            val callbackData = uuid4().toString()
            val sentMessage = runCatchingSafely {
                send(
                    if (writeUserDirectly) user else chat,
                    replyMarkup = inlineKeyboard {
                        row {
                            dataButton(buttonText, callbackData)
                        }
                        if (adminsApi != null && !writeUserDirectly) {
                            row {
                                dataButton("Cancel (Admins only)", cancelData)
                            }
                        }
                    }
                ) {
                    mention(user)
                    regular(", $captchaText")
                }
            }.getOrElse {
                return null
            }
            this@Worker.sentMessage = sentMessage

            val pushed = waitMessageDataCallbackQuery().filter {
                when {
                    !it.message.sameMessage(sentMessage) -> false
                    it.data == callbackData && it.user.id == user.id -> true
                    !writeUserDirectly && it.data == cancelData && (adminsApi ?.isAdmin(chat.id, it.user.id) == true) -> true
                    it.data == callbackData -> {
                        runCatchingSafely {
                            answer(it, "This button is not for you")
                        }
                        false
                    }
                    it.data == cancelData -> {
                        runCatchingSafely {
                            answer(it, "This button is for admins only")
                        }
                        false
                    }
                    else -> false
                }
            }.first()

            runCatchingSafely {
                answer(
                    pushed,
                    when (pushed.data) {
                        cancelData -> "You have cancelled captcha"
                        else -> "Ok, thanks"
                    }
                )
            }

            return true
        }

        override suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean?) {
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
        kickOnUnsuccess: Boolean,
        reactOnJoinRequest: Boolean
    ): CaptchaProviderWorker = Worker(chat, user, adminsApi, reactOnJoinRequest)
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
    override val complexity: Complexity by lazy {
        var base = Complexity.Medium.weight
        val operationWeight = (Complexity.Medium.weight - Complexity.Easy.weight) / 3
        val answerWeight = (Complexity.Medium.weight - Complexity.Easy.weight) / 3
        base += operationWeight * operations.coerceIn(0, 6)
        base += answerWeight * (answers - attempts).coerceIn(-3, 3)
        Complexity.Custom(base)
    }

    private inner class Worker(
        private val chat: GroupChat,
        private val user: User,
        private val adminsApi: AdminsCacheAPI?,
        private val reactOnJoinRequest: Boolean
    ) : CaptchaProviderWorker {
        private var sentMessage: Message? = null
        override suspend fun BehaviourContext.doCaptcha(): Boolean? {
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
            val sentMessage = runCatchingSafely {
                send(
                    if (reactOnJoinRequest) user else chat,
                    replyMarkup = inlineKeyboard {
                        answers.map {
                            CallbackDataInlineKeyboardButton(it.toString(), it.toString())
                        }.chunked(3).forEach(::add)
                        if (adminsApi != null && !reactOnJoinRequest) {
                            row {
                                dataButton("Cancel (Admins only)", cancelData)
                            }
                        }
                    }
                ) {
                    mention(user)
                    regular(", $captchaText ")
                    bold(callbackData.second)
                }.also {
                    sentMessage = it
                }
            }.getOrElse {
                return null
            }

            var leftAttempts = attempts
            return waitMessageDataCallbackQuery().filter {
                it.message.sameMessage(sentMessage)
            }.takeWhile { leftAttempts > 0 }.filter { query ->
                when {
                    !reactOnJoinRequest && adminsApi ?.isAdmin(sentMessage.chat.id, query.user.id) == true && query.data == cancelData -> {
                        sendAdminCanceledMessage(
                            sentMessage.chat,
                            user,
                            query.user
                        )
                        true
                    }
                    query.user.id != user.id -> {
                        runCatchingSafely {
                            answer(query, "It is not for you :)")
                        }
                        false
                    }
                    query.data == correctAnswer -> {
                        true
                    }
                    else -> {
                        leftAttempts--
                        if (leftAttempts > 0) {
                            runCatchingSafely {
                                answer(query, leftRetriesText + leftAttempts)
                            }
                        }
                        false
                    }
                }
            }.firstOrNull() != null
        }

        override suspend fun BehaviourContext.onCloseCaptcha(passed: Boolean?) {
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
        kickOnUnsuccess: Boolean,
        reactOnJoinRequest: Boolean
    ): CaptchaProviderWorker = Worker(chat, user, adminsApi, reactOnJoinRequest)
}


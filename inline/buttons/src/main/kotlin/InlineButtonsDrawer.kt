package dev.inmo.plagubot.plugins.inline.buttons

import dev.inmo.plagubot.plugins.inline.buttons.utils.InlineButtonsKeys
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.*
import org.koin.core.Koin

/**
 * Buttons drawer with context info
 */
interface InlineButtonsDrawer {
    /**
     * Title of drawer to show on buttons
     */
    val name: String

    /**
     * Identifier of drawer which will be used on button as data
     */
    val id: String

    /**
     * Supported keys of drawer. If null - this drawer will be used with all keys
     *
     * Default value is a list with single parameter [InlineButtonsKeys.Settings], which
     * means that this drawer will be used only in settings request
     */
    val keys: Set<String?>?
        get() = setOf(InlineButtonsKeys.Settings)

    /**
     * This method will be called when message editing will be called. It is assumed that the drawer will
     * edit message by itself. In case you want to provide work with "Back" button, you should retrieve [InlineButtonsDrawer]
     * from [Koin]
     */
    suspend fun BehaviourContext.drawInlineButtons(chatId: IdChatIdentifier, userId: UserId, messageId: MessageId, key: String?)

    suspend fun BehaviourContext.drawInlineButtons(
        chatId: ChatId,
        userId: UserId,
        messageId: MessageId
    ) = drawInlineButtons(chatId, userId, messageId, null)

    suspend fun BehaviourContext.setupReactions(koin: Koin) {}
}

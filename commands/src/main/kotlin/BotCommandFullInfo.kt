package dev.inmo.plagubot.plugins.commands

import dev.inmo.micro_utils.language_codes.IetfLang
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import kotlinx.serialization.Serializable

/**
 * Full info about the command its [key] and the [command] itself
 *
 * @see full
 */
@Serializable
data class BotCommandFullInfo(
    val key: CommandsKeeperKey,
    val command: BotCommand
) {
    constructor(command: BotCommand) : this(CommandsKeeperKey.DEFAULT, command)
}

fun BotCommand.full(
    key: CommandsKeeperKey = CommandsKeeperKey.DEFAULT
) = BotCommandFullInfo(key, this)

fun BotCommand.full(
    scope: BotCommandScope
) = full(CommandsKeeperKey(scope))

fun BotCommand.full(
    languageCode: String
) = full(CommandsKeeperKey(languageCode = languageCode))

fun BotCommand.full(
    languageCode: IetfLang
) = full(CommandsKeeperKey(BotCommandScope.Default, languageCode = languageCode))

fun BotCommand.full(
    scope: BotCommandScope,
    languageCode: String
) = full(CommandsKeeperKey(scope, languageCode))

fun BotCommand.full(
    scope: BotCommandScope,
    languageCode: IetfLang
) = full(CommandsKeeperKey(scope, languageCode))

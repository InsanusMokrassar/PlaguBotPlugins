package dev.inmo.plagubot.plugins.commands

import dev.inmo.micro_utils.language_codes.IetfLanguageCode
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
import kotlinx.serialization.Serializable

/**
 * Full info about the command scope including [BotCommandScope] and its optional language code (see [languageCode] and
 * [languageCodeIetf])
 *
 * @see CommandsKeeperKey.DEFAULT
 */
@Serializable
@JvmInline
value class CommandsKeeperKey(
    val key: Pair<BotCommandScope, String?>
) {
    val scope: BotCommandScope
        get() = key.first
    val languageCode: String?
        get() = key.second
    val languageCodeIetf: IetfLanguageCode?
        get() = languageCode ?.let(::IetfLanguageCode)

    constructor(scope: BotCommandScope = BotCommandScope.Default, languageCode: String? = null) : this(scope to languageCode)
    constructor(scope: BotCommandScope, languageCode: IetfLanguageCode) : this(scope to languageCode.code)

    companion object {
        /**
         * Default realization of [CommandsKeeperKey] with null [languageCode] and [BotCommandScope.Default] [scope]
         */
        val DEFAULT = CommandsKeeperKey()
    }
}

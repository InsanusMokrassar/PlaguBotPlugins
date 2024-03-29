package dev.inmo.plagubot.plugins.commands

import dev.inmo.micro_utils.language_codes.IetfLang
import dev.inmo.tgbotapi.types.commands.BotCommandScope
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
    val languageCodeIetf: IetfLang?
        get() = languageCode?.let(::IetfLang)

    constructor(scope: BotCommandScope = BotCommandScope.Default, languageCode: String? = null) : this(scope to languageCode)
    constructor(scope: BotCommandScope, languageCode: IetfLang) : this(scope to languageCode.code)

    companion object {
        /**
         * Default realization of [CommandsKeeperKey] with null [languageCode] and [BotCommandScope.Default] [scope]
         */
        val DEFAULT = CommandsKeeperKey()
    }
}

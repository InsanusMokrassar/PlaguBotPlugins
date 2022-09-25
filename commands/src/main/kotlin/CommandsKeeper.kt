package dev.inmo.plagubot.plugins.commands

import dev.inmo.micro_utils.language_codes.IetfLanguageCode
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.commands.BotCommandScope
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In memory commands keeper. Contains all the registered commands inside and can be useful in case you wish to
 * [addCommand] or [removeCommand]
 */
class CommandsKeeper(
    preset: List<BotCommandFullInfo> = emptyList()
) {
    internal val onScopeChanged = MutableSharedFlow<CommandsKeeperKey>()
    private val scopesCommands: MutableMap<CommandsKeeperKey, MutableSet<BotCommand>> = preset.groupBy {
        it.key
    }.mapValues { (_, v) ->
        v.map { it.command }.toMutableSet()
    }.toMutableMap()
    private val changesMutex = Mutex()

    suspend fun addCommand(scope: CommandsKeeperKey, command: BotCommand) {
        changesMutex.withLock {
            val added = scopesCommands.getOrPut(scope) {
                mutableSetOf()
            }.add(command)

            if (added) {
                onScopeChanged.emit(scope)
            }
        }
    }

    suspend fun addCommand(
        scope: BotCommandScope,
        command: BotCommand
    ) = addCommand(
        CommandsKeeperKey(scope, null),
        command
    )

    suspend fun addCommand(
        languageCode: String,
        command: BotCommand
    ) = addCommand(
        CommandsKeeperKey(languageCode = languageCode),
        command
    )

    suspend fun addCommand(
        languageCode: IetfLanguageCode,
        command: BotCommand
    ) = addCommand(
        CommandsKeeperKey(BotCommandScopeDefault, languageCode),
        command
    )

    suspend fun addCommand(
        scope: BotCommandScope,
        languageCode: IetfLanguageCode,
        command: BotCommand
    ) = addCommand(
        CommandsKeeperKey(scope, languageCode),
        command
    )

    suspend fun addCommand(
        scope: BotCommandScope,
        languageCode: String,
        command: BotCommand
    ) = addCommand(
        CommandsKeeperKey(scope, languageCode),
        command
    )

    suspend fun addCommand(
        command: BotCommand
    ) = addCommand(
        CommandsKeeperKey.DEFAULT,
        command
    )

    suspend fun removeCommand(scope: CommandsKeeperKey, command: BotCommand) {
        changesMutex.withLock {
            val removed = scopesCommands[scope] ?.remove(command) == true

            if (removed) {
                onScopeChanged.emit(scope)
            }
        }
    }

    suspend fun removeCommand(
        scope: BotCommandScope,
        command: BotCommand
    ) = removeCommand(
        CommandsKeeperKey(scope),
        command
    )

    suspend fun removeCommand(
        languageCode: String,
        command: BotCommand
    ) = removeCommand(
        CommandsKeeperKey(languageCode = languageCode),
        command
    )

    suspend fun removeCommand(
        languageCode: IetfLanguageCode,
        command: BotCommand
    ) = removeCommand(
        CommandsKeeperKey(BotCommandScopeDefault, languageCode),
        command
    )

    suspend fun removeCommand(
        scope: BotCommandScope,
        languageCode: IetfLanguageCode,
        command: BotCommand
    ) = removeCommand(
        CommandsKeeperKey(scope, languageCode),
        command
    )

    suspend fun removeCommand(
        scope: BotCommandScope,
        languageCode: String,
        command: BotCommand
    ) = removeCommand(
        CommandsKeeperKey(scope, languageCode),
        command
    )

    suspend fun removeCommand(
        command: BotCommand
    ) = removeCommand(
        CommandsKeeperKey.DEFAULT,
        command
    )

    internal fun getCommands(scope: CommandsKeeperKey) = scopesCommands[scope] ?.toList()
    internal fun getKeys() = scopesCommands.keys.toList()
}

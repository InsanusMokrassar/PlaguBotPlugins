package dev.inmo.plagubot.plugins.commands

import org.koin.core.Koin
import org.koin.core.scope.Scope

val Scope.commandsKeeper
    get() = get<CommandsKeeper>()

val Koin.commandsKeeper
    get() = get<CommandsKeeper>()

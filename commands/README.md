# PlaguBotCommandsPlugin

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/dev.inmo/plagubot.plugins.commands/badge.svg)](https://maven-badges.herokuapp.com/maven-central/dev.inmo/plagubot.plugins.commands)

This plugin has been created for centralized work with commands in your plugins. You may pass your commands (even in
runtime) and they will automatically appear in bot commands for users

## How to include

Add dependency:

Gradle:

```groovy
api "dev.inmo:plagubot.plugins.commands:$commands_version"
```

Maven:

```xml
<dependency>
    <groupId>dev.inmo</groupId>
    <artifactId>plagubot.plugins.commands</artifactId>
    <version>${commands_version}</version>
</dependency>
```

## How to use

End user should include in his plugins section next line:

```json
...
"plugins": [
  ...,
  "dev.inmo.plagubot.plugins.commands.CommandsPlugin"
],
...
```

Then, in your plugin you should register your commands. Just pass them inside `setupDI` as `BotCommandFullInfo`:

```kotlin
// ... Your plugin code
    override fun Module.setupDI(database: Database, params: JsonObject) {
        // ...
        single { BotCommand("commandExample", "Command description").full() }
        // ...
    }
// ...
```

You may pass info `full` extension:

* Any [BotCommandScope](https://tgbotapi.inmo.dev/docs/dev.inmo.tgbotapi.types.commands/-bot-command-scope/index.html)
* Some language code as `String` OR:
* Any [IetfLanguageCode](https://microutils.inmo.dev/micro_utils.dokka/dev.inmo.micro_utils.language_codes/%5Bcommon%5D-ietf-language-code/index.html)

### Runtime commands changing

In runtime you may change the commands of bot using `CommandsKeeper`. The instance of `CommandsKeeper` can be
retrieved from `koin` via simple `koin.get<CommandsKeeper>()` or `koin.commandsKeeper`:

```kotlin
// ...
    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val commandsKeeper = koin.commandsKeeper
        // ... Some your code
        commandsKeeper.addCommand(BotCommand("commandExample", "Command description"))
        // ... Some your code
    }
// ...
```

Just as in the code above (in `setupDI`) you may pass all the command environment and it will be automatically updated
for bot.

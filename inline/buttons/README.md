# PlaguBotCommandsPlugin

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/dev.inmo/plagubot.plugins.inline.buttons/badge.svg)](https://maven-badges.herokuapp.com/maven-central/dev.inmo/plagubot.plugins.inline.buttons)

This plugin has been created for centralized work with inline buttons in some message as settings.

## How to include

Add dependency:

Gradle:

```groovy
api "dev.inmo:plagubot.plugins.inline.buttons:$inline.buttons_version"
```

Maven:

```xml
<dependency>
    <groupId>dev.inmo</groupId>
    <artifactId>plagubot.plugins.inline.buttons</artifactId>
    <version>${inline.buttons_version}</version>
</dependency>
```

## How to use

End user should include in his plugins section next line:

```json
...
"plugins": [
  ...,
  "dev.inmo.plagubot.plugins.inline.buttons.InlineButtonsPlugin"
],
...
```

## Development

Then, in your plugin you should register your inline buttons. Just pass them inside `setupDI` as `BotCommandFullInfo`:

```kotlin
// ... Your plugin code
    override fun Module.setupDI(database: Database, params: JsonObject) {
        // ...
        single { InlineButtonsProvider("Button title", "button id") { chatId, userId, messageId -> /* callback to draw settings */ } }
        // ...
    }
// ...
```

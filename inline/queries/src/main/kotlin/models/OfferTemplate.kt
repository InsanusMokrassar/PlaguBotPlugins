package dev.inmo.plagubot.plugins.inline.queries.models

import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.InlineQueryResultArticle
import kotlinx.serialization.Serializable

@Serializable
data class OfferTemplate(
    val title: String,
    val formats: List<Format> = emptyList(),
    val description: String? = null
) {
    fun createArticleResult(id: String, query: String): InlineQueryResultArticle? = formats.firstOrNull {
        it.queryRegex.matches(query)
    } ?.createContent(query) ?.let { content ->
        InlineQueryResultArticle(
            id,
            title,
            content,
            description = description
        )
    }
}

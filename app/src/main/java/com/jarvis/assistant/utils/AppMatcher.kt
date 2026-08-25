package com.jarvis.assistant.utils

data class AppEntry(
    val label: String,
    val packageName: String,
)

/**
 * Resolves a spoken app name to installed apps.
 *
 * Kept free of any Android dependency so the ranking can be tested directly.
 * The rules are ordered strictest first, and anything that scores zero is
 * dropped rather than returned as a weak guess: opening the wrong app is worse
 * than admitting the name was not recognised.
 *
 * When more than one app survives, all of them come back. The caller is expected
 * to ask the user which one rather than silently taking the top row.
 */
object AppMatcher {

    fun rank(query: String, apps: List<AppEntry>): List<AppEntry> {
        val normalisedQuery = TextNormalizer.normaliseWords(query)
        if (normalisedQuery.isEmpty()) return emptyList()

        val rawQuery = query.trim().lowercase()
        val queryTokens = TextNormalizer.tokens(query)

        return apps
            .map { it to score(it, normalisedQuery, rawQuery, queryTokens) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }
                .thenBy { it.first.label.length })
            .map { it.first }
    }

    private fun score(
        app: AppEntry,
        normalisedQuery: String,
        rawQuery: String,
        queryTokens: List<String>,
    ): Int {
        val packageName = app.packageName.lowercase()
        if (packageName == rawQuery) return EXACT
        val label = TextNormalizer.normaliseWords(app.label)
        val labelTokens = label.split(' ').filter { it.isNotBlank() }

        return when {
            label == normalisedQuery -> EXACT
            label.startsWith("$normalisedQuery ") || label.startsWith(normalisedQuery) -> PREFIX
            labelTokens.any { it == normalisedQuery } -> WHOLE_WORD
            label.contains(normalisedQuery) -> SUBSTRING
            queryTokens.isNotEmpty() && queryTokens.all { it in labelTokens } -> ALL_TOKENS
            packageName.contains(normalisedQuery.replace(" ", "")) -> PACKAGE_HINT
            else -> 0
        }
    }

    private const val EXACT = 100
    private const val PREFIX = 80
    private const val WHOLE_WORD = 70
    private const val SUBSTRING = 55
    private const val ALL_TOKENS = 40
    private const val PACKAGE_HINT = 25
}

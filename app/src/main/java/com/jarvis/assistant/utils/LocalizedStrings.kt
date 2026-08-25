package com.jarvis.assistant.utils

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.jarvis.assistant.core.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves strings in the language JARVIS is currently speaking, which is not
 * necessarily the phone's language.
 *
 * A user with an English phone who has set JARVIS to Vietnamese should hear
 * Vietnamese, and Auto mode has to be able to answer in whichever language the
 * last utterance was in. Plain `context.getString` would follow the system
 * locale and get both cases wrong, so every spoken or displayed string that
 * depends on the assistant language goes through here.
 */
@Singleton
class LocalizedStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cache = mutableMapOf<String, Context>()

    fun get(language: Language, @StringRes id: Int, vararg args: Any): String {
        val resolved = language.resolve()
        val localised = contextFor(resolved)
        return if (args.isEmpty()) {
            localised.getString(id)
        } else {
            localised.getString(id, *args)
        }
    }

    @Synchronized
    private fun contextFor(language: Language): Context = cache.getOrPut(language.tag) {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(language.locale)
        }
        context.createConfigurationContext(configuration)
    }
}

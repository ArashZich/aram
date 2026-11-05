package ir.arashzich.aram

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language chosen in Settings. Android 13 and later keep it themselves and restart the screens;
 * older phones have no per-app language, so Aram keeps it and wraps its own contexts.
 */
object Lang {
    const val PHONE = 0
    const val PERSIAN = 1
    const val ENGLISH = 2
    val all = listOf(PHONE, PERSIAN, ENGLISH)

    private val tags = mapOf(PHONE to "", PERSIAN to "fa", ENGLISH to "en")

    fun label(language: Int) = when (language) {
        PERSIAN -> R.string.lang_fa
        ENGLISH -> R.string.lang_en
        else -> R.string.lang_phone
    }

    fun current(c: Context): Int {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.language.orEmpty()
        } else Store(c).language
        return tags.entries.firstOrNull { it.value == tag }?.key ?: PHONE
    }

    fun set(c: Context, language: Int) {
        val tag = tags.getValue(language)
        if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            Store(c).language = tag
            c.startActivity(Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    /** For attachBaseContext on phones before Android 13; later ones already give the right context. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = Store(base).language
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }
}

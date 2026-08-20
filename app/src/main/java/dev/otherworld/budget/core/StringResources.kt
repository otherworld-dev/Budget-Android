package dev.otherworld.budget.core

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Lets the ViewModel layer resolve string resources without holding a Composable context, so
 * user-facing copy lives in `strings.xml` (and is translatable) instead of as literals in Kotlin.
 *
 * A tiny interface precisely so the unit tests -- which construct ViewModels directly, with no
 * Hilt graph and no real Android [Context] -- can supply a fake in its place.
 */
interface StringResources {
    fun get(@StringRes id: Int): String
    fun get(@StringRes id: Int, vararg formatArgs: Any): String
}

class AndroidStringResources @Inject constructor(
    @ApplicationContext private val context: Context,
) : StringResources {
    override fun get(id: Int): String = context.getString(id)
    override fun get(id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)
}

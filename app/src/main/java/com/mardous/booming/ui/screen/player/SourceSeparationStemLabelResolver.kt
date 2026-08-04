package com.mardous.booming.ui.screen.player

import android.content.Context
import androidx.annotation.StringRes
import com.mardous.booming.R
import com.mardous.booming.separation.model.contract.normalizeStemLabel

object SourceSeparationStemLabelResolver {
    fun resolve(context: Context, canonicalLabel: String): String =
        resolve(canonicalLabel, context::getString)

    internal fun resolve(
        canonicalLabel: String,
        localizedText: (Int) -> String,
    ): String = resourceId(canonicalLabel)?.let(localizedText) ?: canonicalLabel

    @StringRes
    internal fun resourceId(canonicalLabel: String): Int? =
        LABEL_RESOURCES[normalizeStemLabel(canonicalLabel)]

    private val LABEL_RESOURCES = mapOf(
        "vocals" to R.string.source_separation_blend_vocals,
        "instrumental" to R.string.source_separation_blend_instrumental,
        "bass" to R.string.source_separation_stem_bass,
        "drums" to R.string.source_separation_stem_drums,
        "other" to R.string.source_separation_stem_other,
        "reverb" to R.string.source_separation_stem_reverb,
        "no crowd" to R.string.source_separation_stem_no_crowd,
        "guitar" to R.string.source_separation_stem_guitar,
        "piano" to R.string.source_separation_stem_piano,
        "target stem" to R.string.source_separation_stem_target,
        "remaining audio" to R.string.source_separation_stem_remaining_audio,
    )
}

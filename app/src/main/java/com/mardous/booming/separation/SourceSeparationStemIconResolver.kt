package com.mardous.booming.separation

import androidx.annotation.DrawableRes
import com.mardous.booming.R
import com.mardous.booming.separation.model.contract.normalizeStemLabel

object SourceSeparationStemIconResolver {
    @DrawableRes
    fun resourceId(canonicalLabel: String): Int = when (normalizeStemLabel(canonicalLabel)) {
        "vocals" -> R.drawable.ic_person_24dp
        "instrumental" -> R.drawable.ic_speaker_24dp
        "bass" -> R.drawable.ic_stem_bass_24dp
        "drums" -> R.drawable.ic_stem_drums_24dp
        "other" -> R.drawable.ic_speaker_24dp
        "remaining audio", "remaining_audio" -> R.drawable.ic_graphic_eq_24dp
        "reverb" -> R.drawable.ic_stem_reverb_24dp
        "no crowd", "no_crowd" -> R.drawable.ic_group_off_24dp
        "crowd" -> R.drawable.ic_group_24dp
        "guitar" -> R.drawable.ic_stem_guitar_24dp
        "piano" -> R.drawable.ic_stem_piano_24dp
        else -> R.drawable.ic_graphic_eq_24dp
    }
}

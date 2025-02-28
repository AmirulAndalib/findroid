package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.StringRes

data class PreferenceGroup(
    @StringRes val nameStringResource: Int? = null,
    val preferences: List<Preference>,
)

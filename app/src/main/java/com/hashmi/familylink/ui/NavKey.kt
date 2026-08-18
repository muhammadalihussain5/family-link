package com.hashmi.familylink.ui

import androidx.navigation3.runtime.NavKey as BaseNavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface NavKey : BaseNavKey {
    @Serializable
    data object SetupWizard : NavKey

    @Serializable
    data object ServerMain : NavKey

    @Serializable
    data object ClientMain : NavKey

    @Serializable
    data object ClientPermissions : NavKey

    @Serializable
    data object Settings : NavKey
}

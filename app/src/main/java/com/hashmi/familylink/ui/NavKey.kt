package com.hashmi.familylink.ui

import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.NavKey as BaseNavKey

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
}

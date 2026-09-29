package com.example.adb_connection.ui.wifi

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.adb_connection.ServiceLocator
import com.example.adb_connection.data.settings.SettingsRepository

class WifiSetupViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val appCtx = context.applicationContext
        return WifiSetupViewModel(
            wifiRepository = ServiceLocator.provideWifiRepository(appCtx),
            settingsRepository = SettingsRepository(appCtx)
        ) as T
    }
}

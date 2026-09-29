package com.example.adb_connection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.adb_connection.ui.navigation.AppNavigation
import com.example.adb_connection.ui.theme.Adb_ConnectionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Adb_ConnectionTheme {
                AppNavigation()
            }
        }
    }
}

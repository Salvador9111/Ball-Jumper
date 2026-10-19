package com.example.skyboundjumper

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Installs the debug provider only in debug builds. Never ship debug tokens in source. */
internal object AppCheckInstaller {
    fun install(context: Context) {
        val app = FirebaseApp.initializeApp(context) ?: return
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
    }
}

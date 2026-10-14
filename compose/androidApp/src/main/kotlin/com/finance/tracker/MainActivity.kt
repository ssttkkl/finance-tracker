package com.finance.tracker

import com.finance.tracker.app.App
import com.finance.tracker.app.isComposeDemoModeBuildEnabled
import com.finance.tracker.core.*

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val demoMode = BuildConfig.DEBUG && isComposeDemoModeBuildEnabled()
        if (!demoMode) AndroidTokenStoreContext.install(applicationContext)
        intent?.dataString?.let(::receiveIncomingInvitationUrl)

        setContent {
            App(demoMode = demoMode)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(::receiveIncomingInvitationUrl)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App("")
}

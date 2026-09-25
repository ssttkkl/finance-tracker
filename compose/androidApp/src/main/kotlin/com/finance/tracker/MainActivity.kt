package com.finance.tracker

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
        AndroidTokenStoreContext.install(applicationContext)
        intent?.dataString?.let(::receiveIncomingInvitationUrl)

        setContent {
            App(BuildConfig.FT_API_ORIGIN)
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

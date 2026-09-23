package com.watchpicture.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

class MainActivity : ComponentActivity(), NavigationEventDispatcherOwner {

    private val eventDispatcher = NavigationEventDispatcher()
    override val navigationEventDispatcher: NavigationEventDispatcher
        get() = eventDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) {
                App()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        eventDispatcher.dispose()
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                WatchPictureApp.instance.dispatchExternalArchive(uri)
            }
        }
    }
}

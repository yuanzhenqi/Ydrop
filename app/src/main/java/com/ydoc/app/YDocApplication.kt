package com.ydoc.app

import android.app.Application
import android.content.Context
import com.ydoc.app.data.AppContainer
import com.ydoc.app.quickrecord.QuickRecordShortcuts

class YDocApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        QuickRecordShortcuts.publishDynamicShortcut(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as YDocApplication).appContainer

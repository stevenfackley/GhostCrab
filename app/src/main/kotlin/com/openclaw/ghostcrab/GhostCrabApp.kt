package com.openclaw.ghostcrab

import android.app.Application
import com.openclaw.ghostcrab.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class GhostCrabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@GhostCrabApp)
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.ERROR)
            modules(appModule)
        }
    }
}

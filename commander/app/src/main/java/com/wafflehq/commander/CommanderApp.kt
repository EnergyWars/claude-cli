package com.wafflehq.commander

import android.app.Application
import com.wafflehq.commander.data.download.DownloadForegroundService
import com.wafflehq.commander.data.download.HostedFileDownloader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@HiltAndroidApp
class CommanderApp : Application() {

    @Inject
    lateinit var hostedFileDownloader: HostedFileDownloader

    override fun onCreate() {
        super.onCreate()
        hostedFileDownloader.activeTarget
            .onEach { target -> if (target != null) DownloadForegroundService.start(this) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}

package com.wafflehq.commander

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import com.wafflehq.commander.data.download.DownloadForegroundService
import com.wafflehq.commander.data.download.HostedFileDownloader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.system.exitProcess
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
        installCrashSafetyNet()
        hostedFileDownloader.activeTarget
            .onEach { target -> if (target != null) DownloadForegroundService.start(this) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    /**
     * Last-resort net: every API call is already caught locally, but if an unrelated bug still throws
     * uncaught, restart into MainActivity instead of showing the system crash dialog. The gate/nav logic
     * then re-evaluates the session on its own and lands on the login screen if it isn't valid.
     */
    private fun installCrashSafetyNet() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CommanderApp", "Uncaught exception - restarting instead of crashing", throwable)
            try {
                val restartIntent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(restartIntent)
            } catch (restartError: Exception) {
                Log.e("CommanderApp", "Restart after crash failed", restartError)
                previousHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}

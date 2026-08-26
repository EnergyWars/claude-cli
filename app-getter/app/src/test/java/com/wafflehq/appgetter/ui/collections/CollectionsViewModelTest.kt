package com.wafflehq.appgetter.ui.collections

import com.wafflehq.appgetter.data.api.ApiException
import com.wafflehq.appgetter.data.api.AppGetterApi
import com.wafflehq.appgetter.data.api.CollectedFile
import com.wafflehq.appgetter.data.api.CollectionList
import com.wafflehq.appgetter.data.discovery.NetworkDiscovery
import com.wafflehq.appgetter.data.install.ApkInstaller
import com.wafflehq.appgetter.data.install.DownloadPhase
import com.wafflehq.appgetter.data.install.DownloadStatus
import com.wafflehq.appgetter.data.settings.ServerOverride
import com.wafflehq.appgetter.data.settings.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val file = CollectedFile("test.apk", "2026-08-26T00:00:00.000Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(installer: ApkInstaller, api: AppGetterApi = mockk {
        coEvery { getCollections("host", 8787) } returns CollectionList(listOf(file))
    }): CollectionsViewModel {
        val settingsRepository = mockk<SettingsRepository> {
            every { serverOverride } returns flowOf(ServerOverride("host", 8787))
        }
        val viewModel = CollectionsViewModel(settingsRepository, mockk<NetworkDiscovery>(), api, installer)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `downloadingFileName is set while the download runs and cleared once it finishes`() = runTest(dispatcher) {
        val downloaded = File.createTempFile("appgetter-test", ".apk")
        val installer = mockk<ApkInstaller> {
            every { downloadStatus } returns MutableStateFlow(null)
            every { clearDownloadStatus() } just Runs
            coEvery { downloadFile("host", 8787, "test.apk") } coAnswers {
                delay(1_000)
                downloaded
            }
        }
        val viewModel = viewModel(installer)

        viewModel.downloadAndInstall(file)
        dispatcher.scheduler.runCurrent()
        assertEquals("test.apk", viewModel.uiState.value.downloadingFileName)

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(downloaded, viewModel.uiState.value.installFile)
        assertEquals("test.apk", viewModel.uiState.value.downloadingFileName)

        viewModel.consumeInstallFile()
        assertNull(viewModel.uiState.value.downloadingFileName)
        assertNull(viewModel.uiState.value.installFile)
    }

    @Test
    fun `a failed download clears downloadingFileName and reports the error`() = runTest(dispatcher) {
        val installer = mockk<ApkInstaller> {
            every { downloadStatus } returns MutableStateFlow(null)
            every { clearDownloadStatus() } just Runs
            coEvery { downloadFile("host", 8787, "test.apk") } throws ApiException(500, "Download fehlgeschlagen.")
        }
        val viewModel = viewModel(installer)

        viewModel.downloadAndInstall(file)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.downloadingFileName)
        assertEquals("Download fehlgeschlagen.", viewModel.uiState.value.error)
    }

    @Test
    fun `a second tap while downloading is ignored`() = runTest(dispatcher) {
        var callCount = 0
        val installer = mockk<ApkInstaller> {
            every { downloadStatus } returns MutableStateFlow(null)
            every { clearDownloadStatus() } just Runs
            coEvery { downloadFile("host", 8787, "test.apk") } coAnswers {
                callCount++
                delay(1_000)
                File.createTempFile("appgetter-test", ".apk")
            }
        }
        val viewModel = viewModel(installer)

        viewModel.downloadAndInstall(file)
        dispatcher.scheduler.runCurrent()
        viewModel.downloadAndInstall(file)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, callCount)
    }

    @Test
    fun `downloadStatus passes through the installer's status flow`() = runTest(dispatcher) {
        val statusFlow = MutableStateFlow<DownloadStatus?>(null)
        val installer = mockk<ApkInstaller> {
            every { downloadStatus } returns statusFlow
        }
        val viewModel = viewModel(installer)

        statusFlow.value = DownloadStatus(DownloadPhase.VERIFYING)

        assertEquals(DownloadPhase.VERIFYING, viewModel.downloadStatus.value?.phase)
    }
}

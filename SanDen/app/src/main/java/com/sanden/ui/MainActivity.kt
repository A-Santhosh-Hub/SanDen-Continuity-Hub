package com.sanden.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.sanden.data.transfer.LanTransferHub
import com.sanden.domain.repository.VaultRepository
import com.sanden.domain.usecase.ScanVaultUseCase
import com.sanden.ui.navigation.SanDenNavGraph
import com.sanden.ui.navigation.Screen
import com.sanden.ui.theme.SanDenTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var scanVaultUseCase: ScanVaultUseCase

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var lanTransferHub: LanTransferHub

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isPermissionGranted = checkStoragePermission()
        val isShareIntent = intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE
        val startDestination = when {
            !isPermissionGranted -> Screen.Onboarding.route
            isShareIntent -> Screen.Connect.route
            else -> Screen.Home.route
        }

        if (isPermissionGranted && isShareIntent) {
            handleIncomingShare(intent)
        }

        setContent {
            SanDenTheme {
                val navController = rememberNavController()
                SanDenNavGraph(
                    navController = navController,
                    startDestination = startDestination
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (checkStoragePermission()) {
            handleIncomingShare(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShare(shareIntent: Intent?) {
        val action = shareIntent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        lifecycleScope.launch {
            if (!lanTransferHub.isRunning.value) {
                lanTransferHub.start()
            }
            val sharedText = shareIntent.getStringExtra(Intent.EXTRA_TEXT)
            val uris = mutableListOf<Uri>()
            if (action == Intent.ACTION_SEND) {
                val u = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    shareIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    shareIntent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (u != null) uris.add(u)
            } else {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    shareIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    shareIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (list != null) uris.addAll(list)
            }

            if (uris.isNotEmpty()) {
                for (uri in uris) {
                    vaultRepository.importFile(uri, "").onSuccess { vf ->
                        lanTransferHub.sendPhoneFile(
                            filePath = vf.path,
                            fileName = vf.name,
                            fileSize = vf.size,
                            mimeType = vf.mimeType ?: "application/octet-stream",
                            channel = "General"
                        )
                    }
                }
                vaultRepository.scanVault()
            } else if (!sharedText.isNullOrBlank()) {
                lanTransferHub.sendPhoneMessage(sharedText, type = "TEXT", channel = "General")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkStoragePermission()) {
            vaultRepository.startWatching()
            if (!lanTransferHub.isRunning.value) {
                lanTransferHub.start()
            }
            lifecycleScope.launch {
                try {
                    scanVaultUseCase()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Scan failed", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vaultRepository.stopWatching()
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            read && write
        }
    }
}

package com.saikiran.pulse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.saikiran.pulse.camera.CameraScreen
import com.saikiran.pulse.ui.theme.PulseTheme

/**
 * Main Activity hosting the Pulse perception and UI interface.
 *
 * Hardware Key Shortcuts (Priority P2 Accessibility Feature):
 *  - Volume Up (Click): Triggers "WHAT JUST HAPPENED?" natural language summary query.
 *  - Volume Down (Click): Triggers "WHAT CHANGED?" state delta query.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        init {
            try {
                System.loadLibrary("mediapipe_tasks_vision_jni")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load mediapipe_tasks_vision_jni", e)
            }
        }
    }

    @Volatile
    private var volumeUpHandler: (() -> Unit)? = null

    @Volatile
    private var volumeDownHandler: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PulseTheme {
                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                var hasAudioPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { permissions ->
                        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
                        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
                    }
                )

                LaunchedEffect(Unit) {
                    val permissionsToRequest = mutableListOf<String>()
                    if (!hasCameraPermission) permissionsToRequest.add(Manifest.permission.CAMERA)
                    if (!hasAudioPermission) permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)

                    if (permissionsToRequest.isNotEmpty()) {
                        permissionsLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        if (hasCameraPermission) {
                            CameraScreen(
                                hasAudioPermission = hasAudioPermission,
                                onRegisterVolumeKeyHandlers = { up, down ->
                                    volumeUpHandler = up
                                    volumeDownHandler = down
                                }
                            )
                        } else {
                            Text(
                                text = "Camera permission is required to use Pulse.",
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpHandler?.let {
                    it.invoke()
                    return true
                }
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownHandler?.let {
                    it.invoke()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

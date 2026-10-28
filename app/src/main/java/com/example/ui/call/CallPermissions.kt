package com.example.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.util.LocalAppStrings
import com.example.util.EnTranslations

/**
 * Возвращает функцию `request(video) { ... }`: запрашивает микрофон (и камеру для видео, и уведомления
 * на Android 13+) и вызывает колбэк, только если микрофон разрешён. Если камеру не дали, звонок
 * идёт без видео.
 */
@Composable
fun rememberCallPermissionRequester(): (video: Boolean, onReady: () -> Unit) -> Unit {
    val context = LocalContext.current
    val en = LocalAppStrings.current === EnTranslations
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val action = pending[0]
        pending[0] = null
        if (micOk) action?.invoke()
        else Toast.makeText(
            context,
            if (en) "Microphone permission is required for calls" else "Для звонков нужен доступ к микрофону",
            Toast.LENGTH_LONG
        ).show()
    }
    return { video, onReady ->
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onReady()
        else {
            pending[0] = onReady
            launcher.launch(needed.toTypedArray())
        }
    }
}

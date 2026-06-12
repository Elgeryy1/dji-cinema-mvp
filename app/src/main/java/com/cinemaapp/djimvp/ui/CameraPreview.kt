package com.cinemaapp.djimvp.ui

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.cinemaapp.djimvp.MainViewModel

@Composable
fun CameraPreview(
    viewModel: MainViewModel,
    rebindKey: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = context as LifecycleOwner
    val previewView = remember(configuration.orientation) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    LaunchedEffect(lifecycleOwner, previewView, rebindKey, configuration.orientation) {
        viewModel.bindCamera(lifecycleOwner, previewView)
    }
    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )
}

/**
 * Preview en vivo durante la grabacion Camera2. Su superficie se registra en el ViewModel y se
 * añade como segundo target de la sesion Camera2, de modo que la pantalla NO se congela al grabar.
 */
@Composable
fun RecordingPreview(
    viewModel: MainViewModel,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        viewModel.setRecordingPreviewSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
                        viewModel.setRecordingPreviewSurface(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                        viewModel.setRecordingPreviewSurface(null)
                    }
                })
            }
        },
        update = { view ->
            if (width > 0 && height > 0) {
                view.holder.setFixedSize(width, height)
            }
        }
    )
}

package com.rainy.screenshot.ui.preview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.rainy.screenshot.R
import com.rainy.screenshot.ui.components.RainyBackground
import com.rainy.screenshot.ui.theme.RainyScreenShotTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 预览页（悬浮球入口）：图片可缩放查看，视频内嵌播放。
 * 入口参数：EXTRA_PATH（文件路径）+ EXTRA_IS_VIDEO（是否视频）。
 */
class PreviewActivity : AppCompatActivity() {

    /** 当前视频实例（onPause 暂停，防止后台继续出声）。 */
    private var activeVideoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        // 旋转重建后从上次播放位置续播
        val resumePos = savedInstanceState?.getInt(STATE_VIDEO_POS, 0) ?: 0
        setContent {
            RainyScreenShotTheme {
                RainyBackground {
                    PreviewScreen(
                        file = File(path),
                        isVideo = isVideo,
                        resumeVideoPos = resumePos,
                        onVideoReady = { activeVideoView = it },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onPause() {
        activeVideoView?.let { if (it.isPlaying) it.pause() }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        activeVideoView?.let { outState.putInt(STATE_VIDEO_POS, it.currentPosition) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        activeVideoView?.stopPlayback()
        activeVideoView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "preview_path"
        const val EXTRA_IS_VIDEO = "preview_is_video"
        private const val STATE_VIDEO_POS = "preview_video_pos"

        fun start(context: Context, file: File, isVideo: Boolean) {
            val intent = Intent(context, PreviewActivity::class.java).apply {
                putExtra(EXTRA_PATH, file.absolutePath)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(
    file: File,
    isVideo: Boolean,
    resumeVideoPos: Int = 0,
    onVideoReady: (VideoView?) -> Unit = {},
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(file.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, color = Color.White)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f))
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (isVideo) {
                VideoPreview(file, resumeVideoPos, onVideoReady)
            } else {
                ImagePreview(file)
            }
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 异步解码（大图防主线程掉帧）
    var bitmapState by remember(file.absolutePath) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var decodeFailed by remember(file.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(file.absolutePath) {
        decodeFailed = false
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                var sample = 1
                while (opts.outWidth / sample > 4096 || opts.outHeight / sample > 4096) sample *= 2
                val d = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, d)
            }.getOrNull()
        }
        if (bmp == null) decodeFailed = true else bitmapState = bmp
    }
    if (decodeFailed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.preview_unavailable), color = Color.White)
        }
        return
    }
    val bitmap = bitmapState ?: run {
        // 解码中
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(file.absolutePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        // 缩回 1x 时归位
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }
    )
}

@Composable
private fun VideoPreview(file: File, resumePos: Int, onReady: (VideoView?) -> Unit) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.fromFile(file))
                val controller = MediaController(ctx)
                setMediaController(controller)
                controller.setAnchorView(this)
                setOnPreparedListener { mp ->
                    if (resumePos > 0) seekTo(resumePos)
                    start()
                }
                setOnErrorListener { _, what, extra ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.preview_play_error, what, extra),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                onReady(this)
            }
        },
        onRelease = { onReady(null) },
        modifier = Modifier.fillMaxSize()
    )
}
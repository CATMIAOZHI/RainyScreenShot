package com.rainy.screenshot.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.screenshot.R
import com.rainy.screenshot.capture.RecordingEngine
import com.rainy.screenshot.capture.ScreenshotEngine
import com.rainy.screenshot.capture.ShellExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条产出记录。 */
data class CaptureItem(
    val file: File,
    val isVideo: Boolean,
    /** 是否为 RAW 原始像素文件（16 字节头 + 裸 RGBA，不可预览） */
    val isRaw: Boolean = false
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val recordingSessionManager: com.rainy.screenshot.session.RecordingSessionManager
) : ViewModel() {

    private val _items = MutableStateFlow<List<CaptureItem>>(emptyList())
    val items: StateFlow<List<CaptureItem>> = _items.asStateFlow()

    /** App 私有外部目录根（Screenshots/、Recordings/ 的父目录） */
    private val outputRoot: File by lazy { shellExecutor.outputRootDir() }

    init {
        refresh()
    }

    /** 扫描两个产出目录，合并按修改时间倒序（排除正在写入的录制文件）。 */
    fun refresh() {
        viewModelScope.launch {
            // 正在写入的录制文件（内容未定稿，预览必错、误删=数据丢失）
            val writing = (recordingSessionManager.currentState()
                as? com.rainy.screenshot.session.RecordingSessionManager.SessionState.Recording)
                ?.outputFile?.absolutePath
            val list = withContext(Dispatchers.IO) {
                scanDir(ScreenshotEngine.DIR_SCREENSHOTS, video = false, writing) +
                    scanDir(RecordingEngine.DIR_RECORDINGS, video = true, writing)
            }
            _items.value = list.sortedByDescending { it.file.lastModified() }
        }
    }

    private fun scanDir(subDir: String, video: Boolean, writing: String?): List<CaptureItem> {
        val dir = File(outputRoot, subDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L && it.absolutePath != writing }
            ?.map { f ->
                val isRaw = f.extension.equals("raw", ignoreCase = true)
                CaptureItem(file = f, isVideo = video, isRaw = isRaw)
            }
            ?: emptyList()
    }

    /** 删除记录（App 自己私有目录文件，直接删）。 */
    fun delete(item: CaptureItem, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { item.file.delete() }.getOrDefault(false)
            }
            if (ok) refresh()
            onDone(ok)
        }
    }
}

/**
 * 历史页：产出文件浏览（阶段 3：完整实现——目录扫描/删除/分享）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    var confirmDelete by remember { mutableStateOf<CaptureItem?>(null) }
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.file.absolutePath }) { item ->
                    CaptureItemCard(
                        item = item,
                        onOpen = {
                            // RAW 文件不可预览（16 字节头 + 裸像素），Toast 提示
                            if (item.isRaw) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.history_raw_toast),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                com.rainy.screenshot.ui.preview.PreviewActivity.start(
                                    context, item.file, item.isVideo
                                )
                            }
                        },
                        onDelete = { confirmDelete = item },
                        onShare = { shareFile(context, item) }
                    )
                }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(item.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item) { confirmDelete = null }
                }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun CaptureItemCard(
    item: CaptureItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onOpen
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩略图 / 类型图标
            Thumbnail(item, Modifier.size(56.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatSize(item.file.length())} · ${formatWhen(item.file.lastModified())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.isRaw) {
                    Text(
                        stringResource(R.string.history_tap_preview),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            // 操作：分享 / 删除
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.common_share), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 缩略图：PNG 异步解码显示；视频/RAW 显示类型图标。 */
@Composable
private fun Thumbnail(item: CaptureItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 异步解码：remember 组合期同步解码会阻塞主线程（滚动卡顿，审计体验级 9）
    var bitmap by androidx.compose.runtime.remember(item.file.absolutePath) {
        androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(item.file.absolutePath) {
        bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadThumbnail(context, item.file, maxPx = 120)
        }
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = item.file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                if (item.isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                contentDescription = stringResource(
                    when {
                        item.isVideo -> R.string.history_thumb_video
                        item.isRaw -> R.string.history_thumb_raw
                        else -> R.string.history_thumb_image
                    }
                ),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** 加载 PNG 缩略图（RAW/视频/解码失败返回 null）。 */
private fun loadThumbnail(context: Context, file: File, maxPx: Int = 120): android.graphics.Bitmap? {
    if (!file.extension.equals("png", ignoreCase = true)) return null
    if (file.length() > 20L * 1024 * 1024) return null // 超大文件不解码
    return runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > maxPx * 2 || opts.outHeight / sample > maxPx * 2) {
            sample *= 2
        }
        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }.getOrNull()
}

/**
 * 分享单个文件（FileProvider content:// URI + ACTION_SEND chooser）。
 * MIME：png/image、mp4/video、raw/octet-stream。
 */
private fun shareFile(context: Context, item: CaptureItem) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        item.file
    )
    val mime = when {
        item.isVideo -> "video/mp4"
        item.isRaw -> "application/octet-stream"
        else -> "image/png"
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.history_share_chooser, item.file.name))
        )
    }
}

/** 格式化文件大小。 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 格式化修改时间（月-日 时:分）。 */
private fun formatWhen(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
    return sdf.format(java.util.Date(ts))
}
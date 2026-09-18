package ru.meerbot.sdk.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.meerbot.sdk.R
import ru.meerbot.sdk.state.Attachment
import ru.meerbot.sdk.state.ChatController
import ru.meerbot.sdk.state.ChatMessage
import java.io.File
import java.util.Locale

/**
 * Отрисовка вложений в пузыре сообщения. Картинки — превью в ленте, остальное — плашка
 * (иконка/расширение + имя + размер) со скачиванием по тапу.
 */
@Composable
internal fun MessageAttachments(
    controller: ChatController,
    message: ChatMessage,
    onSurface: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        message.attachments.forEach { attachment ->
            if (attachment.isImage) {
                AttachmentImage(controller, message.serverId, attachment)
            } else {
                AttachmentFileTile(controller, message.serverId, attachment, onSurface, accent)
            }
        }
    }
}

/** Три состояния загрузки картинки: пока грузим — спиннер, ошибка — заглушка, готово — bitmap. */
private sealed interface ImageLoad {
    object Loading : ImageLoad
    object Failed : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
}

@Composable
private fun AttachmentImage(
    controller: ChatController,
    messageServerId: Long?,
    attachment: Attachment,
) {
    val imageDescription = stringResource(R.string.meerbot_attachment_image)
    // Ключ включает serverId: локальная строка получает его после подтверждения, и тогда картинку
    // можно догрузить из сети, даже если байты из кэша уже вытеснены.
    val load by produceState<ImageLoad>(ImageLoad.Loading, attachment.mediaId, messageServerId) {
        value = try {
            val bytes = controller.mediaBytes(messageServerId, attachment)
            val bitmap = withContext(Dispatchers.Default) { decodeSampled(bytes, MAX_IMAGE_PX) }
            if (bitmap != null) ImageLoad.Ready(bitmap.asImageBitmap()) else ImageLoad.Failed
        } catch (e: Throwable) {
            // 401/пропавшее медиа/битые байты — заглушка, а не краш. Байты в лог не пишем.
            ImageLoad.Failed
        }
    }

    val aspect = attachment.width?.takeIf { it > 0 }?.let { w ->
        attachment.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h.toFloat() }
    } ?: DEFAULT_IMAGE_ASPECT

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = imageDescription },
        contentAlignment = Alignment.Center,
    ) {
        when (val state = load) {
            is ImageLoad.Ready -> Image(
                bitmap = state.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
            ImageLoad.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ImageLoad.Failed -> Icon(
                imageVector = remember { brokenImageIcon() },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun AttachmentFileTile(
    controller: ChatController,
    messageServerId: Long?,
    attachment: Attachment,
    onSurface: Color,
    accent: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadFailed = stringResource(R.string.meerbot_attachment_download_failed)
    val noViewer = stringResource(R.string.meerbot_attachment_no_viewer)
    val tileDescription = stringResource(R.string.meerbot_attachment_file, attachment.fileName)

    Row(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                scope.launch {
                    val opened = openAttachment(context, controller, messageServerId, attachment)
                    if (opened == OpenResult.DownloadFailed) {
                        Toast.makeText(context, downloadFailed, Toast.LENGTH_SHORT).show()
                    } else if (opened == OpenResult.NoViewer) {
                        Toast.makeText(context, noViewer, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .padding(10.dp)
            .semantics { contentDescription = tileDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Иконка вида: у видео — «play», у остального — квадрат с расширением файла.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.kind == Attachment.KIND_VIDEO) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    text = fileExtension(attachment),
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                attachment.fileName,
                color = onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                humanSize(attachment.size),
                color = onSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = remember { downloadIcon() },
            contentDescription = null,
            tint = onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private enum class OpenResult { Ok, DownloadFailed, NoViewer }

/** Скачать байты (если ещё не локальные) и открыть системным просмотрщиком через FileProvider. */
private suspend fun openAttachment(
    context: android.content.Context,
    controller: ChatController,
    messageServerId: Long?,
    attachment: Attachment,
): OpenResult {
    val bytes = try {
        controller.mediaBytes(messageServerId, attachment)
    } catch (e: Throwable) {
        return OpenResult.DownloadFailed
    }
    val uri = try {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "meerbot_media").apply { mkdirs() }
            val safeName = attachment.fileName.substringAfterLast('/').ifEmpty { "file" }
            val file = File(dir, safeName)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.meerbot.fileprovider", file)
        }
    } catch (e: Throwable) {
        return OpenResult.DownloadFailed
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, attachment.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        OpenResult.Ok
    } catch (e: Throwable) {
        OpenResult.NoViewer
    }
}

// ─── Утилиты ────────────────────────────────────────────────────────────────────────────────

private const val MAX_IMAGE_PX = 1280
private const val DEFAULT_IMAGE_ASPECT = 4f / 3f

/** Декодирование с прореживанием: превью в ленте не должно тянуть в память полноразмерный кадр. */
private fun decodeSampled(bytes: ByteArray, maxPx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

internal fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Короткое расширение для плашки: из имени файла, иначе из подтипа MIME, иначе «FILE». */
internal fun fileExtension(attachment: Attachment): String {
    val fromName = attachment.fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
    val fromMime = attachment.mime.substringAfterLast('/', "").takeIf { it.isNotEmpty() && it.length <= 5 }
    return (fromName ?: fromMime ?: "FILE").uppercase(Locale.US).take(4)
}

// Векторные иконки строятся из path-данных Material (в material-icons-core их нет):
// скрепка для композера, скачивание для плашки и «битая картинка» для заглушки.
internal fun attachIcon(): ImageVector = materialVector(
    "meerbot_attach",
    "M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5c0-1.38 1.12-2.5 2.5-2.5s2.5 1.12 2.5 2.5v10.5" +
        "c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5c0 1.38 1.12 2.5 2.5 2.5s2.5-1.12 2.5-2.5V5" +
        "c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z",
)

private fun downloadIcon(): ImageVector = materialVector(
    "meerbot_download",
    "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
)

private fun brokenImageIcon(): ImageVector = materialVector(
    "meerbot_broken_image",
    "M21 5v6.59l-3-3.01-4 4.01-4-4-4 4-3-3.01V5c0-1.1.9-2 2-2h14c1.1 0 2 .9 2 2zm-3 6.42l3 3.01V19" +
        "c0 1.1-.9 2-2 2H5c-1.1 0-2-.9-2-2v-6.58l3 2.99 4-4 4 4 4-3.99z",
)

private fun materialVector(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

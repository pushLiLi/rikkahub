package me.rerere.rikkahub.ui.components.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * 直接拉起系统相册选图/选视频的入口，替代 [ActivityResultContracts.PickVisualMedia]。
 *
 * PickVisualMedia 在多数国产 ROM 上不可用，AndroidX 会回退到系统文档管理器
 * （ACTION_OPEN_DOCUMENT），用户看到的是文件夹浏览而不是相册。
 * ACTION_PICK + MediaStore 由系统相册 App 响应，各 ROM 上都直接进相册；
 * 设备上没有任何相册响应时才回退到系统 Photo Picker。
 */

/** 单选：相册里选一张，取消返回 null */
class PickGalleryMedia(
    private val video: Boolean = false,
) : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = galleryPickIntent(video)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) parseGalleryUris(intent).firstOrNull() else null
}

/** 多选：返回相册里选中的全部 uri，取消返回空列表 */
class PickMultipleGalleryMedia(
    private val video: Boolean = false,
    private val maxItems: Int = Int.MAX_VALUE,
) : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent = galleryPickIntent(video, maxItems)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK) return emptyList()
        // 不支持限选数量的相册可能多返回，这里截断兜底
        return parseGalleryUris(intent).take(maxItems)
    }
}

private fun galleryPickIntent(video: Boolean, maxItems: Int = 1): Intent {
    val collection = if (video) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    return Intent(Intent.ACTION_PICK, collection).apply {
        type = "${if (video) "video" else "image"}/*"
        if (maxItems > 1) {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // 部分国产相册识别的兼容 extra
            putExtra("multi-pick", true)
            // Intent.EXTRA_MAX_IMAGES 的字面量（部分 SDK 版本缺这个常量）
            putExtra("android.intent.extra.MAX_IMAGES", maxItems)
        }
    }
}

private fun parseGalleryUris(intent: Intent?): List<Uri> {
    intent ?: return emptyList()
    val uris = mutableListOf<Uri>()
    val clip = intent.clipData
    if (clip != null) {
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let(uris::add)
        }
    }
    if (uris.isEmpty()) intent.data?.let(uris::add)
    return uris
}

/** 单选相册图片/视频；无相册响应 ACTION_PICK 的设备回退系统 Photo Picker */
@Composable
fun rememberGalleryMediaPicker(
    video: Boolean = false,
    onResult: (Uri?) -> Unit,
): () -> Unit {
    val primary = rememberLauncherForActivityResult(PickGalleryMedia(video)) { onResult(it) }
    val fallback = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { onResult(it) }
    return {
        try {
            primary.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            fallback.launch(
                PickVisualMediaRequest(
                    if (video) {
                        ActivityResultContracts.PickVisualMedia.VideoOnly
                    } else {
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    }
                )
            )
        }
    }
}

/** 多选相册图片/视频；无相册响应 ACTION_PICK 的设备回退系统 Photo Picker（多选） */
@Composable
fun rememberMultipleGalleryMediaPicker(
    video: Boolean = false,
    maxItems: Int = Int.MAX_VALUE,
    onResult: (List<Uri>) -> Unit,
): () -> Unit {
    val primary = rememberLauncherForActivityResult(PickMultipleGalleryMedia(video, maxItems)) { onResult(it) }
    val fallback =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems)) { onResult(it) }
    return {
        try {
            primary.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            fallback.launch(
                PickVisualMediaRequest(
                    if (video) {
                        ActivityResultContracts.PickVisualMedia.VideoOnly
                    } else {
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    }
                )
            )
        }
    }
}

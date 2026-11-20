package me.rerere.rikkahub.ui.components.richtext

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Colors
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.ImageDownload
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.exportImage
import me.rerere.ui.toast.ToastType
import me.rerere.ui.webview.WEB_VIEW_BASE_URL
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.WebViewContentCache
import me.rerere.ui.webview.rememberWebViewState

private val MinSvgPreviewHeight = 96.dp
private val MaxSvgPreviewHeight = 420.dp
private val FallbackSvgPreviewHeight = 200.dp

/** SVG 预览的背景，AI 画的黑白线条在深色背景上可能看不清，允许手动切换 */
private enum class SvgBackground {
    THEME,
    LIGHT,
    DARK;

    fun backgroundHex(colorScheme: ColorScheme): String = when (this) {
        THEME -> colorScheme.surfaceContainer.toOpaqueCssHex()
        LIGHT -> "#FFFFFF"
        DARK -> "#1E1E1E"
    }
}

/**
 * 在聊天里渲染 AI 生成的 SVG。
 *
 * 用 WebView（Chromium）而不是 Coil/AndroidSVG：`foreignObject`、现代 CSS、滤镜和动画这些
 * AI 常用的写法只有浏览器能完整还原。图形高度由页面里的 `ResizeObserver` 回传，卡片跟着
 * 内容长高；太长的图限制在 [MaxSvgPreviewHeight] 内滚动。
 *
 * 支持切换背景、保存 PNG 到相册、全屏查看（全屏页支持双指缩放）。
 */
@Composable
fun SvgPreview(
    code: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentActivity by rememberUpdatedState(activity)
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val density = LocalDensity.current

    var backgroundIndex by rememberSaveable { mutableIntStateOf(SvgBackground.THEME.ordinal) }
    val background = SvgBackground.values()[backgroundIndex]
    val backgroundHex = remember(background, colorScheme) { background.backgroundHex(colorScheme) }

    // WebView 的 JS 接口只创建一次，高度状态也保持同一个实例；换代码时手动清零重新测量
    var reportedHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(code) { reportedHeightPx = 0 }
    val previewHeight: Dp = remember(reportedHeightPx, density) {
        val measured = with(density) { reportedHeightPx.toDp() }
        if (reportedHeightPx <= 0) FallbackSvgPreviewHeight
        else maxOf(MinSvgPreviewHeight, minOf(MaxSvgPreviewHeight, measured))
    }

    val html = remember(code, backgroundHex) { buildSvgHtml(code, backgroundHex) }
    val jsInterface = remember {
        SvgInterface(
            onHeightChanged = { reportedHeightPx = it },
            onExportImage = { base64 ->
                if (base64.isEmpty()) {
                    toaster.show(
                        context.getString(R.string.svg_preview_save_failed),
                        type = ToastType.Error,
                    )
                } else {
                    val saved = runCatching {
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("Unable to decode image")
                        val target = currentActivity ?: error("No activity")
                        context.exportImage(
                            target,
                            bitmap,
                            "svg_${System.currentTimeMillis()}.png",
                        )
                    }
                    toaster.show(
                        context.getString(
                            if (saved.isSuccess) R.string.svg_preview_saved
                            else R.string.svg_preview_save_failed
                        ),
                        type = if (saved.isSuccess) ToastType.Success else ToastType.Error,
                    )
                }
            },
        )
    }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(SVG_JS_INTERFACE to jsInterface),
        settings = {
            // 预览区尺寸跟随内容，缩放交给全屏页
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        },
    )

    Column(modifier) {
        WebView(
            state = webViewState,
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(8.dp)),
            onCreated = { it.overScrollMode = View.OVER_SCROLL_NEVER },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgActionButton(
                icon = HugeIcons.Colors,
                contentDescription = stringResource(R.string.svg_preview_background),
                onClick = { backgroundIndex = (backgroundIndex + 1) % SvgBackground.values().size },
            )
            SvgActionButton(
                icon = HugeIcons.ImageDownload,
                contentDescription = stringResource(R.string.svg_preview_save_image),
                onClick = {
                    webViewState.webView?.evaluateJavascript(
                        "window.exportPng && window.exportPng();",
                        null,
                    )
                },
            )
            SvgActionButton(
                icon = HugeIcons.Fullscreen,
                contentDescription = stringResource(R.string.code_block_preview),
                onClick = {
                    val contentId = WebViewContentCache.store(context.cacheDir, html)
                    navController.navigate(Screen.WebView(contentId = contentId))
                },
            )
        }
    }
}

@Composable
private fun SvgActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

private const val SVG_JS_INTERFACE = "SvgInterface"

private class SvgInterface(
    private val onHeightChanged: (Int) -> Unit,
    private val onExportImage: (String) -> Unit,
) {
    // JavascriptInterface 的回调在 WebView 的 JavaBridge 线程，回到主线程再改 Compose 状态
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun setHeight(heightPx: Int) {
        mainHandler.post { onHeightChanged(heightPx) }
    }

    @JavascriptInterface
    fun exportImage(base64Image: String) {
        mainHandler.post { onExportImage(base64Image) }
    }
}

private fun Color.toOpaqueCssHex(): String {
    val argb = toArgb()
    return String.format(
        "#%02X%02X%02X",
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
    )
}

private fun buildSvgHtml(code: String, backgroundHex: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body {
                margin: 0;
                padding: 0;
                background: $backgroundHex;
            }
            #svg-container {
                box-sizing: border-box;
                padding: 12px;
                display: flex;
                justify-content: center;
                align-items: center;
                background: $backgroundHex;
            }
            #svg-container svg {
                max-width: 100%;
                height: auto;
            }
        </style>
    </head>
    <body>
        <div id="svg-container">$code</div>
        <script>
            (function () {
                var container = document.getElementById('svg-container');
                var svg = container.querySelector('svg');
                if (svg) {
                    // AI 经常只写 width/height 不写 viewBox，补一个再让宽度自适应，避免图形被裁掉
                    if (!svg.getAttribute('viewBox')) {
                        var rect = svg.getBoundingClientRect();
                        var width = parseFloat(svg.getAttribute('width')) || rect.width;
                        var height = parseFloat(svg.getAttribute('height')) || rect.height;
                        if (width > 0 && height > 0) {
                            svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
                        }
                    }
                    svg.removeAttribute('width');
                    svg.removeAttribute('height');
                }

                var lastHeight = -1;
                function notifyHeight() {
                    var height = Math.ceil(container.getBoundingClientRect().height);
                    if (height > 0 && Math.abs(height - lastHeight) >= 1) {
                        lastHeight = height;
                        $SVG_JS_INTERFACE.setHeight(height);
                    }
                }

                window.addEventListener('load', notifyHeight);
                if (window.ResizeObserver) {
                    new ResizeObserver(notifyHeight).observe(container);
                }
                setTimeout(notifyHeight, 50);
                setTimeout(notifyHeight, 300);

                window.exportPng = function () {
                    try {
                        var svg = container.querySelector('svg');
                        if (!svg) {
                            $SVG_JS_INTERFACE.exportImage('');
                            return;
                        }
                        var rect = svg.getBoundingClientRect();
                        var scale = 2;
                        var width = Math.max(1, Math.round(rect.width));
                        var height = Math.max(1, Math.round(rect.height));
                        var clone = svg.cloneNode(true);
                        clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
                        clone.setAttribute('width', width);
                        clone.setAttribute('height', height);
                        var xml = new XMLSerializer().serializeToString(clone);
                        var svgBase64 = btoa(unescape(encodeURIComponent(xml)));
                        var img = new Image();
                        img.onload = function () {
                            var canvas = document.createElement('canvas');
                            canvas.width = width * scale;
                            canvas.height = height * scale;
                            var ctx = canvas.getContext('2d');
                            ctx.fillStyle = '$backgroundHex';
                            ctx.fillRect(0, 0, canvas.width, canvas.height);
                            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
                            $SVG_JS_INTERFACE.exportImage(canvas.toDataURL('image/png').split(',')[1]);
                        };
                        img.onerror = function () {
                            $SVG_JS_INTERFACE.exportImage('');
                        };
                        img.src = 'data:image/svg+xml;base64,' + svgBase64;
                    } catch (e) {
                        $SVG_JS_INTERFACE.exportImage('');
                    }
                };
            })();
        </script>
    </body>
    </html>
""".trimIndent()

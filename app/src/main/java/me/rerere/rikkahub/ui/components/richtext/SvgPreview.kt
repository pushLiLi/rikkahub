package me.rerere.rikkahub.ui.components.richtext

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Colors
import me.rerere.hugeicons.stroke.ImageDownload
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.ScreenRotation
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.getActivity
import me.rerere.ui.toast.ToastType
import me.rerere.ui.webview.WEB_VIEW_BASE_URL
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.rememberWebViewState
import kotlin.math.roundToInt

private val MinSvgPreviewHeight = 80.dp
private val MaxSvgPreviewHeight = 460.dp
private val FallbackSvgPreviewHeight = 220.dp

/** SVG 预览的背景，AI 画的深浅线条在固定底色上可能看不清，支持在顶栏或全屏下切换 */
internal enum class SvgBackground {
    THEME,
    LIGHT,
    DARK;

    fun backgroundHex(colorScheme: ColorScheme): String = when (this) {
        THEME -> colorScheme.surfaceContainer.toOpaqueCssHex()
        LIGHT -> "#FFFFFF"
        DARK -> "#1A1C1E"
    }
}

@Stable
internal class SvgPreviewState(
    initialBackgroundIndex: Int = SvgBackground.THEME.ordinal,
) {
    var backgroundIndex by mutableIntStateOf(initialBackgroundIndex)
        private set

    var showFullscreen by mutableStateOf(false)
        private set

    var exportRequestCount by mutableIntStateOf(0)
        private set

    fun toggleBackground() {
        backgroundIndex = (backgroundIndex + 1) % SvgBackground.values().size
    }

    fun requestExportPng() {
        exportRequestCount++
    }

    fun openFullscreen() {
        showFullscreen = true
    }

    fun closeFullscreen() {
        showFullscreen = false
    }
}

@Composable
internal fun rememberSvgPreviewState(): SvgPreviewState {
    var savedBgIndex by rememberSaveable { mutableIntStateOf(SvgBackground.THEME.ordinal) }
    val state = remember { SvgPreviewState(savedBgIndex) }
    LaunchedEffect(state.backgroundIndex) {
        savedBgIndex = state.backgroundIndex
    }
    return state
}

/**
 * 在代码块卡片内渲染完整的 SVG 图像。
 *
 * - 操作按钮全部收敛在代码块顶栏，不在卡片底部重复堆砌工具栏。
 * - 自动用 `getBBox()` 校准 `viewBox` 并以 `meet` 模式完整放进卡片，高度按真实宽高比换算，不截断、不出现内部滚动条。
 * - 点击画面直接打开沉浸式全屏预览（支持横竖屏一键旋转、双指自由缩放与拖拽平移）。
 */
@Composable
internal fun SvgPreview(
    code: String,
    state: SvgPreviewState,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = LocalActivity.current ?: remember(context) { context.getActivity() }
    val currentActivity by rememberUpdatedState(activity)
    val toaster = LocalToaster.current
    val density = LocalDensity.current

    val background = SvgBackground.values()[state.backgroundIndex]
    val backgroundHex = remember(background, colorScheme) { background.backgroundHex(colorScheme) }

    var viewWidthPx by remember { mutableIntStateOf(0) }
    var reportedViewportWidth by remember { mutableIntStateOf(0) }
    var reportedContentHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(code) {
        reportedViewportWidth = 0
        reportedContentHeight = 0
    }

    val previewHeight: Dp = remember(viewWidthPx, reportedViewportWidth, reportedContentHeight, density) {
        if (reportedContentHeight <= 0) {
            FallbackSvgPreviewHeight
        } else {
            // 用 WebView 内部 (内容高度 / 视口宽度) 的无量纲比例乘以宿主机真实像素宽度，彻底消除 density 倍率误差
            val rawDp = if (viewWidthPx > 0 && reportedViewportWidth > 0) {
                val targetHeightPx = (viewWidthPx.toFloat() * reportedContentHeight / reportedViewportWidth).roundToInt()
                with(density) { targetHeightPx.toDp() }
            } else {
                reportedContentHeight.dp
            }
            maxOf(MinSvgPreviewHeight, minOf(MaxSvgPreviewHeight, rawDp))
        }
    }

    val onExportResult: (String) -> Unit = remember(context, toaster) {
        { base64 ->
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
        }
    }

    val jsInterface = remember {
        SvgInterface(
            onDimensionsChanged = { vw, ch ->
                reportedViewportWidth = vw
                reportedContentHeight = ch
            },
            onExportImage = { onExportResult(it) },
        )
    }

    val inlineHtml = remember(code, backgroundHex) {
        buildInlineSvgHtml(code = code, backgroundHex = backgroundHex)
    }

    val webViewState = rememberWebViewState(
        data = inlineHtml,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(SVG_JS_INTERFACE to jsInterface),
        settings = {
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
        },
    )

    LaunchedEffect(state.exportRequestCount) {
        if (state.exportRequestCount > 0) {
            webViewState.webView?.evaluateJavascript(
                "window.exportPng && window.exportPng();",
                null,
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewWidthPx = it.width },
    ) {
        WebView(
            state = webViewState,
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight),
            onCreated = {
                it.overScrollMode = View.OVER_SCROLL_NEVER
                it.isVerticalScrollBarEnabled = false
                it.isHorizontalScrollBarEnabled = false
            },
        )
        // 透明点击层：保证在聊天列表里上下滑动流畅不被 WebView 抢手势，轻点直接打开全屏大图
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { state.openFullscreen() },
                ),
        )
    }

    if (state.showFullscreen) {
        SvgFullscreenDialog(
            code = code,
            state = state,
            hostActivity = activity,
            backgroundHex = backgroundHex,
            onExportResult = onExportResult,
            onDismissRequest = { state.closeFullscreen() },
        )
    }
}

@Composable
private fun SvgFullscreenDialog(
    code: String,
    state: SvgPreviewState,
    hostActivity: Activity?,
    backgroundHex: String,
    onExportResult: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val activity = hostActivity ?: LocalActivity.current
    var isLandscape by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    LaunchedEffect(activity, isLandscape) {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val jsInterface = remember {
        SvgInterface(
            onDimensionsChanged = { _, _ -> },
            onExportImage = { onExportResult(it) },
        )
    }

    val fullscreenHtml = remember(code, backgroundHex) {
        buildFullscreenSvgHtml(code = code, backgroundHex = backgroundHex)
    }

    val webViewState = rememberWebViewState(
        data = fullscreenHtml,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(SVG_JS_INTERFACE to jsInterface),
        settings = {
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
        },
    )

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            WebView(
                state = webViewState,
                modifier = Modifier.fillMaxSize(),
                onCreated = {
                    it.overScrollMode = View.OVER_SCROLL_NEVER
                    it.isVerticalScrollBarEnabled = false
                    it.isHorizontalScrollBarEnabled = false
                },
            )

            // 左上角关闭按钮
            Surface(
                onClick = onDismissRequest,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 底部悬浮胶囊操作栏（Material 3 Expressive 风格）
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FullscreenToolbarButton(
                        icon = HugeIcons.ScreenRotation,
                        label = if (isLandscape) "竖屏" else "横屏",
                        onClick = { isLandscape = !isLandscape },
                    )
                    FullscreenToolbarIconButton(
                        icon = HugeIcons.Colors,
                        contentDescription = stringResource(R.string.svg_preview_background),
                        onClick = { state.toggleBackground() },
                    )
                    FullscreenToolbarIconButton(
                        icon = HugeIcons.Refresh01,
                        contentDescription = "Reset",
                        onClick = {
                            webViewState.webView?.evaluateJavascript(
                                "window.resetView && window.resetView();",
                                null,
                            )
                        },
                    )
                    FullscreenToolbarIconButton(
                        icon = HugeIcons.ImageDownload,
                        contentDescription = stringResource(R.string.svg_preview_save_image),
                        onClick = {
                            webViewState.webView?.evaluateJavascript(
                                "window.exportPng && window.exportPng();",
                                null,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun FullscreenToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

private const val SVG_JS_INTERFACE = "SvgInterface"

private class SvgInterface(
    private val onDimensionsChanged: (viewportWidth: Int, contentHeight: Int) -> Unit,
    private val onExportImage: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun setDimensions(viewportWidth: Int, contentHeight: Int) {
        mainHandler.post { onDimensionsChanged(viewportWidth, contentHeight) }
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

/**
 * 公共的 SVG 归一化与高清 PNG 导出脚本：
 * - 自动补齐或用 `getBBox()` 校准被 AI 写小的 `viewBox`，防止内容被切边
 * - 返回归一化后的真实宽高比 `vbWidth / vbHeight`
 */
private fun commonSvgNormalizeAndExportJs(backgroundHex: String): String = """
    function parsePxLength(raw) {
        if (!raw) return 0;
        var s = String(raw).trim();
        if (!/^\d+(\.\d+)?(px)?$/i.test(s)) return 0;
        return parseFloat(s) || 0;
    }

    function normalizeSvg(svg) {
        if (!svg) return { w: 400, h: 300 };
        var vbAttr = svg.getAttribute('viewBox');
        var wAttr = parsePxLength(svg.getAttribute('width'));
        var hAttr = parsePxLength(svg.getAttribute('height'));
        if (!vbAttr && wAttr > 0 && hAttr > 0) {
            svg.setAttribute('viewBox', '0 0 ' + wAttr + ' ' + hAttr);
        }
        svg.removeAttribute('width');
        svg.removeAttribute('height');
        svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');

        if (!svg.dataset.bboxChecked) {
            try {
                var bbox = svg.getBBox();
                if (bbox && bbox.width > 0 && bbox.height > 0) {
                    svg.dataset.bboxChecked = '1';
                    var cur = svg.viewBox && svg.viewBox.baseVal;
                    var pad = Math.max(bbox.width, bbox.height) * 0.02;
                    if (!cur || cur.width <= 0 || cur.height <= 0) {
                        svg.setAttribute(
                            'viewBox',
                            (bbox.x - pad) + ' ' + (bbox.y - pad) + ' ' +
                            (bbox.width + pad * 2) + ' ' + (bbox.height + pad * 2)
                        );
                    } else {
                        var minX = Math.min(cur.x, bbox.x);
                        var minY = Math.min(cur.y, bbox.y);
                        var maxX = Math.max(cur.x + cur.width, bbox.x + bbox.width);
                        var maxY = Math.max(cur.y + cur.height, bbox.y + bbox.height);
                        if (minX < cur.x - 1 || minY < cur.y - 1 ||
                            maxX > cur.x + cur.width + 1 || maxY > cur.y + cur.height + 1) {
                            svg.setAttribute(
                                'viewBox',
                                (minX - pad) + ' ' + (minY - pad) + ' ' +
                                (maxX - minX + pad * 2) + ' ' + (maxY - minY + pad * 2)
                            );
                        }
                    }
                }
            } catch (e) {}
        }

        var finalVb = svg.viewBox && svg.viewBox.baseVal;
        if (finalVb && finalVb.width > 0 && finalVb.height > 0) {
            return { w: finalVb.width, h: finalVb.height };
        }
        var rect = svg.getBoundingClientRect();
        return {
            w: (wAttr > 0 ? wAttr : (rect.width > 0 ? rect.width : 400)),
            h: (hAttr > 0 ? hAttr : (rect.height > 0 ? rect.height : 300))
        };
    }

    window.exportPng = function () {
        try {
            var svg = document.querySelector('#svg-container svg');
            if (!svg) {
                $SVG_JS_INTERFACE.exportImage('');
                return;
            }
            var size = normalizeSvg(svg);
            var targetLongEdge = 1920;
            var scale = targetLongEdge / Math.max(size.w, size.h, 1);
            var outW = Math.max(1, Math.round(size.w * scale));
            var outH = Math.max(1, Math.round(size.h * scale));
            var pad = Math.round(Math.max(outW, outH) * 0.03);

            var clone = svg.cloneNode(true);
            clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
            clone.setAttribute('width', outW);
            clone.setAttribute('height', outH);
            clone.style.width = outW + 'px';
            clone.style.height = outH + 'px';
            clone.style.transform = 'none';

            var xml = new XMLSerializer().serializeToString(clone);
            var svgBase64 = btoa(unescape(encodeURIComponent(xml)));
            var img = new Image();
            img.onload = function () {
                var canvas = document.createElement('canvas');
                canvas.width = outW + pad * 2;
                canvas.height = outH + pad * 2;
                var ctx = canvas.getContext('2d');
                ctx.fillStyle = '$backgroundHex';
                ctx.fillRect(0, 0, canvas.width, canvas.height);
                ctx.drawImage(img, pad, pad, outW, outH);
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
""".trimIndent()

/**
 * 聊天卡片内联预览 HTML：
 * 根据归一化后的 `viewBox` 宽高比直接算出完整显示所需的精确高度（上限 430px，超长图按比例整体缩小居中），
 * 确保在卡片里一眼看到 100% 完整的图像，绝不裁边。
 */
private fun buildInlineSvgHtml(code: String, backgroundHex: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
            html, body {
                margin: 0;
                padding: 0;
                width: 100%;
                background: $backgroundHex;
                overflow: hidden;
            }
            #svg-container {
                box-sizing: border-box;
                width: 100%;
                padding: 12px;
                display: flex;
                justify-content: center;
                align-items: center;
                background: $backgroundHex;
            }
            #svg-container svg {
                display: block;
                width: 100%;
                height: auto;
            }
        </style>
    </head>
    <body>
        <div id="svg-container">$code</div>
        <script>
            (function () {
                ${commonSvgNormalizeAndExportJs(backgroundHex)}

                var container = document.getElementById('svg-container');
                var svg = container.querySelector('svg');
                var intrinsic = normalizeSvg(svg);
                var lastVw = -1;
                var lastCh = -1;

                function layoutAndNotify() {
                    var vw = Math.max(1, Math.round(window.innerWidth || document.documentElement.clientWidth || 360));
                    if (svg && intrinsic.w > 0 && intrinsic.h > 0) {
                        var availW = Math.max(1, vw - 24);
                        var naturalH = availW * (intrinsic.h / intrinsic.w);
                        var fittedH = Math.min(430, Math.max(64, Math.ceil(naturalH)));
                        svg.style.width = availW + 'px';
                        svg.style.height = fittedH + 'px';
                    }
                    var ch = Math.ceil(container.getBoundingClientRect().height);
                    if (ch > 0 && (vw !== lastVw || Math.abs(ch - lastCh) >= 1)) {
                        lastVw = vw;
                        lastCh = ch;
                        $SVG_JS_INTERFACE.setDimensions(vw, ch);
                    }
                }

                window.addEventListener('load', function () {
                    intrinsic = normalizeSvg(svg);
                    layoutAndNotify();
                });
                window.addEventListener('resize', layoutAndNotify);
                setTimeout(function () {
                    intrinsic = normalizeSvg(svg);
                    layoutAndNotify();
                }, 40);
                setTimeout(layoutAndNotify, 200);
            })();
        </script>
    </body>
    </html>
""".trimIndent()

/**
 * 全屏沉浸式预览 HTML：
 * - 图像默认 `contain` 居中铺满屏幕（无论竖屏还是横屏都能完整居中显示）
 * - 支持单指任意方向拖拽平移、双指围绕中心点自由缩放（0.5x ~ 12x）、双击快速放大/复位
 */
private fun buildFullscreenSvgHtml(code: String, backgroundHex: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
            html, body {
                margin: 0;
                padding: 0;
                width: 100vw;
                height: 100vh;
                background: $backgroundHex;
                overflow: hidden;
                touch-action: none;
                user-select: none;
                -webkit-user-select: none;
            }
            #stage {
                width: 100vw;
                height: 100vh;
                display: flex;
                justify-content: center;
                align-items: center;
                box-sizing: border-box;
                padding: 56px 16px 88px 16px;
            }
            #svg-container {
                width: 100%;
                height: 100%;
                display: flex;
                justify-content: center;
                align-items: center;
                transform-origin: center center;
            }
            #svg-container svg {
                width: 100%;
                height: 100%;
                max-width: 100%;
                max-height: 100%;
                display: block;
            }
        </style>
    </head>
    <body>
        <div id="stage">
            <div id="svg-container">$code</div>
        </div>
        <script>
            (function () {
                ${commonSvgNormalizeAndExportJs(backgroundHex)}

                var stage = document.getElementById('stage');
                var container = document.getElementById('svg-container');
                var svg = container.querySelector('svg');
                var intrinsic = normalizeSvg(svg);

                function fitToStage() {
                    if (!svg) return;
                    intrinsic = normalizeSvg(svg);
                    var availW = Math.max(1, (window.innerWidth || 360) - 32);
                    var availH = Math.max(1, (window.innerHeight || 640) - 144);
                    if (intrinsic.w > 0 && intrinsic.h > 0) {
                        var fitScale = Math.min(availW / intrinsic.w, availH / intrinsic.h);
                        svg.style.width = Math.max(1, Math.round(intrinsic.w * fitScale)) + 'px';
                        svg.style.height = Math.max(1, Math.round(intrinsic.h * fitScale)) + 'px';
                    }
                }
                fitToStage();
                window.addEventListener('load', fitToStage);

                var scale = 1;
                var tx = 0;
                var ty = 0;

                function applyTransform(animated) {
                    container.style.transition = animated ? 'transform 0.18s ease-out' : 'none';
                    container.style.transform = 'translate3d(' + tx + 'px, ' + ty + 'px, 0) scale(' + scale + ')';
                }

                window.resetView = function () {
                    scale = 1;
                    tx = 0;
                    ty = 0;
                    applyTransform(true);
                };

                var pointers = [];
                var lastPanX = 0;
                var lastPanY = 0;
                var startDist = 0;
                var startScale = 1;
                var startMidX = 0;
                var startMidY = 0;
                var startTx = 0;
                var startTy = 0;
                var lastTapTime = 0;

                function dist(t1, t2) {
                    var dx = t1.clientX - t2.clientX;
                    var dy = t1.clientY - t2.clientY;
                    return Math.hypot(dx, dy);
                }

                stage.addEventListener('touchstart', function (e) {
                    if (e.touches.length === 1) {
                        var now = Date.now();
                        if (now - lastTapTime < 280) {
                            if (scale > 1.1) {
                                window.resetView();
                            } else {
                                var cx = window.innerWidth / 2;
                                var cy = window.innerHeight / 2;
                                var tapX = e.touches[0].clientX - cx;
                                var tapY = e.touches[0].clientY - cy;
                                scale = 2.4;
                                tx = -tapX * 1.4;
                                ty = -tapY * 1.4;
                                applyTransform(true);
                            }
                            lastTapTime = 0;
                            return;
                        }
                        lastTapTime = now;
                        lastPanX = e.touches[0].clientX;
                        lastPanY = e.touches[0].clientY;
                    } else if (e.touches.length === 2) {
                        startDist = Math.max(10, dist(e.touches[0], e.touches[1]));
                        startScale = scale;
                        startMidX = (e.touches[0].clientX + e.touches[1].clientX) / 2 - window.innerWidth / 2;
                        startMidY = (e.touches[0].clientY + e.touches[1].clientY) / 2 - window.innerHeight / 2;
                        startTx = tx;
                        startTy = ty;
                    }
                }, { passive: false });

                stage.addEventListener('touchmove', function (e) {
                    e.preventDefault();
                    if (e.touches.length === 1) {
                        var dx = e.touches[0].clientX - lastPanX;
                        var dy = e.touches[0].clientY - lastPanY;
                        lastPanX = e.touches[0].clientX;
                        lastPanY = e.touches[0].clientY;
                        tx += dx;
                        ty += dy;
                        applyTransform(false);
                    } else if (e.touches.length === 2) {
                        var curDist = Math.max(10, dist(e.touches[0], e.touches[1]));
                        var nextScale = Math.min(12, Math.max(0.5, startScale * (curDist / startDist)));
                        var curMidX = (e.touches[0].clientX + e.touches[1].clientX) / 2 - window.innerWidth / 2;
                        var curMidY = (e.touches[0].clientY + e.touches[1].clientY) / 2 - window.innerHeight / 2;
                        var ratio = nextScale / startScale;
                        tx = curMidX - (startMidX - startTx) * ratio;
                        ty = curMidY - (startMidY - startTy) * ratio;
                        scale = nextScale;
                        applyTransform(false);
                    }
                }, { passive: false });

                stage.addEventListener('touchend', function (e) {
                    if (e.touches.length === 1) {
                        lastPanX = e.touches[0].clientX;
                        lastPanY = e.touches[0].clientY;
                    }
                });

                window.addEventListener('resize', function () {
                    fitToStage();
                    window.resetView();
                });
            })();
        </script>
    </body>
    </html>
""".trimIndent()

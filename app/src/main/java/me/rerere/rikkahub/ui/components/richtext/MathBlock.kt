package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import me.rerere.bettertext.latex.LatexBlock
import me.rerere.bettertext.latex.LatexSettings
import me.rerere.bettertext.latex.rememberLatexLayout

/** 块级公式，居中显示，太宽时横向滚动；字号和颜色默认跟随当前文字样式 */
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val style = LocalTextStyle.current
    val resolvedFontSize = fontSize.takeOrElse { style.fontSize }
    val color = style.color.takeOrElse { LocalContentColor.current }
    val layout = rememberLatexLayout(latex, LatexSettings(displayMode = true))
    if (layout.error != null) {
        // bettertext 解析失败 → huarangmeng 引擎兜底（仍失败时它自身按源码着色显示）
        HrmMathBlock(
            latex = latex,
            modifier = modifier.padding(8.dp),
            fontSize = resolvedFontSize,
            color = color,
        )
    } else {
        LatexBlock(
            layout = layout,
            modifier = modifier.padding(8.dp),
            fontSize = resolvedFontSize,
            color = color,
        )
    }
}

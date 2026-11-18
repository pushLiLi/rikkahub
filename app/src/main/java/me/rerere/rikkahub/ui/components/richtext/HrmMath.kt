package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.takeOrElse
import com.hrm.latex.renderer.Latex as HrmLatex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import me.rerere.bettertext.latex.DefaultMathScale
import me.rerere.bettertext.text.BetterInline
import me.rerere.bettertext.text.ComposableInline
import me.rerere.bettertext.text.InlineAlign

/**
 * huarangmeng/latex 引擎的块级公式, 作为 bettertext 解析失败时的兜底。
 *
 * 与 [MathBlock] 保持同样的观感口径: 居中、太宽时横向滚动、字号放大 [DefaultMathScale]
 * 倍(与 KaTeX/bettertext 的数学字号比例一致)、颜色跟随当前文字样式、背景透明。
 */
@Composable
fun HrmMathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
) {
    val style = LocalTextStyle.current
    val resolvedFontSize = fontSize.takeOrElse { style.fontSize } * DefaultMathScale
    val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        HrmLatex(
            latex = latex,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            config = LatexConfig(
                fontSize = resolvedFontSize,
                theme = LatexTheme.light(color = resolvedColor),
            ),
        )
    }
}

/**
 * huarangmeng/latex 引擎的行内公式兜底, 包成 BetterText 的行内组件。
 *
 * 源码包上 `$…$` 让解析器走 InlineMath 节点(TEXT 数学样式, 行内尺寸);
 * 已经含 `$` 的源码原样传入, 避免定界符冲突。长公式不换行(单块占位),
 * 只服务于 bettertext 渲染失败的少数公式。
 */
fun hrmLatexInlineFallback(
    formula: String,
    fontSize: TextUnit,
    color: Color,
): BetterInline = ComposableInline(align = InlineAlign.Center) {
    val source = if (formula.contains('$')) formula else "\$$formula\$"
    HrmLatex(
        latex = source,
        config = LatexConfig(
            fontSize = fontSize * DefaultMathScale,
            theme = LatexTheme.light(color = color),
        ),
    )
}

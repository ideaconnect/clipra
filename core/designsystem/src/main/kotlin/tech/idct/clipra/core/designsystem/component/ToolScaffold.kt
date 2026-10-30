package tech.idct.clipra.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.adaptive.LocalWindowLayout
import tech.idct.clipra.core.designsystem.adaptive.WindowLayout

/**
 * A tool screen with a preview (#70): its header, the preview and the controls.
 *
 * On a phone they stack: the preview [previewHeight] high on black, the controls under it (they
 * get a modifier that fills the rest), then [bottom]. On a tablet ([LocalWindowLayout]) the
 * header runs across, the preview fills the left, and the controls and [bottom] sit in a
 * [panelWidth] column on the right, so a tool is never a stretched phone column.
 */
@Composable
fun ToolScaffold(
    header: @Composable () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
    controls: @Composable (Modifier) -> Unit,
    previewHeight: Dp,
    modifier: Modifier = Modifier,
    bottom: @Composable () -> Unit = {},
    panelWidth: Dp = 420.dp,
) {
    val colors = ClipraTheme.colors
    Column(modifier = modifier) {
        header()
        if (LocalWindowLayout.current == WindowLayout.Tablet) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(colors.ad.background)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                    content = preview,
                )
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .drawBehind {
                            drawLine(
                                colors.borderSubtle,
                                Offset(0f, 0f),
                                Offset(0f, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        },
                ) {
                    controls(Modifier.weight(1f))
                    bottom()
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .clipToBounds()
                    .background(colors.ad.background),
                contentAlignment = Alignment.Center,
                content = preview,
            )
            controls(Modifier.weight(1f))
            bottom()
        }
    }
}

/**
 * A screen without a preview to put beside its controls (#70): on a tablet its content keeps to
 * a readable [maxWidth] in the middle of the window instead of stretching across it. On a phone
 * it is drawn as it is.
 */
@Composable
fun ReadableWidth(maxWidth: Dp = 720.dp, content: @Composable () -> Unit) {
    if (LocalWindowLayout.current == WindowLayout.Tablet) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ClipraTheme.colors.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxHeight(),
            ) {
                content()
            }
        }
    } else {
        content()
    }
}

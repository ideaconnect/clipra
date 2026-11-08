package tech.idct.clipra.feature.export

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals

/**
 * Fails when a text on the screen is cut: shortened with an ellipsis, clipped at its end, or a
 * word broken over two lines, as "Udostępni" / "j" on a 360 dp phone (#237). Texts in
 * [mayBreak], such as file names, may break anywhere, but not be cut.
 */
internal fun SemanticsNodeInteractionsProvider.assertNoTextCut(vararg mayBreak: String) {
    val cut = onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        layouts
    }.filter { it.isCut(it.layoutInput.text.text in mayBreak) }.map { it.layoutInput.text.text }
    assertEquals("Texts that don't fit", emptyList<String>(), cut)
}

private fun TextLayoutResult.isCut(mayBreak: Boolean): Boolean {
    val text = layoutInput.text.text
    val brokenWord = !mayBreak && (0 until lineCount - 1).any { line ->
        val end = getLineEnd(line)
        end in 1 until text.length && text[end - 1].isLetter() && text[end].isLetter()
    }
    // A line wider than the text's box is clipped at its end (one line, no soft wrap).
    val clipped = (0 until lineCount).any { getLineRight(it) - getLineLeft(it) > size.width + 1 }
    return clipped || multiParagraph.didExceedMaxLines || brokenWord ||
        (0 until lineCount).any { isLineEllipsized(it) }
}

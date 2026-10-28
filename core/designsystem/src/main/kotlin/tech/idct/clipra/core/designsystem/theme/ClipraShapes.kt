package tech.idct.clipra.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Corner radii of design/tokens.json ("radius"). */
@Immutable
data class ClipraShapes(
    val chip: Shape = CircleShape,
    val card: Shape = RoundedCornerShape(16.dp),
    val cardLarge: Shape = RoundedCornerShape(20.dp),
    val button: Shape = RoundedCornerShape(14.dp),
    val buttonLarge: Shape = RoundedCornerShape(16.dp),
    val segmentContainer: Shape = RoundedCornerShape(14.dp),
    val segment: Shape = RoundedCornerShape(10.dp),
    val clip: Shape = RoundedCornerShape(7.dp),
    val thumb: Shape = RoundedCornerShape(6.dp),
)

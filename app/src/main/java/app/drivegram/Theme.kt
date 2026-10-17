@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.drivegram

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Material 3 Expressive (m3.material.io): the expressive motion scheme (springy, slightly overshooting), shape-morphing
// components, segmented list groups on a tinted background, and the shape library for avatars and hero icons.

@Composable
fun DriveGramTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
        // Segmented list items sit on `surface`, so the page behind them is one tone darker.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    }
}

/**
 * A segmented list group: rounded outer corners, small inner ones and a 2 dp gap, like Android 16 settings.
 * Each non-null item gets the shapes for its position, so conditional rows keep the group's corners right.
 */
@Composable
fun ListGroup(vararg items: (@Composable (ListItemShapes) -> Unit)?, modifier: Modifier = Modifier) {
    val shown = items.filterNotNull()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        SegmentedSurface { shown.forEachIndexed { i, item -> item(ListItemDefaults.segmentedShapes(i, shown.size)) } }
    }
}

/**
 * Segmented list items are filled with `surface`, which in dark theme is darker than the page. Swap in `surfaceBright`
 * so the rows read as raised in both themes, as in Android 16 settings (in light theme the two are the same tone).
 */
@Composable
fun SegmentedSurface(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = colors.copy(surface = colors.surfaceBright), content = content)
}

/** An icon on one of the Material shapes, for page heroes and list leading icons. */
@Composable
fun ShapedIcon(
    icon: Int,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
    size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** Waiting on TDLib: the expressive loading indicator, or a message when there's something to say. */
@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (text.isEmpty()) {
            ContainedLoadingIndicator(Modifier.size(72.dp))
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

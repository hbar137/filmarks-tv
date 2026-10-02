package net.eulerai.filmarks.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// The website's palette (internal/web/templates/layout.html in filmarks)
object Palette {
    val bg = Color(0xFF0B0B0F)
    val bg2 = Color(0xFF13131A)
    val card = Color(0xFF17171F)
    val line = Color(0xFF262631)
    val text = Color(0xFFECEBF2)
    val muted = Color(0xFF9B9AAA)
    val gold = Color(0xFFF5B83D)
    val red = Color(0xFFFF5D6C)
}

private val scheme: ColorScheme = darkColorScheme(
    primary = Palette.gold, onPrimary = Color(0xFF111111),
    background = Palette.bg, onBackground = Palette.text,
    surface = Palette.card, onSurface = Palette.text,
    surfaceVariant = Palette.bg2, onSurfaceVariant = Palette.muted,
    border = Palette.line, error = Palette.red,
)

@Composable
fun FilmarksTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

/** The UI's two languages: tr(en, "日本語", "English"). */
fun tr(en: Boolean, ja: String, english: String) = if (en) english else ja

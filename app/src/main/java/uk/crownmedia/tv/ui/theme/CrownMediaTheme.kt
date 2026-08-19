package uk.crownmedia.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val titleFamily = FontFamily.Serif

private val bodyFamily = FontFamily.SansSerif

private val CrownColors = darkColorScheme(
    primary = Color(0xFF53E0FF),
    onPrimary = Color(0xFF04111D),
    secondary = Color(0xFF2F64FF),
    onSecondary = Color(0xFFF4F7FB),
    background = Color(0xFF08111F),
    onBackground = Color(0xFFF1F5FB),
    surface = Color(0xFF0D1728),
    onSurface = Color(0xFFF1F5FB),
    surfaceVariant = Color(0xFF13233B),
    outline = Color(0xFF29405D),
)

private val CrownTypography = Typography(
    displayLarge = TextStyle(fontFamily = titleFamily, fontWeight = FontWeight.Bold, fontSize = 56.sp),
    headlineLarge = TextStyle(fontFamily = titleFamily, fontWeight = FontWeight.Bold, fontSize = 38.sp),
    headlineMedium = TextStyle(fontFamily = titleFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp),
    titleLarge = TextStyle(fontFamily = titleFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

@Composable
fun CrownMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CrownColors,
        typography = CrownTypography,
        content = content,
    )
}

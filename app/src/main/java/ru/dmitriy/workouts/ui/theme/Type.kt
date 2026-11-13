package ru.dmitriy.workouts.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import ru.dmitriy.workouts.R

@OptIn(ExperimentalTextApi::class)
private val AppFont = FontFamily(
    Font(R.font.golos_text, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.golos_text, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.golos_text, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
object AppType {
    val TodayTitle = TextStyle(fontFamily = AppFont, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
    val Hero = TextStyle(fontFamily = AppFont, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp)
    val Title = TextStyle(fontFamily = AppFont, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
    val Heading = TextStyle(fontFamily = AppFont, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val Body = TextStyle(fontFamily = AppFont, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val Label = TextStyle(fontFamily = AppFont, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val Caption = TextStyle(fontFamily = AppFont, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val Number = TextStyle(fontFamily = AppFont, fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val ActiveWeight = TextStyle(fontFamily = AppFont, fontSize = 70.sp, lineHeight = 82.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", letterSpacing = (-2).sp)
    val ActiveReps = TextStyle(fontFamily = AppFont, fontSize = 54.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", letterSpacing = (-1).sp)
    val Timer = TextStyle(fontFamily = AppFont, fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
}

@Composable fun WorkoutsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Accent, onPrimary = AppColors.Background,
            primaryContainer = AppColors.Raised, onPrimaryContainer = AppColors.Text,
            secondary = AppColors.Secondary, onSecondary = AppColors.Background,
            secondaryContainer = AppColors.Raised, onSecondaryContainer = AppColors.Text,
            tertiary = AppColors.Accent, onTertiary = AppColors.Background,
            tertiaryContainer = AppColors.Raised, onTertiaryContainer = AppColors.Text,
            background = AppColors.Background, onBackground = AppColors.Text,
            surface = AppColors.Surface, onSurface = AppColors.Text,
            surfaceVariant = AppColors.Raised, onSurfaceVariant = AppColors.Secondary,
            outline = AppColors.Line, outlineVariant = AppColors.Line,
            error = AppColors.Error, onError = AppColors.Background,
            errorContainer = AppColors.Surface, onErrorContainer = AppColors.Error,
            inverseSurface = AppColors.Text, inverseOnSurface = AppColors.Background,
            inversePrimary = AppColors.Pressed, scrim = AppColors.Background,
            surfaceTint = AppColors.Transparent,
        ),
        typography = Typography(
            displayLarge = AppType.Hero, displayMedium = AppType.Hero, displaySmall = AppType.Title,
            headlineLarge = AppType.Title, headlineMedium = AppType.Title, headlineSmall = AppType.Heading,
            titleLarge = AppType.Heading, titleMedium = AppType.Body, titleSmall = AppType.Label,
            bodyLarge = AppType.Body, bodyMedium = AppType.Body, bodySmall = AppType.Label,
            labelLarge = AppType.Body, labelMedium = AppType.Label, labelSmall = AppType.Caption,
        ), content = content,
    )
}

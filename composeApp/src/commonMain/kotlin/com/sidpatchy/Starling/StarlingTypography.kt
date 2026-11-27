package com.sidpatchy.Starling

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.sidpatchy.Starling.generated.resources.Res
import com.sidpatchy.Starling.generated.resources.inter_bold
import com.sidpatchy.Starling.generated.resources.inter_italic
import com.sidpatchy.Starling.generated.resources.inter_medium
import com.sidpatchy.Starling.generated.resources.inter_regular
import com.sidpatchy.Starling.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

@Composable
internal fun starlingTypography(): Typography {
    val regular = Font(Res.font.inter_regular, weight = FontWeight.Normal)
    val italic = Font(Res.font.inter_italic, weight = FontWeight.Normal, style = FontStyle.Italic)
    val medium = Font(Res.font.inter_medium, weight = FontWeight.Medium)
    val semiBold = Font(Res.font.inter_semibold, weight = FontWeight.SemiBold)
    val bold = Font(Res.font.inter_bold, weight = FontWeight.Bold)
    return remember(regular, italic, medium, semiBold, bold) {
        val inter = FontFamily(regular, italic, medium, semiBold, bold)
        val base = Typography()
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = inter),
            displayMedium = base.displayMedium.copy(fontFamily = inter),
            displaySmall = base.displaySmall.copy(fontFamily = inter),
            headlineLarge = base.headlineLarge.copy(fontFamily = inter),
            headlineMedium = base.headlineMedium.copy(fontFamily = inter),
            headlineSmall = base.headlineSmall.copy(fontFamily = inter),
            titleLarge = base.titleLarge.copy(fontFamily = inter),
            titleMedium = base.titleMedium.copy(fontFamily = inter),
            titleSmall = base.titleSmall.copy(fontFamily = inter),
            bodyLarge = base.bodyLarge.copy(fontFamily = inter),
            bodyMedium = base.bodyMedium.copy(fontFamily = inter),
            bodySmall = base.bodySmall.copy(fontFamily = inter),
            labelLarge = base.labelLarge.copy(fontFamily = inter),
            labelMedium = base.labelMedium.copy(fontFamily = inter),
            labelSmall = base.labelSmall.copy(fontFamily = inter),
        )
    }
}

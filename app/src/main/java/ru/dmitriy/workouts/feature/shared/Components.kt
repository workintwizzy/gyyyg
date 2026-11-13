package ru.dmitriy.workouts.feature.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import ru.dmitriy.workouts.ui.theme.*

@Composable fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null,
    onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) Motion.PressedScale else Motion.NaturalScale, spring(dampingRatio = 1f, stiffness = 600f), label = "Нажатие")
    val fill = when { !enabled -> AppColors.DisabledSurface; primary && pressed -> AppColors.Pressed; primary -> AppColors.Accent; pressed -> AppColors.Surface; else -> AppColors.Raised }
    val brush = if (primary && enabled && !pressed) Brush.verticalGradient(listOf(AppColors.Accent, AppColors.AccentEnd)) else SolidColor(fill)
    val foreground = when { !enabled -> AppColors.DisabledText; primary -> AppColors.Background; else -> AppColors.Text }
    Row(modifier.scale(scale).clip(AppShapes.Control).background(brush)
        .then(if (!primary) Modifier.border(Space.Hairline, AppColors.Line.copy(alpha = .5f), AppShapes.Control) else Modifier)
        .then(if (onLongClick != null) Modifier.combinedClickable(interactionSource = interaction, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
        else Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick))
        .defaultMinSize(minWidth = Space.Action).heightIn(min = Space.Action)
        .padding(horizontal = Space.Regular, vertical = Space.Medium),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = foreground, modifier = Modifier.size(Space.Icon))
        if (text.isNotBlank()) {
            if (icon != null) Spacer(Modifier.width(Space.Small))
            Text(text, style = AppType.Body, color = foreground)
        }
    }
}

@Composable fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) Motion.PressedScale else Motion.NaturalScale, spring(dampingRatio = 1f, stiffness = 600f), label = "Нажатие значка")
    Box(modifier.size(Space.Touch).scale(scale).clip(AppShapes.Round)
        .background(if (pressed) AppColors.Raised else AppColors.Transparent)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = AppColors.Text, modifier = Modifier.size(Space.Icon))
    }
}

@Composable fun Panel(modifier: Modifier = Modifier, outlined: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(AppShapes.Surface).background(AppColors.Surface)
        .then(if (outlined) Modifier.border(BorderStroke(Space.Hairline, AppColors.Line.copy(alpha = .6f)), AppShapes.Surface) else Modifier)
        .padding(Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Regular), content = content)
}

@Composable fun Input(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, numeric: Boolean = false, multiline: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Text(label, style = AppType.Label, color = if (focused) AppColors.Accent else AppColors.Secondary)
        BasicTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
            .heightIn(min = Space.Action).clip(AppShapes.Control).background(AppColors.Input)
            .border(Space.Hairline, if (focused) AppColors.Accent.copy(alpha = .5f) else AppColors.Line.copy(alpha = .55f), AppShapes.Control)
            .padding(Space.Regular),
            textStyle = AppType.Body.copy(color = AppColors.Text), cursorBrush = SolidColor(AppColors.Accent), singleLine = !multiline,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
    }
}

@Composable fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(AppShapes.Chip).background(if (selected) AppColors.AccentSoft else AppColors.Raised)
        .border(Space.Hairline, if (selected) AppColors.Accent.copy(alpha = .2f) else AppColors.Line.copy(alpha = .45f), AppShapes.Chip)
        .clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected }
        .heightIn(min = Space.Touch).padding(horizontal = Space.Regular, vertical = Space.Medium), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
            if (selected) Icon(Icons.Outlined.Check, null, tint = AppColors.Accent, modifier = Modifier.size(Space.Regular))
            Text(text, style = AppType.Label, color = if (selected) AppColors.Accent else AppColors.Secondary)
        }
    }
}

@Composable fun SectionLabel(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppType.Heading, color = AppColors.Text, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = AppType.Label, color = AppColors.Secondary)
    }
}

@Composable fun DividerLine() { Box(Modifier.fillMaxWidth().height(Space.Hairline).background(AppColors.Line.copy(alpha = .55f))) }

@Composable fun EmptyState(title: String, message: String) {
    Panel(Modifier.fillMaxWidth(), outlined = true) {
        Text(title, style = AppType.Heading, color = AppColors.Text)
        Text(message, style = AppType.Body, color = AppColors.Secondary)
    }
}

@Composable fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.clip(AppShapes.Chip).clickable(role = Role.Button, onClick = onClick).heightIn(min = Space.Touch)
        .padding(horizontal = Space.Small, vertical = Space.Small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
        if (icon != null) Icon(icon, null, tint = AppColors.Secondary, modifier = Modifier.size(Space.Icon))
        Text(text, style = AppType.Label, color = AppColors.Secondary)
    }
}

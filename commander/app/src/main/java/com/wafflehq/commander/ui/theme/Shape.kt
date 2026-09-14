package com.wafflehq.commander.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

val WaffleHQShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.chip),
    small = RoundedCornerShape(AppRadius.button),
    medium = RoundedCornerShape(AppRadius.textField),
    large = RoundedCornerShape(AppRadius.card),
    extraLarge = RoundedCornerShape(AppRadius.dialog)
)

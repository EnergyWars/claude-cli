package com.wafflehq.commander.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── Button ────────────────────────────────────────────────────────────────

@Immutable
data class ButtonRoleTokens(
    val filledBackground: Color,
    val filledContent: Color,
    val tonalBackground: Color,
    val tonalContent: Color,
    val elevatedBackground: Color,
    val elevatedContent: Color,
    val outlinedBorder: Color,
    val outlinedContent: Color,
    val textContent: Color,
)

@Immutable
data class ButtonTokens(
    val primary: ButtonRoleTokens,
    val secondary: ButtonRoleTokens,
    val tertiary: ButtonRoleTokens,
    val success: ButtonRoleTokens,
    val warning: ButtonRoleTokens,
    val error: ButtonRoleTokens,
    val neutral: ButtonRoleTokens,
    val disabledBackground: Color,
    val disabledContent: Color,
) {
    fun forRole(role: AppRole): ButtonRoleTokens = when (role) {
        AppRole.Primary   -> primary
        AppRole.Secondary -> secondary
        AppRole.Tertiary  -> tertiary
        AppRole.Success   -> success
        AppRole.Warning   -> warning
        AppRole.Error     -> error
        AppRole.Neutral   -> neutral
    }
}

// ─── Icon Button ───────────────────────────────────────────────────────────

@Immutable
data class IconButtonRoleTokens(
    val standardContent: Color,
    val filledBackground: Color,
    val filledContent: Color,
    val tonalBackground: Color,
    val tonalContent: Color,
    val outlinedBorder: Color,
    val outlinedContent: Color,
)

@Immutable
data class IconButtonTokens(
    val primary: IconButtonRoleTokens,
    val secondary: IconButtonRoleTokens,
    val tertiary: IconButtonRoleTokens,
    val success: IconButtonRoleTokens,
    val warning: IconButtonRoleTokens,
    val error: IconButtonRoleTokens,
    val neutral: IconButtonRoleTokens,
    val disabledBackground: Color,
    val disabledContent: Color,
) {
    fun forRole(role: AppRole): IconButtonRoleTokens = when (role) {
        AppRole.Primary   -> primary
        AppRole.Secondary -> secondary
        AppRole.Tertiary  -> tertiary
        AppRole.Success   -> success
        AppRole.Warning   -> warning
        AppRole.Error     -> error
        AppRole.Neutral   -> neutral
    }
}

// ─── Chip ──────────────────────────────────────────────────────────────────

@Immutable
data class ChipRoleTokens(
    val assistBackground: Color,
    val assistBorder: Color,
    val assistContent: Color,
    val suggestionBackground: Color,
    val suggestionBorder: Color,
    val suggestionContent: Color,
    val filterSelectedBackground: Color,
    val filterSelectedContent: Color,
    val filterUnselectedBackground: Color,
    val filterUnselectedBorder: Color,
    val filterUnselectedContent: Color,
    val inputSelectedBackground: Color,
    val inputSelectedContent: Color,
    val inputUnselectedBackground: Color,
    val inputUnselectedBorder: Color,
    val inputUnselectedContent: Color,
)

@Immutable
data class ChipTokens(
    val primary: ChipRoleTokens,
    val secondary: ChipRoleTokens,
    val tertiary: ChipRoleTokens,
    val success: ChipRoleTokens,
    val warning: ChipRoleTokens,
    val error: ChipRoleTokens,
    val neutral: ChipRoleTokens,
) {
    fun forRole(role: AppRole): ChipRoleTokens = when (role) {
        AppRole.Primary   -> primary
        AppRole.Secondary -> secondary
        AppRole.Tertiary  -> tertiary
        AppRole.Success   -> success
        AppRole.Warning   -> warning
        AppRole.Error     -> error
        AppRole.Neutral   -> neutral
    }
}

// ─── Card ──────────────────────────────────────────────────────────────────

@Immutable
data class CardBaseTokens(
    val filledBackground: Color,
    val elevatedBackground: Color,
    val outlinedBackground: Color,
    val outlinedBorder: Color,
)

@Immutable
data class CardRoleTokens(
    val filledBackground: Color,
    val filledContent: Color,
    val elevatedBackground: Color,
    val elevatedContent: Color,
)

@Immutable
data class CardTokens(
    val base: CardBaseTokens,
    val primary: CardRoleTokens,
    val secondary: CardRoleTokens,
    val tertiary: CardRoleTokens,
    val success: CardRoleTokens,
    val warning: CardRoleTokens,
    val error: CardRoleTokens,
    val neutral: CardRoleTokens,
) {
    fun forRole(role: AppRole): CardRoleTokens = when (role) {
        AppRole.Primary   -> primary
        AppRole.Secondary -> secondary
        AppRole.Tertiary  -> tertiary
        AppRole.Success   -> success
        AppRole.Warning   -> warning
        AppRole.Error     -> error
        AppRole.Neutral   -> neutral
    }
}

// ─── TextField ─────────────────────────────────────────────────────────────

@Immutable
data class TextFieldRoleTokens(
    val background: Color,
    val content: Color,
    val labelFocused: Color,
    val labelUnfocused: Color,
    val borderFocused: Color,
    val borderUnfocused: Color,
    val errorBorder: Color,
    val errorLabel: Color,
)

@Immutable
data class TextFieldTokens(
    val primary: TextFieldRoleTokens,
    val secondary: TextFieldRoleTokens,
    val tertiary: TextFieldRoleTokens,
    val success: TextFieldRoleTokens,
    val warning: TextFieldRoleTokens,
    val error: TextFieldRoleTokens,
    val neutral: TextFieldRoleTokens,
    val disabledBackground: Color,
    val disabledContent: Color,
) {
    fun forRole(role: AppRole): TextFieldRoleTokens = when (role) {
        AppRole.Primary   -> primary
        AppRole.Secondary -> secondary
        AppRole.Tertiary  -> tertiary
        AppRole.Success   -> success
        AppRole.Warning   -> warning
        AppRole.Error     -> error
        AppRole.Neutral   -> neutral
    }
}

// ─── Banner ────────────────────────────────────────────────────────────────

@Immutable
data class BannerRoleTokens(
    val background: Color,
    val title: Color,
    val body: Color,
    val icon: Color,
    val actionContent: Color,
)

@Immutable
data class BannerTokens(
    val primary: BannerRoleTokens,
    val secondary: BannerRoleTokens,
    val tertiary: BannerRoleTokens,
    val success: BannerRoleTokens,
    val warning: BannerRoleTokens,
    val error: BannerRoleTokens,
    val neutral: BannerRoleTokens,
) {
    fun forRole(role: AppRole): BannerRoleTokens = when (role) {
        AppRole.Primary   -> primary
        AppRole.Secondary -> secondary
        AppRole.Tertiary  -> tertiary
        AppRole.Success   -> success
        AppRole.Warning   -> warning
        AppRole.Error     -> error
        AppRole.Neutral   -> neutral
    }
}

// ─── Badge ─────────────────────────────────────────────────────────────────

@Immutable
data class BadgeRoleTokens(
    val countBackground: Color,
    val countContent: Color,
    val pillBackground: Color,
    val pillContent: Color,
)

@Immutable
data class BadgeTokens(
    val primary: BadgeRoleTokens,
    val secondary: BadgeRoleTokens,
    val tertiary: BadgeRoleTokens,
    val success: BadgeRoleTokens,
    val warning: BadgeRoleTokens,
    val error: BadgeRoleTokens,
    val neutral: BadgeRoleTokens,
) {
    fun forRole(role: AppRole): BadgeRoleTokens = when (role) {
        AppRole.Primary   -> primary
        AppRole.Secondary -> secondary
        AppRole.Tertiary  -> tertiary
        AppRole.Success   -> success
        AppRole.Warning   -> warning
        AppRole.Error     -> error
        AppRole.Neutral   -> neutral
    }
}

// ─── Surface ───────────────────────────────────────────────────────────────

@Immutable
data class SurfaceTokens(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surface3: Color,
    val outline: Color,
)

// ─── Root ──────────────────────────────────────────────────────────────────

@Immutable
data class AppTokens(
    val surface: SurfaceTokens,
    val button: ButtonTokens,
    val iconButton: IconButtonTokens,
    val chip: ChipTokens,
    val card: CardTokens,
    val textField: TextFieldTokens,
    val banner: BannerTokens,
    val badge: BadgeTokens,
)

val LocalAppTokens = staticCompositionLocalOf<AppTokens> {
    error("AppTokens not provided — wrap your app in AppTheme")
}

// ─── Builder ───────────────────────────────────────────────────────────────

internal fun buildAppTokens(c: AppColors): AppTokens = AppTokens(
    surface = SurfaceTokens(
        background       = c.background,
        onBackground     = c.onBackground,
        surface          = c.surface,
        onSurface        = c.onSurface,
        surfaceVariant   = c.surfaceVariant,
        onSurfaceVariant = c.onSurfaceVariant,
        surface3         = c.surface3,
        outline          = c.outline,
    ),
    button = ButtonTokens(
        primary   = c.primary.toButtonRoleTokens(),
        secondary = c.secondary.toButtonRoleTokens(),
        tertiary  = c.tertiary.toButtonRoleTokens(),
        success   = c.success.toButtonRoleTokens(),
        warning   = c.warning.toButtonRoleTokens(),
        error     = c.error.toButtonRoleTokens(),
        neutral   = c.neutral.toButtonRoleTokens(),
        disabledBackground = c.surfaceVariant,
        disabledContent    = c.onSurfaceVariant,
    ),
    iconButton = IconButtonTokens(
        primary   = c.primary.toIconButtonRoleTokens(),
        secondary = c.secondary.toIconButtonRoleTokens(),
        tertiary  = c.tertiary.toIconButtonRoleTokens(),
        success   = c.success.toIconButtonRoleTokens(),
        warning   = c.warning.toIconButtonRoleTokens(),
        error     = c.error.toIconButtonRoleTokens(),
        neutral   = c.neutral.toIconButtonRoleTokens(),
        disabledBackground = c.surfaceVariant,
        disabledContent    = c.onSurfaceVariant,
    ),
    chip = ChipTokens(
        primary   = c.primary.toChipRoleTokens(c.outline),
        secondary = c.secondary.toChipRoleTokens(c.outline),
        tertiary  = c.tertiary.toChipRoleTokens(c.outline),
        success   = c.success.toChipRoleTokens(c.outline),
        warning   = c.warning.toChipRoleTokens(c.outline),
        error     = c.error.toChipRoleTokens(c.outline),
        neutral   = c.neutral.toChipRoleTokens(c.outline),
    ),
    card = CardTokens(
        base = CardBaseTokens(
            filledBackground   = c.surface,
            elevatedBackground = c.surface,
            outlinedBackground = c.surface,
            outlinedBorder     = c.outline,
        ),
        primary   = c.primary.toCardRoleTokens(),
        secondary = c.secondary.toCardRoleTokens(),
        tertiary  = c.tertiary.toCardRoleTokens(),
        success   = c.success.toCardRoleTokens(),
        warning   = c.warning.toCardRoleTokens(),
        error     = c.error.toCardRoleTokens(),
        neutral   = c.neutral.toCardRoleTokens(),
    ),
    textField = TextFieldTokens(
        primary   = c.primary.toTextFieldRoleTokens(c),
        secondary = c.secondary.toTextFieldRoleTokens(c),
        tertiary  = c.tertiary.toTextFieldRoleTokens(c),
        success   = c.success.toTextFieldRoleTokens(c),
        warning   = c.warning.toTextFieldRoleTokens(c),
        error     = c.error.toTextFieldRoleTokens(c),
        neutral   = c.neutral.toTextFieldRoleTokens(c),
        disabledBackground = c.surfaceVariant,
        disabledContent    = c.onSurfaceVariant,
    ),
    banner = BannerTokens(
        primary   = c.primary.toBannerRoleTokens(),
        secondary = c.secondary.toBannerRoleTokens(),
        tertiary  = c.tertiary.toBannerRoleTokens(),
        success   = c.success.toBannerRoleTokens(),
        warning   = c.warning.toBannerRoleTokens(),
        error     = c.error.toBannerRoleTokens(),
        neutral   = c.neutral.toBannerRoleTokens(),
    ),
    badge = BadgeTokens(
        primary   = c.primary.toBadgeRoleTokens(),
        secondary = c.secondary.toBadgeRoleTokens(),
        tertiary  = c.tertiary.toBadgeRoleTokens(),
        success   = c.success.toBadgeRoleTokens(),
        warning   = c.warning.toBadgeRoleTokens(),
        error     = c.error.toBadgeRoleTokens(),
        neutral   = c.neutral.toBadgeRoleTokens(),
    ),
)

private fun RoleColors.toButtonRoleTokens() = ButtonRoleTokens(
    filledBackground   = accent,
    filledContent      = onAccent,
    tonalBackground    = container,
    tonalContent       = accent,
    elevatedBackground = container,
    elevatedContent    = accent,
    outlinedBorder     = accent,
    outlinedContent    = accent,
    textContent        = accent,
)

private fun RoleColors.toIconButtonRoleTokens() = IconButtonRoleTokens(
    standardContent  = accent,
    filledBackground = accent,
    filledContent    = onAccent,
    tonalBackground  = container,
    tonalContent     = accent,
    outlinedBorder   = accent,
    outlinedContent  = accent,
)

private fun RoleColors.toChipRoleTokens(outline: Color) = ChipRoleTokens(
    assistBackground           = container,
    assistBorder               = outline,
    assistContent              = accent,
    suggestionBackground       = container,
    suggestionBorder           = outline,
    suggestionContent          = accent,
    filterSelectedBackground   = accent,
    filterSelectedContent      = onAccent,
    filterUnselectedBackground = container,
    filterUnselectedBorder     = outline,
    filterUnselectedContent    = accent,
    inputSelectedBackground    = accent,
    inputSelectedContent       = onAccent,
    inputUnselectedBackground  = container,
    inputUnselectedBorder      = outline,
    inputUnselectedContent     = accent,
)

private fun RoleColors.toCardRoleTokens() = CardRoleTokens(
    filledBackground   = container,
    filledContent      = onContainer,
    elevatedBackground = container,
    elevatedContent    = onContainer,
)

private fun RoleColors.toTextFieldRoleTokens(c: AppColors) = TextFieldRoleTokens(
    background      = c.surface,
    content         = c.onSurface,
    labelFocused    = accent,
    labelUnfocused  = c.onSurfaceVariant,
    borderFocused   = accent,
    borderUnfocused = c.outline,
    errorBorder     = c.error.accent,
    errorLabel      = c.error.accent,
)

private fun RoleColors.toBannerRoleTokens() = BannerRoleTokens(
    background    = container,
    title         = accent,
    body          = onContainer,
    icon          = accent,
    actionContent = accent,
)

private fun RoleColors.toBadgeRoleTokens() = BadgeRoleTokens(
    countBackground = accent,
    countContent    = onAccent,
    pillBackground  = container,
    pillContent     = accent,
)

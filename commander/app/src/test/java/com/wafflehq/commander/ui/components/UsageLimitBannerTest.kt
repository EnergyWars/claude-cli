package com.wafflehq.commander.ui.components

import com.wafflehq.commander.ui.theme.AppRole
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageLimitBannerTest {

    @Test
    fun `below 70 percent is neutral`() {
        assertEquals(AppRole.Neutral, usageRoleFor(0))
        assertEquals(AppRole.Neutral, usageRoleFor(69))
    }

    @Test
    fun `70 to 89 percent is warning`() {
        assertEquals(AppRole.Warning, usageRoleFor(70))
        assertEquals(AppRole.Warning, usageRoleFor(89))
    }

    @Test
    fun `90 percent and above is error`() {
        assertEquals(AppRole.Error, usageRoleFor(90))
        assertEquals(AppRole.Error, usageRoleFor(100))
    }
}

package com.wafflehq.commander.ui.run

import org.junit.Assert.assertEquals
import org.junit.Test

class RunAgentCommandTest {

    @Test
    fun `without a context the prompt is sent unchanged`() {
        assertEquals("Fix the bug", buildAgentCommand(contextValue = null, prompt = "Fix the bug"))
    }

    @Test
    fun `with a context its value is prepended before the prompt`() {
        assertEquals(
            "Use Kotlin 2.0 conventions.\n\nFix the bug",
            buildAgentCommand(contextValue = "Use Kotlin 2.0 conventions.", prompt = "Fix the bug"),
        )
    }

    @Test
    fun `an empty context value is still prepended verbatim`() {
        assertEquals("\n\nFix the bug", buildAgentCommand(contextValue = "", prompt = "Fix the bug"))
    }

    @Test
    fun `with a context a blank prompt is omitted`() {
        assertEquals(
            "Use Kotlin 2.0 conventions.",
            buildAgentCommand(contextValue = "Use Kotlin 2.0 conventions.", prompt = "  "),
        )
    }

    @Test
    fun `with a context an empty prompt is omitted`() {
        assertEquals("Use Kotlin 2.0 conventions.", buildAgentCommand(contextValue = "Use Kotlin 2.0 conventions.", prompt = ""))
    }
}

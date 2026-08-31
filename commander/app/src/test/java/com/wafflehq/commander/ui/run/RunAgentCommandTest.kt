package com.wafflehq.commander.ui.run

import org.junit.Assert.assertEquals
import org.junit.Test

class RunAgentCommandTest {

    @Test
    fun `without a context the prompt is sent unchanged`() {
        assertEquals("Fix the bug", buildAgentCommand(contextValues = emptyList(), prompt = "Fix the bug"))
    }

    @Test
    fun `with a single context its value is prepended before the prompt`() {
        assertEquals(
            "Use Kotlin 2.0 conventions.\n\nFix the bug",
            buildAgentCommand(contextValues = listOf("Use Kotlin 2.0 conventions."), prompt = "Fix the bug"),
        )
    }

    @Test
    fun `with multiple contexts all values are prepended in order before the prompt`() {
        assertEquals(
            "Use Kotlin 2.0 conventions.\n\nAlways add tests.\n\nFix the bug",
            buildAgentCommand(
                contextValues = listOf("Use Kotlin 2.0 conventions.", "Always add tests."),
                prompt = "Fix the bug",
            ),
        )
    }

    @Test
    fun `an empty context value is still prepended verbatim`() {
        assertEquals("\n\nFix the bug", buildAgentCommand(contextValues = listOf(""), prompt = "Fix the bug"))
    }

    @Test
    fun `with contexts a blank prompt is omitted`() {
        assertEquals(
            "Use Kotlin 2.0 conventions.\n\nAlways add tests.",
            buildAgentCommand(contextValues = listOf("Use Kotlin 2.0 conventions.", "Always add tests."), prompt = "  "),
        )
    }

    @Test
    fun `with a context an empty prompt is omitted`() {
        assertEquals(
            "Use Kotlin 2.0 conventions.",
            buildAgentCommand(contextValues = listOf("Use Kotlin 2.0 conventions."), prompt = ""),
        )
    }
}

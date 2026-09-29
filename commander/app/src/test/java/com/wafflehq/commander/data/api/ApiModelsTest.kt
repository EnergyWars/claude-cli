package com.wafflehq.commander.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun commandState(agent: String, status: String) = CommandState(
    id = "cmd-1",
    agent = agent,
    model = "sonnet",
    command = "Fix the bug",
    path = "/tmp/project",
    status = status,
    output = "",
    exitCode = null,
    createdAt = "2026-08-26T10:00:00Z",
    updatedAt = "2026-08-26T10:00:01Z",
)

class ApiModelsTest {

    @Test
    fun `a failed agent run is retryable`() {
        assertTrue(commandState(agent = "dev", status = "failed").isRetryable())
    }

    @Test
    fun `a completed run is also retryable`() {
        assertTrue(commandState(agent = "dev", status = "completed").isRetryable())
    }

    @Test
    fun `a stopped run is also retryable`() {
        assertTrue(commandState(agent = "dev", status = "stopped").isRetryable())
    }

    @Test
    fun `a running command is not retryable`() {
        assertFalse(commandState(agent = "dev", status = "running").isRetryable())
    }

    @Test
    fun `a failed path command is not retryable, since it has no free-text prompt`() {
        assertFalse(commandState(agent = "path-command:backend:build", status = "failed").isRetryable())
    }

    @Test
    fun `a completed path command is not retryable, since it has no free-text prompt`() {
        assertFalse(commandState(agent = "path-command:backend:build", status = "completed").isRetryable())
    }

    @Test
    fun `a failed hook run is not retryable, since it has no free-text prompt`() {
        assertFalse(commandState(agent = "hook:backend:onLastAgentFinish", status = "failed").isRetryable())
    }

    @Test
    fun `a completed hook run is not retryable, since it has no free-text prompt`() {
        assertFalse(commandState(agent = "hook:backend:onLastAgentFinish", status = "completed").isRetryable())
    }

    @Test
    fun `a failed script-scheduler run is not retryable, since it has no free-text prompt`() {
        assertFalse(commandState(agent = "script-scheduler:auto-commit-hourly", status = "failed").isRetryable())
    }

    @Test
    fun `a completed script-scheduler run is not retryable, since it has no free-text prompt`() {
        assertFalse(commandState(agent = "script-scheduler:auto-commit-hourly", status = "completed").isRetryable())
    }

    @Test
    fun `a failed goal run is not retryable, since retryAgentCommand cannot map it back to a manifest agent`() {
        assertFalse(
            commandState(agent = "goal:backend:2026-09-27-feature/G01-erstes.md", status = "failed").isRetryable(),
        )
    }

    @Test
    fun `a completed goal run is not retryable, since retryAgentCommand cannot map it back to a manifest agent`() {
        assertFalse(
            commandState(agent = "goal:backend:2026-09-27-feature/G01-erstes.md", status = "completed").isRetryable(),
        )
    }

    @Test
    fun `the main agent maps back to its manifest command "cl"`() {
        assertEquals("cl", commandState(agent = "main", status = "failed").retryAgentCommand())
    }

    @Test
    fun `a named agent maps back to its manifest command "cl name"`() {
        assertEquals("cl dev", commandState(agent = "dev", status = "failed").retryAgentCommand())
    }
}

private fun remoteAgentSession(kind: String) = RemoteAgentSession(
    pid = 123,
    cwd = "/tmp/project",
    kind = kind,
    startedAt = 1_700_000_000_000,
    sessionId = "a1b2c3",
    name = "project-a1",
)

class RemoteAgentSessionTest {

    @Test
    fun `a background session is background`() {
        assertTrue(remoteAgentSession(kind = "background").isBackground())
    }

    @Test
    fun `an interactive session is not background`() {
        assertFalse(remoteAgentSession(kind = "interactive").isBackground())
    }

    private fun goalEntry(id: String, running: Boolean) = GoalEntry(
        id = id,
        fileName = "$id.md",
        title = id,
        description = "",
        date = "2026-09-29",
        dependsOn = emptyList(),
        command = "/goal x",
        content = "",
        timestamp = "2026-09-29T00:00:00.000Z",
        legacy = false,
        status = "ready",
        missingDependencies = emptyList(),
        running = running,
    )

    @Test
    fun `goal group progress counts done, overall and running goals`() {
        val group = GoalListGroup(
            folder = "f",
            totalCount = 5,
            goals = listOf(goalEntry("G03", running = true), goalEntry("G04", running = false), goalEntry("G05", false)),
        )

        assertEquals(2, group.doneCount())
        assertEquals(5, group.overallCount())
        assertEquals(1, group.runningCount())
    }

    @Test
    fun `goal group progress never goes negative when the server omits totalCount`() {
        val group = GoalListGroup(folder = "f", goals = listOf(goalEntry("G01", running = false)))

        assertEquals(0, group.doneCount())
        assertEquals(1, group.overallCount())
        assertEquals(0, group.runningCount())
    }
}

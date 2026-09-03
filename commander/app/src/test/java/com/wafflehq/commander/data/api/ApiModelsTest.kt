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
}

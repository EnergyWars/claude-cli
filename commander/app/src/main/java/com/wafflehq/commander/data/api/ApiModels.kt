package com.wafflehq.commander.data.api

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val version: String)

@Serializable
data class AuthStatusResponse(val active: Boolean, val pending: Boolean)

@Serializable
data class AuthCodeRequest(val code: String)

@Serializable
data class AuthTokenResponse(val token: String, val expiresAt: String, val message: String? = null)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class CommandAccepted(val id: String)

@Serializable
data class CommandRequest(val command: String, val path: String, val model: String? = null)

@Serializable
data class CommandState(
    val id: String,
    val agent: String,
    val model: String,
    val command: String,
    val path: String,
    val status: String,
    val output: String,
    val exitCode: Int?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CommandList(val commands: List<CommandState>)

@Serializable
data class PathList(val paths: List<String>)

@Serializable
data class FileList(val files: List<String>)

@Serializable
data class PathCommandEntry(
    val key: String,
    val command: String,
    val displayName: String,
    val description: String,
)

@Serializable
data class ManifestAgent(val command: String, val description: String)

@Serializable
data class ManifestHostedEntry(val name: String, val type: String)

@Serializable
data class ManifestPath(
    val name: String,
    val commands: List<PathCommandEntry>,
    val hosted: List<ManifestHostedEntry>,
)

@Serializable
data class Manifest(val agents: List<ManifestAgent>, val paths: List<ManifestPath>)

const val HOSTED_TYPE_FILE = "file"
const val HOSTED_TYPE_PATH = "path"

const val TICKET_STATUS_GENERATING = "generating"
const val TICKET_STATUS_OPEN = "open"
const val TICKET_STATUS_IN_PROGRESS = "in progress"
const val TICKET_STATUS_DONE = "done"
const val TICKET_STATUS_REJECTED = "rejected"

@Serializable
data class Ticket(
    val id: Int,
    val pathName: String,
    val originalRequest: String,
    val summary: String,
    val claudeInstruction: String,
    val category: String,
    val status: String,
    val ipAddress: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TicketList(val tickets: List<Ticket>)

@Serializable
data class TicketCreateRequest(val text: String)

@Serializable
data class TicketPatchRequest(
    val originalRequest: String? = null,
    val summary: String? = null,
    val claudeInstruction: String? = null,
    val category: String? = null,
    val status: String? = null,
)

@Serializable
data class CollectedFile(val name: String, val timestamp: String)

@Serializable
data class CollectionList(val files: List<CollectedFile>)

@Serializable
data class CollectRequest(val targetName: String? = null)

@Serializable
data class CollectResultEntry(val targetName: String, val fileName: String, val status: String)

@Serializable
data class CollectErrorEntry(val targetName: String, val error: String)

@Serializable
data class CollectSummary(val results: List<CollectResultEntry>, val errors: List<CollectErrorEntry>)

@Serializable
data class FeedbackEntry(
    val id: Int,
    val text: String,
    val section: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class FeedbackList(val feedback: List<FeedbackEntry>)

@Serializable
data class FeedbackPatchRequest(val text: String)

/** Derives the agent name cl server expects in `POST /<agent>` from a manifest `command` like "cl" or "cl dev". */
fun ManifestAgent.agentNameOrNull(): String? = command.removePrefix("cl").trim().ifEmpty { null }

private const val PATH_COMMAND_AGENT_PREFIX = "path-command:"
private const val COMMAND_STATUS_FAILED = "failed"

/** True for failed agent runs (retryable via the run-agent screen) - excludes shell-based path commands, which have no free-text prompt to retry. */
fun CommandState.isRetryable(): Boolean = status == COMMAND_STATUS_FAILED && !agent.startsWith(PATH_COMMAND_AGENT_PREFIX)

/** Reverses the server's `agentNameOrNull()` mapping: turns a stored `CommandState.agent` (e.g. "main", "dev") back into the `ManifestAgent.command` used to look it up (e.g. "cl", "cl dev"). */
fun CommandState.retryAgentCommand(): String = if (agent == "main") "cl" else "cl $agent"

class ApiException(val httpCode: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown by [com.wafflehq.commander.data.api.ClServerApi] when a Connection exists but its JWT is missing/expired. */
class AuthRequiredException(message: String) : Exception(message)

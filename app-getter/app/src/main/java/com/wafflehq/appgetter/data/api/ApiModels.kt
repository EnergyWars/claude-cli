package com.wafflehq.appgetter.data.api

import kotlinx.serialization.Serializable

@Serializable
data class CollectedFile(val name: String, val timestamp: String)

@Serializable
data class CollectionList(val files: List<CollectedFile>)

@Serializable
data class ErrorResponse(val error: String)

class ApiException(val httpCode: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

package com.oojoo.farm.master.network

import java.net.URI

enum class ServerEndpointError {
    EMPTY,
    MALFORMED,
    UNSUPPORTED_SCHEME,
    HOST_REQUIRED,
    CREDENTIALS_NOT_ALLOWED,
    ROOT_PATH_REQUIRED,
    INVALID_PORT,
}

sealed interface ServerEndpointValidation {
    data class Valid(val normalizedUrl: String) : ServerEndpointValidation
    data class Invalid(val error: ServerEndpointError) : ServerEndpointValidation
}

fun validateServerEndpoint(input: String): ServerEndpointValidation {
    val candidate = input.trim()
    if (candidate.isEmpty()) return ServerEndpointValidation.Invalid(ServerEndpointError.EMPTY)

    val uri = try {
        URI(candidate)
    } catch (_: Exception) {
        return ServerEndpointValidation.Invalid(ServerEndpointError.MALFORMED)
    }

    val scheme = uri.scheme?.lowercase()
        ?: return ServerEndpointValidation.Invalid(ServerEndpointError.UNSUPPORTED_SCHEME)
    if (scheme != "http" && scheme != "https") {
        return ServerEndpointValidation.Invalid(ServerEndpointError.UNSUPPORTED_SCHEME)
    }
    if (uri.userInfo != null) {
        return ServerEndpointValidation.Invalid(ServerEndpointError.CREDENTIALS_NOT_ALLOWED)
    }

    val host = uri.host?.lowercase()
        ?: return ServerEndpointValidation.Invalid(ServerEndpointError.HOST_REQUIRED)
    if (host.isBlank()) return ServerEndpointValidation.Invalid(ServerEndpointError.HOST_REQUIRED)
    if (uri.port == 0 || uri.port > 65_535) {
        return ServerEndpointValidation.Invalid(ServerEndpointError.INVALID_PORT)
    }
    if (uri.rawPath.orEmpty() !in setOf("", "/") || uri.rawQuery != null || uri.rawFragment != null) {
        return ServerEndpointValidation.Invalid(ServerEndpointError.ROOT_PATH_REQUIRED)
    }

    val authorityHost = if (host.contains(':')) "[$host]" else host
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return ServerEndpointValidation.Valid("$scheme://$authorityHost$port/")
}

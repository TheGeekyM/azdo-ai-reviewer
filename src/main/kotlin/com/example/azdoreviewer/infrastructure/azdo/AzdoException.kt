package com.example.azdoreviewer.infrastructure.azdo

sealed class AzdoException(message: String) : Exception(message) {
    class NotConfigured(message: String) : AzdoException(message)
    class Unauthorized(message: String)  : AzdoException(message)
    class NotFound(message: String)      : AzdoException(message)
    class ApiError(val code: Int, message: String) : AzdoException("HTTP $code: $message")
}

class AiException(message: String) : Exception(message)

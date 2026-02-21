package com.badgermole.app.datasource

class SchemaBrowserUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

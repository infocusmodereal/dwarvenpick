package com.dwarvenpick.app.query

import org.springframework.stereotype.Component

enum class QueryJustificationMode {
    NONE,
    PROFILE_REQUIRED,
}

@Component
class QueryJustificationPolicy(
    private val queryExecutionProperties: QueryExecutionProperties,
) {
    fun mode(readOnly: Boolean): QueryJustificationMode =
        if (queryExecutionProperties.requireWriteJustification && !readOnly) {
            QueryJustificationMode.PROFILE_REQUIRED
        } else {
            QueryJustificationMode.NONE
        }

    fun requiresJustification(readOnly: Boolean): Boolean = mode(readOnly) == QueryJustificationMode.PROFILE_REQUIRED
}

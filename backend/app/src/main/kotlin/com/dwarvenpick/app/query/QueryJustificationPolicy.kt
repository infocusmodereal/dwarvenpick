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
    fun mode(
        readOnly: Boolean,
        sysadminProfile: Boolean = false,
    ): QueryJustificationMode =
        if ((queryExecutionProperties.requireAdminProfileJustification && sysadminProfile) ||
            (queryExecutionProperties.requireWriteJustification && !readOnly)
        ) {
            QueryJustificationMode.PROFILE_REQUIRED
        } else {
            QueryJustificationMode.NONE
        }
}

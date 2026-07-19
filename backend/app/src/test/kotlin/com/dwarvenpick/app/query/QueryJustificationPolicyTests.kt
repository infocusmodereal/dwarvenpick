package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryJustificationPolicyTests {
    @Test
    fun `governed write-capable profiles require justification for every run`() {
        val policy =
            QueryJustificationPolicy(
                QueryExecutionProperties(requireWriteJustification = true),
            )

        assertThat(policy.mode(readOnly = false)).isEqualTo(QueryJustificationMode.PROFILE_REQUIRED)
        assertThat(policy.requiresJustification(readOnly = false)).isTrue()
        assertThat(policy.mode(readOnly = true)).isEqualTo(QueryJustificationMode.NONE)
        assertThat(policy.requiresJustification(readOnly = true)).isFalse()
    }

    @Test
    fun `disabled governance never marks a profile as requiring justification`() {
        val policy =
            QueryJustificationPolicy(
                QueryExecutionProperties(requireWriteJustification = false),
            )

        assertThat(policy.mode(readOnly = false)).isEqualTo(QueryJustificationMode.NONE)
        assertThat(policy.requiresJustification(readOnly = false)).isFalse()
    }
}

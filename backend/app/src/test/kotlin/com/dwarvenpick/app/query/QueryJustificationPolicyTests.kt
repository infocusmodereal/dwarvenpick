package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryJustificationPolicyTests {
    @Test
    fun `admin profile governance follows the profile flag regardless of write access`() {
        val policy = QueryJustificationPolicy(QueryExecutionProperties(requireAdminProfileJustification = true))
        for (readOnly in listOf(true, false)) {
            assertThat(policy.mode(readOnly, sysadminProfile = true)).isEqualTo(QueryJustificationMode.PROFILE_REQUIRED)
            assertThat(policy.mode(readOnly, sysadminProfile = false)).isEqualTo(QueryJustificationMode.NONE)
        }
    }

    @Test
    fun `governed write-capable profiles require justification for every run`() {
        val policy =
            QueryJustificationPolicy(
                QueryExecutionProperties(requireWriteJustification = true),
            )

        assertThat(policy.mode(readOnly = false)).isEqualTo(QueryJustificationMode.PROFILE_REQUIRED)
        assertThat(policy.mode(readOnly = true)).isEqualTo(QueryJustificationMode.NONE)
    }

    @Test
    fun `disabled governance never marks a profile as requiring justification`() {
        val policy =
            QueryJustificationPolicy(
                QueryExecutionProperties(requireWriteJustification = false),
            )

        assertThat(policy.mode(readOnly = false)).isEqualTo(QueryJustificationMode.NONE)
    }
}

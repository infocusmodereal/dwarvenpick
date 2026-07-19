package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.auth.AuthenticatedUserPrincipal
import com.dwarvenpick.app.datasource.CatalogDatasourceEntry
import com.dwarvenpick.app.datasource.DatasourceRegistryService
import com.dwarvenpick.app.query.QueryExecutionLimitPolicy
import com.dwarvenpick.app.query.QueryJustificationPolicy
import org.springframework.stereotype.Service

@Service
class EffectiveDatasourcePolicyService(
    private val datasourceRegistryService: DatasourceRegistryService,
    private val rbacService: RbacService,
    private val queryJustificationPolicy: QueryJustificationPolicy,
    private val queryExecutionLimitPolicy: QueryExecutionLimitPolicy,
) {
    fun listPermittedDatasources(principal: AuthenticatedUserPrincipal): List<DatasourceResponse> {
        val allAccessRules = rbacService.datasourceAccessSnapshot()
        val permittedDatasourceIds =
            if (principal.roles.contains(SYSTEM_ADMIN_ROLE)) {
                null
            } else {
                allAccessRules
                    .asSequence()
                    .filter { access -> access.groupId in principal.groups && access.canQuery }
                    .map { access -> access.datasourceId }
                    .toSet()
            }

        return datasourceRegistryService
            .listCatalogEntries()
            .asSequence()
            .filter { datasource -> permittedDatasourceIds == null || datasource.id in permittedDatasourceIds }
            .map { datasource ->
                datasource.toResponse(
                    effectiveCredentialProfilePolicies(principal, datasource, allAccessRules),
                )
            }.toList()
    }

    private fun effectiveCredentialProfilePolicies(
        principal: AuthenticatedUserPrincipal,
        datasource: CatalogDatasourceEntry,
        allAccessRules: List<DatasourceAccessRecord>,
    ): List<EffectiveCredentialProfilePolicyResponse> {
        val configuredProfiles = datasource.credentialProfiles
        val candidateProfiles =
            if (principal.roles.contains(SYSTEM_ADMIN_ROLE)) {
                configuredProfiles
            } else {
                allAccessRules
                    .asSequence()
                    .filter { access ->
                        access.datasourceId == datasource.id &&
                            access.groupId in principal.groups &&
                            access.canQuery &&
                            access.credentialProfile in configuredProfiles
                    }.map { access -> access.credentialProfile }
                    .toSet()
            }
        if (candidateProfiles.isEmpty()) {
            return emptyList()
        }

        val defaultProfile =
            runCatching {
                rbacService.resolveQueryAccessPolicy(principal, datasource.id, allAccessRules).credentialProfile
            }.getOrNull()
        val orderedProfiles =
            candidateProfiles.sortedWith(
                compareBy<String>({ profile -> if (profile == defaultProfile) 0 else 1 }, { profile -> profile }),
            )

        return orderedProfiles.map { credentialProfile ->
            val policy =
                rbacService.resolveQueryAccessPolicyForProfile(
                    principal = principal,
                    datasourceId = datasource.id,
                    credentialProfile = credentialProfile,
                    allAccessRules = allAccessRules,
                )
            val effectiveLimits = queryExecutionLimitPolicy.resolve(policy)
            EffectiveCredentialProfilePolicyResponse(
                credentialProfile = credentialProfile,
                readOnly = policy.readOnly,
                canExport =
                    rbacService.canUserExport(
                        principal = principal,
                        datasourceId = datasource.id,
                        credentialProfile = credentialProfile,
                        allAccessRules = allAccessRules,
                    ),
                maxRowsPerQuery = effectiveLimits.maxRowsPerQuery,
                maxRuntimeSeconds = effectiveLimits.maxRuntimeSeconds,
                concurrencyLimit = effectiveLimits.concurrencyLimit,
                sysadmin = credentialProfile in datasource.sysadminCredentialProfiles,
                justificationMode = queryJustificationPolicy.mode(policy.readOnly),
            )
        }
    }

    private fun CatalogDatasourceEntry.toResponse(
        credentialProfilePolicies: List<EffectiveCredentialProfilePolicyResponse>,
    ): DatasourceResponse =
        DatasourceResponse(
            id = id,
            name = name,
            engine = engine.name,
            credentialProfiles = credentialProfiles,
            sysadminCredentialProfiles = sysadminCredentialProfiles,
            credentialProfilePolicies = credentialProfilePolicies,
        )

    private companion object {
        private const val SYSTEM_ADMIN_ROLE = "SYSTEM_ADMIN"
    }
}

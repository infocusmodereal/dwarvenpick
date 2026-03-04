package com.dwarvenpick.app.inspector

import com.dwarvenpick.app.datasource.DatasourcePoolManager
import org.springframework.stereotype.Service
import java.time.Instant

class InspectedObjectNotFoundException(
    override val message: String,
) : RuntimeException(message)

@Service
class ObjectInspectorService(
    private val datasourcePoolManager: DatasourcePoolManager,
    private val providers: List<ObjectInspectorProvider>,
) {
    fun inspect(
        datasourceId: String,
        credentialProfile: String,
        objectRef: ObjectInspectorObjectRef,
    ): ObjectInspectorResponse {
        val inspectedAt = Instant.now()
        val handle =
            datasourcePoolManager.openConnection(
                datasourceId = datasourceId,
                credentialProfile = credentialProfile,
            )

        val spec = handle.spec
        val provider =
            providers.firstOrNull { candidate ->
                spec.engine in candidate.engines
            }

        if (provider == null) {
            handle.connection.close()
            return ObjectInspectorResponse(
                datasourceId = spec.datasourceId,
                datasourceName = spec.datasourceName,
                engine = spec.engine,
                credentialProfile = spec.credentialProfile,
                inspectedAt = inspectedAt.toString(),
                objectRef = objectRef,
                sections =
                    listOf(
                        ObjectInspectorSection(
                            id = "overview",
                            title = "Overview",
                            status = ObjectInspectorSectionStatus.UNSUPPORTED,
                            message = "Object inspector is not implemented yet for engine ${spec.engine}.",
                        ),
                    ),
            )
        }

        val sections =
            handle.connection.use { connection ->
                try {
                    provider.inspect(spec, connection, objectRef)
                } catch (exception: Exception) {
                    if (exception is InspectedObjectNotFoundException || exception is IllegalArgumentException) {
                        throw exception
                    }
                    listOf(
                        ObjectInspectorSection(
                            id = "overview",
                            title = "Overview",
                            status = ObjectInspectorSectionStatus.ERROR,
                            message = exception.message?.takeIf { it.isNotBlank() } ?: "Inspection failed.",
                        ),
                    )
                }
            }

        return ObjectInspectorResponse(
            datasourceId = spec.datasourceId,
            datasourceName = spec.datasourceName,
            engine = spec.engine,
            credentialProfile = spec.credentialProfile,
            inspectedAt = inspectedAt.toString(),
            objectRef = objectRef,
            sections = sections,
        )
    }
}

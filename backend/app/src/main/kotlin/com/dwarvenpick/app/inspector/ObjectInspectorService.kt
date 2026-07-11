package com.dwarvenpick.app.inspector

import com.dwarvenpick.app.datasource.DatasourcePoolManager
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(ObjectInspectorService::class.java)

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
            logger.warn(
                "No object inspector provider is registered for datasourceId={} engine={}.",
                spec.datasourceId,
                spec.engine,
            )
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
                            message =
                                "No object inspector provider is registered for engine ${spec.engine}. " +
                                    "Contact a Dwarvenpick administrator.",
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
            sections = sections.map { section -> section.withInspectorLimits() },
        )
    }

    private fun ObjectInspectorSection.withInspectorLimits(): ObjectInspectorSection {
        val limitedText =
            text?.let { current ->
                limitObjectInspectorText(current, OBJECT_INSPECTOR_TEXT_CHAR_LIMIT)
            }
        val limitedTable = table?.let { current -> limitObjectInspectorTable(current) }
        val textTruncated = limitedText?.truncated == true
        val tableTruncated = limitedTable?.truncated == true
        val cellsTruncated = limitedTable?.cellsTruncated == true
        val limitMessage =
            buildList {
                if (textTruncated) {
                    add(
                        "Text output was truncated to $OBJECT_INSPECTOR_TEXT_CHAR_LIMIT characters.",
                    )
                }
                if (tableTruncated) {
                    add(
                        "Table output was truncated to ${limitedTable?.rowLimit} rows.",
                    )
                }
                if (cellsTruncated) {
                    add(
                        "Long cell values were truncated to ${limitedTable?.cellLimit} characters.",
                    )
                }
            }.joinToString(" ")
                .takeIf { it.isNotBlank() }

        return copy(
            message =
                listOfNotNull(
                    message?.takeIf { it.isNotBlank() },
                    limitMessage,
                ).joinToString(" ").takeIf { it.isNotBlank() },
            text = limitedText?.value ?: text,
            table = limitedTable,
            truncated = truncated || textTruncated || tableTruncated || cellsTruncated,
            textLimit = if (textTruncated) OBJECT_INSPECTOR_TEXT_CHAR_LIMIT else textLimit,
        )
    }
}

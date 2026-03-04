package com.dwarvenpick.app.inspector

import com.dwarvenpick.app.datasource.DatasourceEngine

enum class InspectedObjectType {
    TABLE,
    VIEW,
}

enum class ObjectInspectorSectionStatus {
    OK,
    UNSUPPORTED,
    INSUFFICIENT_PRIVILEGES,
    ERROR,
}

enum class ObjectInspectorSectionKind {
    TEXT,
    TABLE,
    KEY_VALUES,
}

data class ObjectInspectorObjectRef(
    val type: InspectedObjectType,
    val schema: String,
    val name: String,
)

data class ObjectInspectorKeyValue(
    val key: String,
    val value: String?,
)

data class ObjectInspectorTable(
    val columns: List<String>,
    val rows: List<List<String?>>,
)

data class ObjectInspectorSection(
    val id: String,
    val title: String,
    val status: ObjectInspectorSectionStatus,
    val message: String? = null,
    val kind: ObjectInspectorSectionKind? = null,
    val text: String? = null,
    val table: ObjectInspectorTable? = null,
    val keyValues: List<ObjectInspectorKeyValue>? = null,
)

data class ObjectInspectorResponse(
    val datasourceId: String,
    val datasourceName: String,
    val engine: DatasourceEngine,
    val credentialProfile: String,
    val inspectedAt: String,
    val objectRef: ObjectInspectorObjectRef,
    val sections: List<ObjectInspectorSection>,
)

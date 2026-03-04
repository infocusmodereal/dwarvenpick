package com.dwarvenpick.app.inspector

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import java.sql.Connection

interface ObjectInspectorProvider {
    val engines: Set<DatasourceEngine>

    fun inspect(
        spec: ConnectionSpec,
        connection: Connection,
        objectRef: ObjectInspectorObjectRef,
    ): List<ObjectInspectorSection>
}

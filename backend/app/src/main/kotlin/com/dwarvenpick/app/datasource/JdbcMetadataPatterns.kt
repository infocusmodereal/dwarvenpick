package com.dwarvenpick.app.datasource

import java.sql.DatabaseMetaData

internal fun DatabaseMetaData.literalSearchPattern(identifier: String): String {
    val escape = searchStringEscape.orEmpty()
    if (escape.isEmpty()) return identifier

    return identifier
        .replace(escape, escape + escape)
        .replace("%", escape + "%")
        .replace("_", escape + "_")
}

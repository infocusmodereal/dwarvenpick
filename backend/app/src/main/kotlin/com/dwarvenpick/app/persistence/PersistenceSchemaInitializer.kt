package com.dwarvenpick.app.persistence

import org.springframework.stereotype.Component

/**
 * Marker bean kept for repositories that depend on schema readiness.
 *
 * The concrete database schema is now owned by Flyway migrations under db/migration.
 */
@Component
class PersistenceSchemaInitializer

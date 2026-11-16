package io.max.paperplay.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/** [path] null means in-memory. Creates or upgrades the file by `user_version`. */
fun libraryDriver(path: String? = null): JdbcSqliteDriver = JdbcSqliteDriver(
    if (path == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path",
    // Several threads write at once (scan, lookups, sync); they wait for each other up to 30 s instead of failing with "database is locked",
    // and readers never block a writer.
    Properties().apply {
        put("foreign_keys", "true")
        if (path != null) { put("busy_timeout", "30000"); put("journal_mode", "WAL"); put("synchronous", "NORMAL") }
    },
    LibrarySchema,
)

/** The same driver typed as [SqlDriver], for callers that do not have the JDBC driver on their classpath. */
fun openLibraryDriver(path: String): app.cash.sqldelight.db.SqlDriver = libraryDriver(path)

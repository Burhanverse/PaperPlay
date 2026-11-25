package io.max.paperplay.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Opens [name] in the app's databases dir; the helper's version is Drift's `user_version`. */
fun libraryDriver(context: Context, name: String = "library.sqlite"): SqlDriver = AndroidSqliteDriver(
    LibrarySchema,
    context,
    name,
    callback = object : AndroidSqliteDriver.Callback(LibrarySchema) {
        /** Without it there is one connection for all, and a screen that reads one setting waits for every long read or write before it. */
        override fun onConfigure(db: SupportSQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }
    },
)

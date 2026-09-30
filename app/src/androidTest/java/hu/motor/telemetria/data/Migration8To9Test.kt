package hu.motor.telemetria.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-8-9-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun keepsTracksAndAddsTelemetryOutbox() {
        open(8, object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "startTime INTEGER NOT NULL, endTime INTEGER, distanceMeters REAL NOT NULL DEFAULT 0, " +
                        "durationMillis INTEGER NOT NULL DEFAULT 0, movingMillis INTEGER NOT NULL DEFAULT 0, " +
                        "maxSpeedMps REAL NOT NULL DEFAULT 0, elevationGainMeters REAL NOT NULL DEFAULT 0, " +
                        "pointCount INTEGER NOT NULL DEFAULT 0, remoteId INTEGER, syncedPoints INTEGER NOT NULL DEFAULT 0, " +
                        "dirty INTEGER NOT NULL DEFAULT 1)"
                )
                db.execSQL("INSERT INTO tracks (startTime) VALUES (1234)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).close()

        val migrated = open(9, object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_8_9.migrate(db)
            }
        })
        val db = migrated.writableDatabase
        val trackColumns = db.query("PRAGMA table_info(tracks)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        assertTrue("telemetryVersion" in trackColumns)
        assertTrue("telemetrySampleCount" in trackColumns)
        assertTrue("syncedTelemetrySamples" in trackColumns)
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue("telemetry_samples" in tables)
        assertTrue(db.query("SELECT id FROM tracks WHERE startTime = 1234").use { it.moveToFirst() })
        migrated.close()
    }

    private fun open(version: Int, callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build()
        ).also { it.writableDatabase.version = version }
}

package hu.motor.telemetria.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Track::class,
        TrackPoint::class,
        PendingDelete::class,
        TrackAnalysisEntity::class,
        Place::class,
        GeoLabel::class,
        RoadPoint::class,
        SavedRoute::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    abstract fun placeDao(): PlaceDao

    abstract fun geoLabelDao(): GeoLabelDao

    abstract fun roadPointDao(): RoadPointDao

    abstract fun routeDao(): RouteDao

    companion object {
        /** A szerverre feltöltéshez kellő oszlopok és a törlési sor – a régi túrák megmaradnak. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN remoteId INTEGER")
                db.execSQL("ALTER TABLE tracks ADD COLUMN syncedPoints INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_deletes " +
                        "(trackId INTEGER NOT NULL, PRIMARY KEY(trackId))"
                )
            }
        }

        /** A szerveren számolt túraelemzés helyi másolata. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS track_analysis (" +
                        "trackId INTEGER NOT NULL, json TEXT NOT NULL, " +
                        "pointCount INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(trackId))"
                )
            }
        }

        /** Nevezetes helyek (otthon, munkahely, célpontok) az automatikus indításhoz. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS places (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, type TEXT NOT NULL, " +
                        "lat REAL NOT NULL, lon REAL NOT NULL, " +
                        "radiusMeters INTEGER NOT NULL, enabled INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /** Koordinátákhoz tartozó címek gyorsítótára (fordított geokódolás). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS geo_labels (" +
                        "key TEXT NOT NULL, label TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, PRIMARY KEY(key))"
                )
            }
        }

        /** Útmenti elemek (mérők, lámpák, bejelentések) helyi másolata. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS road_points (" +
                        "id INTEGER NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, " +
                        "lat REAL NOT NULL, lon REAL NOT NULL, road TEXT, description TEXT, " +
                        "speedLimit INTEGER, reportedAt INTEGER, expiresAt INTEGER, " +
                        "PRIMARY KEY(id))"
                )
            }
        }

        /** Tervezett útvonalak: a köztes pontok és a szervertől kapott utolsó terv. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saved_routes (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, remoteId INTEGER, " +
                        "name TEXT NOT NULL, waypointsJson TEXT NOT NULL, planJson TEXT, " +
                        "style TEXT NOT NULL, distanceMeters REAL NOT NULL, " +
                        "averageMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        /** Az automatikusan eltett tervek megkülönböztetése a kézzel mentettektől. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_routes ADD COLUMN autoSaved INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "motor-telemetria.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
            }
    }
}

package au.buzz.ryzewave.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import kotlinx.coroutines.flow.Flow

/*
 * Room schema (see docs/APP.md, package `data`). All times are epoch milliseconds (phone local clock).
 * Tables:
 *   steps_hour  (hourStart PK)          one B2 / B1 record per hour
 *   hr_sample   (time, source PK)       F7 history bins, F7 03 auto pushes, E5/FD live samples
 *   spo2_sample (time, source PK)       34 FA history bins, 34 00 spot / auto results
 *   sleep_stage (start PK)              32 stage records
 *   workout     (id auto)               phone-side workouts
 *   track_point (workoutId, time PK)    raw GPS fixes of a workout
 *   sync_cursor (kind PK)               last sync / export times per kind
 *   hc_export   (clientRecordId PK)     Health Connect export ledger: content fingerprint of every record written
 *
 * Every table that feeds the Health Connect export carries `updatedAt`: the phone time at which the row was
 * inserted or last *changed* (the repository skips rows that are re-delivered unchanged, which the watch does
 * on every sync). `xxxSince(time)` in the repository filters on this column, so an export cursor never misses
 * late-arriving history or the hour-in-progress step row that keeps growing. The ledger (`hc_export`, schema
 * version 3) is what makes the cursor's lookback idempotent: a record whose fingerprint equals the ledger's is
 * not written again (see `health.HealthConnectExportPlanner`).
 *
 * Single-column primary keys are already unique-indexed by SQLite; explicit indices are declared where the
 * time column is not the sole PK (composite keys and the workout start) and on `updatedAt`.
 */

// ---------------------------------------------------------------- entities

@Entity(tableName = "steps_hour", indices = [Index(value = ["updatedAt"])])
data class StepsHourEntity(
    @PrimaryKey val hourStart: Long,
    val total: Int,
    val walk: Int,
    val run: Int,
    /** Phone time of the last insert or value change of this row. */
    val updatedAt: Long,
)

@Entity(
    tableName = "hr_sample",
    primaryKeys = ["time", "source"],
    indices = [Index(value = ["time"]), Index(value = ["updatedAt"])],
)
data class HrSampleEntity(
    val time: Long,
    /** [SampleSource.name]; stored as text so the schema stays readable. */
    val source: String,
    val bpm: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "spo2_sample",
    primaryKeys = ["time", "source"],
    indices = [Index(value = ["time"]), Index(value = ["updatedAt"])],
)
data class Spo2SampleEntity(
    val time: Long,
    val source: String,
    val percent: Int,
    val updatedAt: Long,
)

@Entity(tableName = "sleep_stage", indices = [Index(value = ["updatedAt"])])
data class SleepStageEntity(
    @PrimaryKey val start: Long,
    val stage: Int,
    val minutes: Int,
    val updatedAt: Long,
)

/** `end` is an SQL keyword, hence [endTime]. */
@Entity(tableName = "workout", indices = [Index(value = ["start"]), Index(value = ["updatedAt"])])
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Long,
    val endTime: Long?,
    val sportType: Int,
    val distanceMeters: Double,
    val durationSeconds: Int,
    val avgHr: Int?,
    val maxHr: Int?,
    val calories: Int,
    val updatedAt: Long,
    /** Per-workout steps from the watch and from the phone; added in schema version 4 (see [Db.MIGRATION_3_4]). */
    val steps: Int? = null,
    val phoneSteps: Int? = null,
    /** User-chosen Health Connect exercise type override; added in schema version 4. Null = use the heuristic. */
    val exerciseTypeOverride: Int? = null,
)

@Entity(tableName = "track_point", primaryKeys = ["workoutId", "time"], indices = [Index(value = ["time"])])
data class TrackPointEntity(
    val workoutId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val altitudeM: Double?,
    val accepted: Boolean,
    /** Added in schema version 2 (see [Db.MIGRATION_1_2]); null on rows from version 1. */
    val cumulativeM: Double?,
    /** True for a fix taken while paused; added in schema version 4 (see [Db.MIGRATION_3_4]), default 0. */
    @ColumnInfo(defaultValue = "0") val paused: Boolean = false,
)

/** Always-on GPS breadcrumb (Settings opt-in); added in schema version 5 (see [Db.MIGRATION_4_5]). */
@Entity(tableName = "breadcrumb")
data class BreadcrumbEntity(
    @PrimaryKey val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val altitudeM: Double?,
    val activity: String?,
)

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val kind: String,
    val time: Long,
)

/**
 * One row per Health Connect record (or planner marker) ever written: the content fingerprint it was written
 * with (`HealthConnectMapping.fingerprint`) and when. Added in schema version 3 (see [Db.MIGRATION_2_3]).
 */
@Entity(tableName = "hc_export")
data class HcExportEntity(
    @PrimaryKey val clientRecordId: String,
    val fingerprint: Long,
    val exportedAt: Long,
)

// ---------------------------------------------------------------- aggregate projections

/** SUM() of the step columns over a time window (0 when the window is empty). */
data class StepTotals(val total: Int, val walk: Int, val run: Int)

/** MIN/MAX/AVG of heart-rate samples over a time window (all null when the window is empty). */
data class HrStats(val minBpm: Int?, val maxBpm: Int?, val avgBpm: Double?)

// ---------------------------------------------------------------- entity <-> core model mapping

internal fun sourceOf(name: String): SampleSource =
    SampleSource.values().firstOrNull { it.name == name } ?: SampleSource.HISTORY

fun StepsHourEntity.toModel(): StepsHour = StepsHour(hourStart, total, walk, run)
fun StepsHour.toEntity(updatedAt: Long): StepsHourEntity = StepsHourEntity(hourStart, total, walk, run, updatedAt)

fun HrSampleEntity.toModel(): HrSample = HrSample(time, bpm, sourceOf(source))
fun HrSample.toEntity(updatedAt: Long): HrSampleEntity = HrSampleEntity(time, source.name, bpm, updatedAt)

fun Spo2SampleEntity.toModel(): Spo2Sample = Spo2Sample(time, percent, sourceOf(source))
fun Spo2Sample.toEntity(updatedAt: Long): Spo2SampleEntity = Spo2SampleEntity(time, source.name, percent, updatedAt)

fun SleepStageEntity.toModel(): SleepStage = SleepStage(start, stage, minutes)
fun SleepStage.toEntity(updatedAt: Long): SleepStageEntity = SleepStageEntity(start, stage, minutes, updatedAt)

fun WorkoutEntity.toModel(): Workout =
    Workout(id, start, endTime, sportType, distanceMeters, durationSeconds, avgHr, maxHr, calories, steps, phoneSteps, exerciseTypeOverride)

fun Workout.toEntity(updatedAt: Long): WorkoutEntity =
    WorkoutEntity(id, start, end, sportType, distanceMeters, durationSeconds, avgHr, maxHr, calories, updatedAt, steps, phoneSteps, exerciseTypeOverride)

fun TrackPointEntity.toModel(): TrackPoint =
    TrackPoint(workoutId, time, lat, lon, accuracyM, speedMps, altitudeM, accepted, cumulativeM, paused)

fun TrackPoint.toEntity(): TrackPointEntity =
    TrackPointEntity(workoutId, time, lat, lon, accuracyM, speedMps, altitudeM, accepted, cumulativeM, paused)

// ---------------------------------------------------------------- DAOs
// `between` ranges are half-open: fromTime <= t < toTime. `rangeOnce` is inclusive on both ends (it fetches the
// rows that an incoming batch may replace). `changedSince` is updatedAt >= time.

@Dao
interface StepsDao {
    @Upsert
    suspend fun upsert(rows: List<StepsHourEntity>)

    @Query("SELECT * FROM steps_hour WHERE hourStart >= :fromTime AND hourStart < :toTime ORDER BY hourStart")
    fun between(fromTime: Long, toTime: Long): Flow<List<StepsHourEntity>>

    @Query("SELECT * FROM steps_hour WHERE hourStart >= :fromTime AND hourStart <= :toTime ORDER BY hourStart")
    suspend fun rangeOnce(fromTime: Long, toTime: Long): List<StepsHourEntity>

    @Query("SELECT * FROM steps_hour WHERE updatedAt >= :time ORDER BY hourStart")
    suspend fun changedSince(time: Long): List<StepsHourEntity>

    @Query(
        "SELECT COALESCE(SUM(total), 0) AS total, COALESCE(SUM(walk), 0) AS walk, COALESCE(SUM(run), 0) AS run " +
            "FROM steps_hour WHERE hourStart >= :fromTime AND hourStart < :toTime"
    )
    fun totals(fromTime: Long, toTime: Long): Flow<StepTotals?>

    @Query(
        "SELECT COALESCE(SUM(total), 0) AS total, COALESCE(SUM(walk), 0) AS walk, COALESCE(SUM(run), 0) AS run " +
            "FROM steps_hour WHERE hourStart >= :fromTime AND hourStart < :toTime"
    )
    suspend fun totalsOnce(fromTime: Long, toTime: Long): StepTotals?
}

@Dao
interface HrDao {
    @Upsert
    suspend fun upsert(rows: List<HrSampleEntity>)

    @Query("SELECT * FROM hr_sample WHERE time >= :fromTime AND time < :toTime ORDER BY time")
    fun between(fromTime: Long, toTime: Long): Flow<List<HrSampleEntity>>

    @Query("SELECT * FROM hr_sample WHERE time >= :fromTime AND time <= :toTime ORDER BY time")
    suspend fun rangeOnce(fromTime: Long, toTime: Long): List<HrSampleEntity>

    @Query("SELECT * FROM hr_sample WHERE updatedAt >= :time ORDER BY time")
    suspend fun changedSince(time: Long): List<HrSampleEntity>

    @Query("SELECT * FROM hr_sample WHERE time >= :fromTime AND time < :toTime ORDER BY time DESC LIMIT 1")
    fun last(fromTime: Long, toTime: Long): Flow<HrSampleEntity?>

    @Query("SELECT * FROM hr_sample WHERE time >= :fromTime AND time < :toTime ORDER BY time DESC LIMIT 1")
    suspend fun lastOnce(fromTime: Long, toTime: Long): HrSampleEntity?

    /**
     * Daily min/max/avg. Uses the periodic 10-minute series (sources HISTORY and AUTO, see [SampleSource]) when
     * the window has any, otherwise every sample: 1 Hz live/workout streams would otherwise swamp the average.
     */
    @Query(
        "SELECT MIN(bpm) AS minBpm, MAX(bpm) AS maxBpm, AVG(bpm) AS avgBpm " +
            "FROM hr_sample WHERE time >= :fromTime AND time < :toTime " +
            "AND (source IN ('HISTORY', 'AUTO') OR NOT EXISTS (" +
            "SELECT 1 FROM hr_sample p WHERE p.time >= :fromTime AND p.time < :toTime " +
            "AND p.source IN ('HISTORY', 'AUTO')))"
    )
    fun stats(fromTime: Long, toTime: Long): Flow<HrStats?>

    @Query(
        "SELECT MIN(bpm) AS minBpm, MAX(bpm) AS maxBpm, AVG(bpm) AS avgBpm " +
            "FROM hr_sample WHERE time >= :fromTime AND time < :toTime " +
            "AND (source IN ('HISTORY', 'AUTO') OR NOT EXISTS (" +
            "SELECT 1 FROM hr_sample p WHERE p.time >= :fromTime AND p.time < :toTime " +
            "AND p.source IN ('HISTORY', 'AUTO')))"
    )
    suspend fun statsOnce(fromTime: Long, toTime: Long): HrStats?
}

@Dao
interface Spo2Dao {
    @Upsert
    suspend fun upsert(rows: List<Spo2SampleEntity>)

    @Query("SELECT * FROM spo2_sample WHERE time >= :fromTime AND time < :toTime ORDER BY time")
    fun between(fromTime: Long, toTime: Long): Flow<List<Spo2SampleEntity>>

    @Query("SELECT * FROM spo2_sample WHERE time >= :fromTime AND time <= :toTime ORDER BY time")
    suspend fun rangeOnce(fromTime: Long, toTime: Long): List<Spo2SampleEntity>

    @Query("SELECT * FROM spo2_sample WHERE updatedAt >= :time ORDER BY time")
    suspend fun changedSince(time: Long): List<Spo2SampleEntity>

    @Query("SELECT * FROM spo2_sample WHERE time >= :fromTime AND time < :toTime ORDER BY time DESC LIMIT 1")
    fun last(fromTime: Long, toTime: Long): Flow<Spo2SampleEntity?>

    @Query("SELECT * FROM spo2_sample WHERE time >= :fromTime AND time < :toTime ORDER BY time DESC LIMIT 1")
    suspend fun lastOnce(fromTime: Long, toTime: Long): Spo2SampleEntity?
}

@Dao
interface SleepDao {
    @Upsert
    suspend fun upsert(rows: List<SleepStageEntity>)

    @Query("SELECT * FROM sleep_stage WHERE start >= :fromTime AND start < :toTime ORDER BY start")
    fun between(fromTime: Long, toTime: Long): Flow<List<SleepStageEntity>>

    @Query("SELECT * FROM sleep_stage WHERE start >= :fromTime AND start <= :toTime ORDER BY start")
    suspend fun rangeOnce(fromTime: Long, toTime: Long): List<SleepStageEntity>

    @Query("SELECT * FROM sleep_stage WHERE updatedAt >= :time ORDER BY start")
    suspend fun changedSince(time: Long): List<SleepStageEntity>

    /** Half-open [fromTime, toTime): used by `replaceSleepForNight` to clear a night before re-inserting it. */
    @Query("DELETE FROM sleep_stage WHERE start >= :fromTime AND start < :toTime")
    suspend fun deleteRange(fromTime: Long, toTime: Long)
}

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(row: WorkoutEntity): Long

    @Update
    suspend fun update(row: WorkoutEntity)

    /** Most recent first. */
    @Query("SELECT * FROM workout ORDER BY start DESC")
    fun all(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workout WHERE id = :id")
    fun byId(id: Long): Flow<WorkoutEntity?>

    /** Finished workouts inserted or updated at/after [time]; a workout still running (no end) is excluded. */
    @Query("SELECT * FROM workout WHERE updatedAt >= :time AND endTime IS NOT NULL ORDER BY start")
    suspend fun finishedChangedSince(time: Long): List<WorkoutEntity>
}

@Dao
interface TrackPointDao {
    @Upsert
    suspend fun upsert(rows: List<TrackPointEntity>)

    @Query("SELECT * FROM track_point WHERE workoutId = :workoutId ORDER BY time")
    fun forWorkout(workoutId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_point WHERE workoutId = :workoutId ORDER BY time")
    suspend fun forWorkoutOnce(workoutId: Long): List<TrackPointEntity>
}

@Dao
interface BreadcrumbDao {
    @Upsert
    suspend fun upsert(rows: List<BreadcrumbEntity>)

    @Query("SELECT * FROM breadcrumb WHERE time BETWEEN :from AND :to ORDER BY time")
    suspend fun between(from: Long, to: Long): List<BreadcrumbEntity>

    @Query("DELETE FROM breadcrumb WHERE time < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM breadcrumb")
    suspend fun count(): Int
}

@Dao
interface SyncCursorDao {
    @Upsert
    suspend fun upsert(row: SyncCursorEntity)

    @Query("SELECT time FROM sync_cursor WHERE kind = :kind")
    suspend fun get(kind: String): Long?
}

@Dao
interface HcExportDao {
    @Upsert
    suspend fun upsert(rows: List<HcExportEntity>)

    /** Callers chunk [ids] (SQLite allows 999 bound variables per statement). */
    @Query("SELECT * FROM hc_export WHERE clientRecordId IN (:ids)")
    suspend fun byIds(ids: List<String>): List<HcExportEntity>

    @Query("SELECT COUNT(*) FROM hc_export")
    suspend fun count(): Int

    @Query("DELETE FROM hc_export")
    suspend fun clear()
}

// ---------------------------------------------------------------- database

@Database(
    entities = [
        StepsHourEntity::class,
        HrSampleEntity::class,
        Spo2SampleEntity::class,
        SleepStageEntity::class,
        WorkoutEntity::class,
        TrackPointEntity::class,
        SyncCursorEntity::class,
        HcExportEntity::class,
        BreadcrumbEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun steps(): StepsDao
    abstract fun hr(): HrDao
    abstract fun spo2(): Spo2Dao
    abstract fun sleep(): SleepDao
    abstract fun workouts(): WorkoutDao
    abstract fun trackPoints(): TrackPointDao
    abstract fun syncCursors(): SyncCursorDao
    abstract fun hcExport(): HcExportDao
    abstract fun breadcrumbs(): BreadcrumbDao

    companion object {
        const val NAME = "ryzewave.db"

        /** v1 → v2: `track_point.cumulativeM` (nullable REAL); existing rows keep null. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_point ADD COLUMN cumulativeM REAL")
            }
        }

        /** v2 → v3: the Health Connect export ledger (`hc_export`); starts empty, so the next export's candidates are written once more. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hc_export` (`clientRecordId` TEXT NOT NULL, `fingerprint` INTEGER NOT NULL, " +
                        "`exportedAt` INTEGER NOT NULL, PRIMARY KEY(`clientRecordId`))",
                )
            }
        }

        /**
         * v3 → v4: per-workout steps (`workout.steps`, `workout.phoneSteps` — nullable), the user's exercise-type
         * override (`workout.exerciseTypeOverride` — nullable) and the paused flag on GPS fixes
         * (`track_point.paused` — NOT NULL DEFAULT 0, matching `@ColumnInfo(defaultValue = "0")`). The statements
         * are exposed as [MIGRATION_3_4_SQL] so a unit test can assert they add exactly these columns.
         */
        val MIGRATION_3_4_SQL: List<String> = listOf(
            "ALTER TABLE `workout` ADD COLUMN `steps` INTEGER",
            "ALTER TABLE `workout` ADD COLUMN `phoneSteps` INTEGER",
            "ALTER TABLE `workout` ADD COLUMN `exerciseTypeOverride` INTEGER",
            "ALTER TABLE `track_point` ADD COLUMN `paused` INTEGER NOT NULL DEFAULT 0",
        )

        /** Schema version 5: the always-on GPS breadcrumb table (docs/PLAN.md, opt-in). */
        val MIGRATION_4_5_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `breadcrumb` (`time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                "`accuracyM` REAL NOT NULL, `speedMps` REAL NOT NULL, `altitudeM` REAL, `activity` TEXT, PRIMARY KEY(`time`))",
        )

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_4_5_SQL) db.execSQL(sql)
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_3_4_SQL) db.execSQL(sql)
            }
        }

        @Volatile
        private var instance: Db? = null

        /**
         * Process-wide singleton bound to the application context. Every schema step has a real migration and
         * there is deliberately *no* `fallbackToDestructiveMigration()`: a version this build has no migration
         * for (a downgrade, or a forgotten `MIGRATION_n_m`) throws `IllegalStateException` at the first query
         * instead of silently wiping every health table.
         */
        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, Db::class.java, NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}

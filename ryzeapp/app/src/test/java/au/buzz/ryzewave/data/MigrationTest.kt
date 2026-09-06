package au.buzz.ryzewave.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v3 -> v4 migration must add exactly the columns the Room entities gained, with the right SQLite types and
 * nullability, or Room's runtime schema validation refuses to open the database. Instrumentation isn't available
 * here, so the statement list ([Db.MIGRATION_3_4_SQL], applied verbatim by [Db.MIGRATION_3_4]) is asserted.
 */
class MigrationTest {

    @Test
    fun migration3to4HasTheRightVersions() {
        assertEquals(3, Db.MIGRATION_3_4.startVersion)
        assertEquals(4, Db.MIGRATION_3_4.endVersion)
    }

    @Test
    fun migration3to4AddsExactlyTheNewColumns() {
        val sql = Db.MIGRATION_3_4_SQL.map { it.replace("`", "").lowercase() }
        assertEquals(4, sql.size)
        // Workout.steps / phoneSteps / exerciseTypeOverride are nullable Int -> INTEGER with no NOT NULL, no default
        assertTrue(sql.contains("alter table workout add column steps integer"))
        assertTrue(sql.contains("alter table workout add column phonesteps integer"))
        assertTrue(sql.contains("alter table workout add column exercisetypeoverride integer"))
        // TrackPoint.paused is a non-null Boolean with @ColumnInfo(defaultValue = "0") -> INTEGER NOT NULL DEFAULT 0
        assertTrue(sql.contains("alter table track_point add column paused integer not null default 0"))
        // no nullable column may carry a NOT NULL/default (that would break Room's schema match)
        assertTrue(sql.none { it.contains("workout") && it.contains("not null") })
    }

    @Test
    fun migration4to5CreatesTheBreadcrumbTable() {
        assertEquals(4, Db.MIGRATION_4_5.startVersion)
        assertEquals(5, Db.MIGRATION_4_5.endVersion)
        val sql = Db.MIGRATION_4_5_SQL.single().replace("`", "").lowercase()
        assertTrue(sql.startsWith("create table if not exists breadcrumb"))
        for (col in listOf("time integer not null", "lat real not null", "lon real not null", "accuracym real not null", "speedmps real not null", "altitudem real", "activity text", "primary key(time)")) {
            assertTrue("missing $col", sql.contains(col))
        }
    }
}

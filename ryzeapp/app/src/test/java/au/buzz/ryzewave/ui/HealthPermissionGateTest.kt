package au.buzz.ryzewave.ui

import au.buzz.ryzewave.health.HealthConnectExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings gate ("Permissions granted", "Export now" enabled) must be the exporter's own test: the six
 * record write permissions. The exercise route is requested with them but optional — declining only it must not
 * paint the status red or disable the manual export while the background exports keep running without it.
 */
class HealthPermissionGateTest {
    private val route = HealthConnectExporter.ROUTE_PERMISSION
    private val required = HealthConnectExporter.REQUIRED_PERMISSIONS
    private val all = HealthConnectExporter.WRITE_PERMISSIONS

    @Test
    fun theSetsAreWhatTheManifestDeclares() {
        assertEquals(6, required.size)
        assertEquals(7, all.size)
        assertEquals(setOf(route), HealthConnectExporter.OPTIONAL_PERMISSIONS)
        assertTrue(all.containsAll(required))
        assertFalse(route in required)
        assertTrue(required.all { it.startsWith("android.permission.health.WRITE_") })
    }

    @Test
    fun everythingGranted() {
        val g = HealthPermissionGate.evaluate(all)
        assertTrue(g.exportAllowed)
        assertTrue(g.missingRequired.isEmpty())
        assertTrue(g.missingOptional.isEmpty())
    }

    @Test
    fun onlyTheRouteDeclinedStillAllowsTheExport() {
        val g = HealthPermissionGate.evaluate(required)
        assertTrue(g.exportAllowed)                                   // what canExport() / runExport need
        assertTrue(g.missingRequired.isEmpty())
        assertEquals(setOf(route), g.missingOptional)                 // the Settings hint
        assertTrue(HealthConnectExporter.granted(required))
        assertFalse(HealthConnectExporter.routeGranted(required))
        assertTrue(HealthConnectExporter.routeGranted(all))
    }

    @Test
    fun aMissingRecordPermissionBlocksTheExport() {
        val steps = required.first { it.endsWith("WRITE_STEPS") }
        val g = HealthPermissionGate.evaluate(all - steps)
        assertFalse(g.exportAllowed)
        assertEquals(setOf(steps), g.missingRequired)
        assertTrue(g.missingOptional.isEmpty())
        assertFalse(HealthConnectExporter.granted(all - steps))
    }

    @Test
    fun nothingGranted() {
        val g = HealthPermissionGate.evaluate(emptySet())
        assertFalse(g.exportAllowed)
        assertEquals(required, g.missingRequired)
        assertEquals(setOf(route), g.missingOptional)
        // an empty required set can never count as granted (a misconfigured host)
        assertFalse(HealthPermissionGate.evaluate(all, required = emptySet(), optional = emptySet()).exportAllowed)
    }

    /** The host is built with the request set (7) and the required set (6); the optional set is their difference. */
    @Test
    fun theInstancePropertiesMatchTheCompanionSets() {
        // HealthConnectExporter.requiredPermissions / writePermissions / optionalPermissions are instance mirrors of the
        // companion constants (MainActivity wires them into HealthConnectPermissionHost); keep them consistent.
        assertEquals(all - required, HealthConnectExporter.OPTIONAL_PERMISSIONS)
        val g = HealthPermissionGate.evaluate(required, required = required, optional = all - required)
        assertTrue(g.exportAllowed)
        assertEquals(setOf(route), g.missingOptional)
    }
}

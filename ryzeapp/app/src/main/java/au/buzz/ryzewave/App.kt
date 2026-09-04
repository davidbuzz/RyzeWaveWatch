package au.buzz.ryzewave

import android.app.Application
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.WatchApi

/**
 * Manual service locator. Each layer registers its implementation here from `App.onCreate` (see Graph.create).
 * Keep it tiny: no DI framework.
 */
class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = Graph.create(this)
    }

    companion object {
        lateinit var instance: App
            private set
        val graph: Graph get() = instance.graph
    }
}

/** Wiring of the concrete implementations. Filled in by the integration step; see docs/APP.md. */
class Graph(
    val repo: HealthRepository,
    val settings: SettingsStore,
    val watch: WatchApi,
    val health: au.buzz.ryzewave.health.HealthConnectExporter,
) {
    companion object {
        fun create(app: App): Graph = GraphFactory.create(app)
    }
}

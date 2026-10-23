package dev.proprio.wear

import android.app.Application
import dev.proprio.wear.platform.AppGraph
import kotlinx.coroutines.launch

class ProprioApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // App start: zip sessions cut short by a crash/kill and retry pending transfers.
        graph.scope.launch { graph.controller.resumeTransfers() }
    }
}

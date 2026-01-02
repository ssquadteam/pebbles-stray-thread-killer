package tech.sethi.pebbles.straythreadkiller

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

object StrayThreadKiller : ModInitializer {
    private val logger = LoggerFactory.getLogger("stray-thread-killer")
    var server: MinecraftServer? = null
    var isServerRunning = false

    private val watchThread = Executors.newSingleThreadExecutor()

    private val safeThreadPatterns = listOf(
        "DestroyJavaVM",
        "Reference Handler",
        "Finalizer",
        "Signal Dispatcher",
        "Attach Listener",
        "Common-Cleaner",
        "Notification Thread"
    )

    override fun onInitialize() {
        logger.info("=== STRAY THREAD KILLER DEBUG VERSION INITIALIZED ===")
        
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server
            isServerRunning = true

            ConfigHandler

            if (!ConfigHandler.config.enabled) {
                logger.info("Stray Thread Killer is disabled. Skipping initialization.")
                return@register
            }

            logger.info("[DEBUG] Server started, beginning thread monitoring")

            watchThread.execute {
                try {
                    while (isServerRunning) {
                        Thread.sleep(5000)

                        if (server == null || !server.isRunning) {
                            logger.info("=================================================================")
                            logger.info("[DEBUG] Server is no longer running, stopping thread monitoring.")
                            logger.info("=================================================================")
                            Thread.sleep(ConfigHandler.config.waitToShutdownSeconds * 1000L)
                            isServerRunning = false
                            forceShutdownStrayThreads()
                        }
                    }
                } catch (e: InterruptedException) {
                    logger.warn("[DEBUG] Watch thread interrupted", e)
                } finally {
                    logger.info("[DEBUG] Shutting down watch thread executor.")
                    watchThread.shutdown()
                }
            }
        }
        
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            logger.info("=================================================================")
            logger.info("[DEBUG] SERVER_STOPPING event received!")
            logger.info("=================================================================")
            logAllThreads("SERVER_STOPPING")
        }
    }

    private fun logAllThreads(context: String) {
        logger.info("=== THREAD DUMP AT $context ===")
        logger.info("Total threads: ${Thread.getAllStackTraces().size}")
        
        val threadsByGroup = Thread.getAllStackTraces().keys.groupBy { it.threadGroup?.name ?: "null" }
        
        threadsByGroup.forEach { (groupName, threads) ->
            logger.info("--- Thread Group: $groupName (${threads.size} threads) ---")
        }
        logger.info("=== END THREAD SUMMARY ===")
    }

    private fun forceShutdownStrayThreads() {
        logger.info("=================================================================")
        logger.info("[DEBUG] === ATTEMPTING TO SHUTDOWN STRAY THREADS ===")
        logger.info("=================================================================")

        val allThreads = Thread.getAllStackTraces()
        logger.info("[DEBUG] Total threads found: ${allThreads.size}")
        
        val daemonThreads = mutableListOf<Thread>()
        val nonDaemonThreads = mutableListOf<Thread>()
        val safeThreads = mutableListOf<Thread>()
        
        allThreads.keys.forEach { thread ->
            when {
                safeThreadPatterns.any { thread.name.contains(it) } -> safeThreads.add(thread)
                thread.isDaemon -> daemonThreads.add(thread)
                else -> nonDaemonThreads.add(thread)
            }
        }
        
        logger.info("[DEBUG] Thread breakdown:")
        logger.info("[DEBUG]   - Daemon threads: ${daemonThreads.size}")
        logger.info("[DEBUG]   - Non-daemon threads: ${nonDaemonThreads.size}")
        logger.info("[DEBUG]   - Safe/JVM threads (skipping): ${safeThreads.size}")
        
        logger.info("")
        logger.info("=== SAFE THREADS (will NOT interrupt) ===")
        safeThreads.forEach { thread ->
            logger.info("  [SAFE] ${thread.name} (daemon=${thread.isDaemon}, state=${thread.state})")
        }
        
        logger.info("")
        logger.info("=== DAEMON THREADS (${daemonThreads.size}) ===")
        daemonThreads.sortedBy { it.name }.forEach { thread ->
            logThreadDetail(thread, allThreads[thread] ?: emptyArray())
        }
        
        logger.info("")
        logger.info("=================================================================")
        logger.info("=== NON-DAEMON THREADS (${nonDaemonThreads.size}) - THESE BLOCK SHUTDOWN ===")
        logger.info("=================================================================")
        nonDaemonThreads.sortedBy { it.name }.forEach { thread ->
            logThreadDetail(thread, allThreads[thread] ?: emptyArray(), detailed = true)
        }
        
        logger.info("")
        logger.info("=== PHASE 1: GRACEFUL INTERRUPT ===")
        nonDaemonThreads.filter { !safeThreadPatterns.any { pattern -> it.name.contains(pattern) } }
            .forEach { thread ->
                try {
                    logger.info("[INTERRUPT] Interrupting non-daemon thread: ${thread.name}")
                    thread.interrupt()
                } catch (e: Exception) {
                    logger.error("[INTERRUPT] Failed to interrupt ${thread.name}: ${e.message}")
                }
            }
        
        logger.info("[DEBUG] Waiting 2 seconds for threads to respond to interrupt...")
        try {
            Thread.sleep(2000)
        } catch (e: InterruptedException) {
        }
        
        val stillAlive = Thread.getAllStackTraces().keys
            .filter { !it.isDaemon }
            .filter { !safeThreadPatterns.any { pattern -> it.name.contains(pattern) } }
        
        if (stillAlive.isNotEmpty()) {
            logger.info("")
            logger.info("=== PHASE 2: THREADS STILL ALIVE (${stillAlive.size}) ===")
            stillAlive.forEach { thread ->
                logger.warn("[STILL ALIVE] ${thread.name} - state=${thread.state}")
                val stack = Thread.getAllStackTraces()[thread]
                if (stack != null && stack.isNotEmpty()) {
                    logger.warn("  Current location: ${stack.firstOrNull()}")
                }
            }
        }

        logger.info("")
        logger.info("=================================================================")
        logger.info("[DEBUG] All non-daemon threads have been interrupted.")
        logger.info("[DEBUG] Forcing JVM shutdown with Runtime.halt(0)")
        logger.info("=================================================================")

        Runtime.getRuntime().halt(0)
    }
    
    private fun logThreadDetail(thread: Thread, stackTrace: Array<StackTraceElement>, detailed: Boolean = false) {
        val prefix = if (detailed) "[PROBLEM]" else "[DAEMON]"
        logger.info("")
        logger.info("$prefix Thread: ${thread.name}")
        logger.info("$prefix   ID: ${thread.id}")
        logger.info("$prefix   State: ${thread.state}")
        logger.info("$prefix   Daemon: ${thread.isDaemon}")
        logger.info("$prefix   Priority: ${thread.priority}")
        logger.info("$prefix   Group: ${thread.threadGroup?.name ?: "null"}")
        logger.info("$prefix   Alive: ${thread.isAlive}")
        logger.info("$prefix   Interrupted: ${thread.isInterrupted}")
        
        if (stackTrace.isNotEmpty()) {
            logger.info("$prefix   Stack trace (${stackTrace.size} frames):")
            val framesToShow = if (detailed) stackTrace.size else minOf(5, stackTrace.size)
            stackTrace.take(framesToShow).forEachIndexed { index, frame ->
                logger.info("$prefix     [$index] $frame")
            }
            if (!detailed && stackTrace.size > 5) {
                logger.info("$prefix     ... ${stackTrace.size - 5} more frames")
            }
        } else {
            logger.info("$prefix   Stack trace: <empty>")
        }
        
        if (detailed) {
            identifyThreadSource(thread, stackTrace)
        }
    }
    
    private fun identifyThreadSource(thread: Thread, stackTrace: Array<StackTraceElement>) {
        val name = thread.name.lowercase()
        val stackString = stackTrace.joinToString("\n") { it.toString() }
        
        val source = when {
            name.contains("qtp") || stackString.contains("jetty") -> "Jetty (Spark web server) - ShardServer HTTP API"
            name.contains("tebex") -> "Tebex store plugin"
            name.contains("luckperms") -> "LuckPerms permissions plugin"
            name.contains("husksync") -> "HuskSync cross-server sync"
            name.contains("spark") -> "Spark performance profiler"
            name.contains("worldedit") -> "WorldEdit"
            name.contains("okhttp") -> "OkHttp HTTP client (used by various mods)"
            name.contains("scheduler") -> "Generic scheduler (check stack)"
            name.contains("io-worker") -> "Minecraft IO Worker"
            name.contains("forge") -> "Forge compatibility layer"
            name.contains("pool-") -> "Generic thread pool (check stack for origin)"
            stackString.contains("smashmc") -> "SmashMC mod"
            stackString.contains("cobblemon") -> "Cobblemon"
            else -> "Unknown - check stack trace"
        }
        
        logger.info("[PROBLEM]   >>> IDENTIFIED SOURCE: $source")
    }
}
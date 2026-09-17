package ai.rever.boss.run

import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationSettings
import ai.rever.boss.plugin.run.RunConfigurationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Concurrency and persistence tests for [RunConfigurationManager].
 *
 * Regression coverage for the lost-update races and torn writes fixed in
 * [ai.rever.boss.run.RunConfigurationManager] (issue #754, landed as #755):
 * concurrent mutations (add, update, remove) must serialize under the settings
 * mutex without dropping configurations, and every completed mutation must be
 * persisted atomically so the decoded on-disk state always matches memory.
 *
 * Each test runs against a hermetic temp file via [RunConfigurationManager.resetForTesting]
 * and restores the singleton to the real settings file when it finishes, so other tests in
 * the same JVM (which use the real file) are unaffected.
 */
class RunConfigurationConcurrencyTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        // A private directory: the file starts non-existent, and teardown is a single
        // recursive delete instead of a scan of the shared system temp dir.
        tempDir = Files.createTempDirectory("run-config-test-").toFile()
        tempFile = File(tempDir, "run-configurations.json")
        RunConfigurationManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        // Point the singleton back at the user's file and reload, so tests that run after
        // this class observe the same state the app would. With no argument this restores the
        // production path captured once in the manager, so the literal cannot drift.
        RunConfigurationManager.resetForTesting()
        // atomicWriteText writes a unique sibling "<name>.<random>.tmp" inside tempDir and
        // moves it into place; only a JVM kill mid-write could leave one behind.
        tempDir.deleteRecursively()
    }

    private fun createConfig(index: Int) =
        RunConfiguration(
            id = "config-$index",
            name = "Main $index",
            type = RunConfigurationType.MAIN_FUNCTION,
            filePath = "/path/to/project/src/Main$index.kt",
            lineNumber = index,
            language = Language.KOTLIN,
            command = "run $index",
            workingDirectory = "/path/to/project",
        )

    @Test
    fun `concurrent additions do not drop configurations`() =
        runBlocking(Dispatchers.Default) {
            val count = 50
            // All coroutines are dispatched before any of them mutates, so the race window is
            // maximal even on low-core runners where the dispatcher would otherwise serialize
            // them; same start-gate pattern as RunConfigurationPersistenceTest.
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map { index ->
                    async {
                        start.await()
                        RunConfigurationManager.addConfiguration(createConfig(index))
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            val inMemoryConfigs = RunConfigurationManager.currentSettings.value.configurations
            assertEquals(
                count,
                inMemoryConfigs.size,
                "Expected all $count configurations to be retained in memory without race overwrites",
            )

            // Verify disk persistence
            assertTrue(tempFile.exists(), "Settings file should have been created on disk")
            val diskContent = tempFile.readText()
            val diskSettings = json.decodeFromString<RunConfigurationSettings>(diskContent)
            assertEquals(
                count,
                diskSettings.configurations.size,
                "Expected all $count configurations to be persisted to disk",
            )
        }

    @Test
    fun `concurrent duplicate additions add only once`() =
        runBlocking(Dispatchers.Default) {
            val duplicateConfig = createConfig(1)
            val count = 20
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map {
                    async {
                        start.await()
                        RunConfigurationManager.addConfiguration(duplicateConfig)
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            val inMemoryConfigs = RunConfigurationManager.currentSettings.value.configurations
            assertEquals(
                1,
                inMemoryConfigs.size,
                "Duplicate filePath configurations should be deduplicated under concurrency",
            )

            assertTrue(tempFile.exists(), "Settings file should have been created on disk")
            val diskContent = tempFile.readText()
            val diskSettings = json.decodeFromString<RunConfigurationSettings>(diskContent)
            assertEquals(1, diskSettings.configurations.size)
        }

    @Test
    fun `concurrent mixed add update and remove maintain integrity`() =
        runBlocking(Dispatchers.Default) {
            // Seed with 20 initial configurations
            val initialCount = 20
            for (i in 1..initialCount) {
                RunConfigurationManager.addConfiguration(createConfig(i))
            }
            assertEquals(initialCount, RunConfigurationManager.currentSettings.value.configurations.size)

            // Concurrently perform:
            // 1. Add 10 new configs (indices 21..30)
            // 2. Update 5 existing configs (indices 1..5)
            // 3. Remove 5 existing configs (indices 6..10)
            val start = CompletableDeferred<Unit>()
            val addJobs =
                (21..30).map { i ->
                    async {
                        start.await()
                        RunConfigurationManager.addConfiguration(createConfig(i))
                    }
                }
            val updateJobs =
                (1..5).map { i ->
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(
                            createConfig(i).copy(command = "updated-command-$i"),
                        )
                    }
                }
            val removeJobs =
                (6..10).map { i ->
                    async {
                        start.await()
                        RunConfigurationManager.removeConfiguration("config-$i")
                    }
                }

            start.complete(Unit)
            (addJobs + updateJobs + removeJobs).awaitAll()

            val finalConfigs = RunConfigurationManager.currentSettings.value.configurations
            // Expected size: 20 initial + 10 added - 5 removed = 25
            assertEquals(25, finalConfigs.size, "Final count should accurately reflect additions and removals")

            // Verify updates were applied
            for (i in 1..5) {
                val updated = finalConfigs.find { it.id == "config-$i" }
                assertEquals("updated-command-$i", updated?.command)
            }

            // Verify removals were applied
            for (i in 6..10) {
                val removed = finalConfigs.find { it.id == "config-$i" }
                assertNull(removed)
            }

            // Verify disk persistence matches in-memory state
            assertTrue(tempFile.exists(), "Settings file should have been created on disk")
            val diskContent = tempFile.readText()
            val diskSettings = json.decodeFromString<RunConfigurationSettings>(diskContent)
            assertEquals(25, diskSettings.configurations.size)
        }

    @Test
    fun `concurrent removeConfiguration races updateConfiguration on same configuration`() =
        runBlocking(Dispatchers.Default) {
            for (round in 1..10) {
                val config = createConfig(100 + round)
                RunConfigurationManager.addConfiguration(config)
                assertEquals(1, RunConfigurationManager.currentSettings.value.configurations.size)

                // Race remove vs update on the exact same configuration
                val start = CompletableDeferred<Unit>()
                val removeJob =
                    async {
                        start.await()
                        RunConfigurationManager.removeConfiguration(config.id)
                    }
                val updateJob =
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(
                            config.copy(command = "updated-command-round-$round"),
                        )
                    }

                start.complete(Unit)
                awaitAll(removeJob, updateJob)

                val configs = RunConfigurationManager.currentSettings.value.configurations
                // Under serialized execution:
                // - If remove runs first, update's map finds nothing to replace -> configs is empty.
                // - If update runs first, update replaces config, then remove filters it out -> configs is empty.
                // In an unsynchronized race without the mutex, update can overwrite remove's
                // result and resurrect the config.
                assertEquals(
                    0,
                    configs.size,
                    "Configuration should be cleanly removed regardless of race order, round $round",
                )

                assertTrue(tempFile.exists(), "Settings file should have been written on disk")
                val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
                assertEquals(0, diskSettings.configurations.size)

                // Explicitly confirm updateConfiguration on an already-deleted ID:
                // - Completes normally without throwing any exception
                // - Does not resurrect or insert the missing configuration (safe no-op)
                RunConfigurationManager.updateConfiguration(config)
                assertEquals(
                    0,
                    RunConfigurationManager.currentSettings.value.configurations.size,
                    "updateConfiguration on deleted ID must safely no-op without inserting or modifying",
                )
            }
        }

    @Test
    fun `lastUsedConfigId is cleared when config is removed mid-race with update to different config`() =
        runBlocking(Dispatchers.Default) {
            for (round in 1..10) {
                val configA = createConfig(1000 + round)
                val configB = createConfig(2000 + round)

                // Initialize with configA and configB, with lastUsedConfigId pointing to configA
                val initialSettings =
                    RunConfigurationSettings(
                        configurations = listOf(configA, configB),
                        lastUsedConfigId = configA.id,
                        recentConfigIds = listOf(configA.id, configB.id),
                    )
                tempFile.writeText(json.encodeToString(RunConfigurationSettings.serializer(), initialSettings))
                RunConfigurationManager.resetForTesting(tempFile)

                assertEquals(configA.id, RunConfigurationManager.currentSettings.value.lastUsedConfigId)

                // Race: remove configA while concurrently updating configB
                val start = CompletableDeferred<Unit>()
                val removeJob =
                    async {
                        start.await()
                        RunConfigurationManager.removeConfiguration(configA.id)
                    }
                val updateJob =
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(
                            configB.copy(command = "updated-b-$round"),
                        )
                    }

                start.complete(Unit)
                awaitAll(removeJob, updateJob)

                val finalSettings = RunConfigurationManager.currentSettings.value
                // Verify lastUsedConfigId was cleared (not resurrected by configB update)
                assertNull(
                    finalSettings.lastUsedConfigId,
                    "lastUsedConfigId must be cleared despite a concurrent update to configB (round $round)",
                )

                // Verify recentConfigIds no longer contains configA
                assertTrue(configA.id !in finalSettings.recentConfigIds)
                assertTrue(configB.id in finalSettings.recentConfigIds)

                // Verify configA is gone and configB was updated
                assertEquals(1, finalSettings.configurations.size)
                assertEquals(configB.id, finalSettings.configurations[0].id)
                assertEquals("updated-b-$round", finalSettings.configurations[0].command)

                // Verify disk persistence matches in-memory state
                assertTrue(tempFile.exists(), "Settings file should have been written on disk")
                val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
                assertNull(diskSettings.lastUsedConfigId)
                assertEquals(1, diskSettings.configurations.size)
            }
        }

    @Test
    fun `loadSettingsSync gracefully handles corrupt json without crashing`() {
        tempFile.writeText("{ corrupted unparseable json content ... ")
        RunConfigurationManager.resetForTesting(tempFile)

        val settings = RunConfigurationManager.currentSettings.value
        assertEquals(0, settings.configurations.size, "Corrupt file should recover to empty configurations")
    }
}

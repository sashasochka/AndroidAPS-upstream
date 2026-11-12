package app.aaps.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.JvmAppDatabaseBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A database whose version has no migration route, or that comes from a newer AAPS (a downgrade), must
 * fail to open, not be recreated empty.
 *
 * `fallbackToDestructiveMigration(dropAllTables = false)` enables the destructive fallback in Room 2.7+:
 * the parameter only decides whether tables Room does not know are dropped too. An empty database means
 * no boluses and no carbs, so the loop would see IOB and COB as 0.
 */
class MissingMigrationRouteTest {

    private val directory = createTempDirectory("aaps-missing-route-test")
    private val path = "$directory/aaps.db"
    private val driver = BundledSQLiteDriver()

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a database with no migration route fails to open and keeps its rows`() = runTest {
        assertFailsToOpenAndKeepsRows(version = 1, message = "A migration from 1 to $DATABASE_VERSION was required but not found")
    }

    @Test
    fun `a database from a newer version fails to open and keeps its rows`() = runTest {
        val newer = DATABASE_VERSION + 1
        assertFailsToOpenAndKeepsRows(version = newer, message = "A migration from $newer to $DATABASE_VERSION was required but not found")
    }

    private suspend fun assertFailsToOpenAndKeepsRows(version: Int, message: String) {
        driver.open(path).let { connection ->
            connection.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, amount REAL NOT NULL)")
            connection.execSQL("INSERT INTO history (id, amount) VALUES (1, 4.5)")
            connection.execSQL("PRAGMA user_version = $version")
            connection.close()
        }

        val database = JvmAppDatabaseBuilder().provideAppDatabase(path)
        try {
            // The first query opens the file and looks for a route.
            val failure = assertFailsWith<IllegalStateException> { database.heartRateDao.getFromTime(0) }
            assertThat(failure).hasMessageThat().contains(message)
        } finally {
            database.close()
        }

        driver.open(path).let { connection ->
            try {
                connection.prepare("PRAGMA user_version").use { statement ->
                    statement.step()
                    assertThat(statement.getLong(0)).isEqualTo(version.toLong())
                }
                connection.prepare("SELECT amount FROM history WHERE id = 1").use { statement ->
                    assertThat(statement.step()).isTrue()
                    assertThat(statement.getDouble(0)).isEqualTo(4.5)
                }
            } finally {
                connection.close()
            }
        }
    }
}

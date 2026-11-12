package app.aaps.settingsstore

import androidx.datastore.core.DataMigration
import app.aaps.core.interfaces.sharedPreferences.StoreCipher
import app.aaps.shared.impl.sharedPreferences.StoreCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Moves the settings from the plain SharedPreferences file that `SPImpl` wrote (until 2026-10) into the
 * encrypted store, once.
 *
 * The old file is not changed and stays for one release, so an older version installed again still finds
 * the settings as they were on the day of the move. The release after that deletes it.
 *
 * "Once" is the [marker] file. It is written after the first good open, also when there was nothing to
 * move, or by Start fresh when it sets a store aside, and from then on nothing is moved again: not after starting fresh from an unreadable store, and not
 * when an older version wrote the old file meanwhile. Those settings can be dangerously old - the state of
 * a pump or a pod that is long gone, for example. One exception: an old file that exists but gives no
 * settings at all is not marked as moved, so the next start tries again. SharedPreferences answers a
 * damaged file with an empty map instead of an error.
 *
 * DataStore runs it before the store is first read, writes the result, and only then calls [cleanUp]. If
 * the start is cut short in between, the next start does the same again: values already in the store win,
 * because they can only be newer. [cleanUp] also reads the written file back and compares every moved value,
 * and logs the names (never the values) of any that differ.
 *
 * Every value keeps its type, with two exceptions:
 * - A Float becomes a Double through its decimal text. `SPImpl.putDouble` stored a Float, and the plain
 *   conversion would turn 0.1 into 0.10000000149011612.
 * - A set of strings has no place in the store and is left behind, named in [log]. No current code writes
 *   one, and the old file still has it.
 */
class SharedPreferencesMigration(
    /** The SharedPreferences file itself, only to know whether there is anything to move. */
    private val oldFile: File,
    /** Everything in that file, as `SharedPreferences.getAll()` returns it. */
    private val readOld: () -> Map<String, *>,
    private val storeFile: File,
    private val cipher: StoreCipher,
    private val marker: File,
    /** Lines for the log. The app's logger does not exist yet when this runs: it reads its settings from this store. */
    private val log: (String) -> Unit
) : DataMigration<Map<String, Any>> {

    private var oldPresent = false
    private var oldCount = 0
    private var moved: Map<String, Any> = emptyMap()

    override suspend fun shouldMigrate(currentData: Map<String, Any>): Boolean = !marker.exists()

    override suspend fun migrate(currentData: Map<String, Any>): Map<String, Any> {
        // The .bak too: a process killed while SharedPreferences was writing leaves only that, and
        // SharedPreferences puts it back when the file is read.
        oldPresent = oldFile.exists() || File(oldFile.path + ".bak").exists()
        if (!oldPresent) return currentData
        val values = storable(readOld()) { key, value -> log("Setting $key is not moved: a ${value::class.simpleName} has no place in the store") }
        oldCount = values.size
        // Only what is new: after a start that was cut short, the store may have the values already.
        moved = values.filterKeys { it !in currentData }
        return values + currentData
    }

    override suspend fun cleanUp() {
        // Never throws: DataStore would fail the whole start for it, while the next start simply moves again.
        try {
            if (oldPresent && oldCount == 0) {
                log("${oldFile.name} gave no settings to move. It is kept, and the next start tries again.")
                return
            }
            if (moved.isNotEmpty()) {
                val written = StoreCodec.decode(storeFile.readBytes(), cipher)
                val differ = moved.filter { (key, value) -> written[key] != value }.keys
                if (differ.isNotEmpty()) log("${differ.size} moved settings read back different: ${differ.sorted().joinToString()}")
                log("Moved ${moved.size} settings from ${oldFile.name} into the encrypted store, ${moved.size - differ.size} read back the same. The old file is kept for one release.")
            }
            writeMarker(marker, "${System.currentTimeMillis()} ${moved.size}\n")
        } catch (e: Exception) {
            log("Could not finish the move of the settings, the next start does it again: ${e.message}")
        }
    }

    companion object {

        /**
         * Writes [marker] through a synced temporary file and a rename, so it never exists empty. The marker
         * only has to exist to stop every later move, so a plain write that failed half way (a full disk, a
         * power loss) left an empty marker and skipped the move for good. The directory is not synced: if the
         * rename itself is lost, the marker is missing and the next start moves again, which is the safe side.
         */
        internal fun writeMarker(marker: File, text: String) {
            val pending = File(marker.path + ".new")
            try {
                FileOutputStream(pending).use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(pending.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                if (pending.isFile) pending.delete()
                throw e
            }
        }

        /** The values the store can hold, converted where needed. [skipped] hears about each one that is left behind. */
        internal fun storable(old: Map<String, *>, skipped: (key: String, value: Any) -> Unit): Map<String, Any> =
            old.mapNotNull { (key, value) ->
                when (value) {
                    null                                   -> null
                    is String, is Boolean, is Int, is Long -> key to value
                    is Float                               -> key to value.toString().toDouble()
                    is Double                              -> key to value

                    else                                   -> {
                        skipped(key, value)
                        null
                    }
                }
            }.toMap()
    }
}

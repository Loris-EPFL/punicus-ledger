package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.repository.FirstRunNotice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [FirstRunNotice] kept in the file at [path] (a [VersionedJsonFile]). No file is a first start: not dismissed. A
 * damaged file counts as dismissed, and is set aside with dismissed written in its place; one that cannot be read
 * counts as dismissed too, as the port says: the notice is help, and a start never repeats it on a guess.
 */
class FileFirstRunNotice(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FirstRunNotice {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "first-run notice",
        formatVersion = FORMAT_VERSION,
        empty = Saved(dismissed = false),
        decode = { _, text -> Saved(JSON.decodeFromString(StoredNotice.serializer(), text).dismissed) },
        encode = { saved ->
            JSON.encodeToString(StoredNotice.serializer(), StoredNotice(FORMAT_VERSION, saved.dismissed))
        },
    )

    override fun observe(): Flow<Boolean> = file.observe()
        .onEach { read ->
            // A damaged file is set aside: dismissed is written in its place, so the next start, which finds no
            // file otherwise, does not take it for a first start.
            if (read is VersionedJsonFile.Read.Loaded && read.damagedSetAside && !read.value.dismissed) {
                file.change(notSaved = Unit) { current ->
                    Decision(Unit, next = Saved(dismissed = true).takeUnless { current.dismissed })
                }
            }
        }
        .map { read ->
            when (read) {
                is VersionedJsonFile.Read.Loaded -> read.damagedSetAside || read.value.dismissed
                is VersionedJsonFile.Read.Unreadable -> true
            }
        }

    override suspend fun dismiss(): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(dismissed = true)) }

    /** What the file holds; wrapped, since the file reads a null value as damaged. */
    private data class Saved(val dismissed: Boolean)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredNotice(val version: Int, val dismissed: Boolean)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}

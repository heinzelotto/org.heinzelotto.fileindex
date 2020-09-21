package org.heinzelotto.fileindex

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Combined lexeme index over several files that allows to add, modify and remove individual files' indexes.
 *
 * An individual index is maintained for every file. This makes it easy to modify or remove a file's index. When
 * querying the database all the individual indexes are queried and the results are accumulated.
 */
class IndexDb {

    // TODO: later maybe store some file ID instead of the full path
    private val indexes = HashMap<Path, SingleFileIndex>()
    private val rwLock = ReentrantReadWriteLock(true)

    /**
     * Write lock the RW lock and execute some code.
     */
    private inline fun withWriteLock(body: () -> Unit) =
            try {
                rwLock.writeLock().lock()
                body()
            } finally {
                rwLock.writeLock().unlock()
            }

    /**
     * Read lock the RW lock and execute some code.
     */
    private inline fun<T> withReadLock(body: () -> T): T =
        try {
            rwLock.readLock().lock()
            body()
        } finally {
            rwLock.readLock().unlock()
        }

    /**
     * Add a single file's index to the database.
     */
    fun createFileIndex(path: Path, fileIndex: SingleFileIndex) =
        withWriteLock {
            indexes[path] = fileIndex
        }

    /**
     * Update a single file's index in the database, replacing the previous one.
     *
     * Only update if the existing revision is older.
     */
    fun modifyFileIndex(path: Path, fileIndex: SingleFileIndex) =
        withWriteLock {
            if (indexes.contains(path) && indexes[path]!!.modificationTime <= fileIndex.modificationTime)
                indexes[path] = fileIndex
        }

    /**
     * Delete a single file's index from the database.
     */
    fun deleteFileIndex(path: Path) =
        withWriteLock {
            indexes.remove(path)
        }

    /**
     * Query all occurrences of a lexeme across all indexed files.
     */
    fun query(needle: String): List<FilePosition> =
        withReadLock {
            indexes.flatMap { entry -> entry.value.singleFileHashMap[needle] ?: arrayListOf() }
        }

    /**
     * The search string -> occurrences map index for a single file.
     */
    class SingleFileIndex(
            /**
             * Collections of matches for each Lexeme.
             *
             * For simplicity, the file path is stored with every match, trading space for time complexity.
             */
            val singleFileHashMap: Map<String, List<FilePosition>>,

            /**
             * Modification time
             */
            val modificationTime: Instant
    )
}

/**
 * Range within a file representing a search match.
 */
class FilePosition(
        val filePath: Path,
        val range: IntRange)
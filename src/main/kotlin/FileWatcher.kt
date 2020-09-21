package org.heinzelotto.fileindex

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Wrapper around a file watcher event.
 */
data class FileNotification(
        /**
         * The kind of file event.
         */
        val eventKind: EventKind,

        /**
         * Absolute path of modified file.
         */
        val filePath: Path,

        /**
         * For Create and Modify events, current modification time of the file.
         */
        val mTime: FileTime?) {
    /**
     * File system event kind.
     */
    enum class EventKind {
        /**
         * Triggered when a file is created.
         */
        Created,

        /**
         * Triggered when a file is modified.
         */
        Modified,

        /**
         * Triggered when a file is deleted.
         */
        Deleted,
    }
}

/**
 * Watch a directory recursively.
 */
class FileWatcher(
        /**
         * Path of the directory to watch.
         */
        private val rootPath: Path,
        /**
         * Channel to use for output.
         */
        private val channel: Channel<FileNotification> = Channel())
    : Channel<FileNotification> by channel {

    // TODO ?should we use another scope
    private val coroutineScope: CoroutineScope = GlobalScope
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private var watchKeys = ArrayList<WatchKey>()

    /**
     * Recursively add watched directories, starting at the root.
     */
    private fun registerPaths() {
        watchKeys.apply {
            forEach { it.cancel() }
            clear()
        }
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(subPath: Path, attrs: BasicFileAttributes): FileVisitResult {
                watchKeys.add(subPath.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE))
                return FileVisitResult.CONTINUE
            }
        })
    }

    init {
        // only directories are supported, no single file
        assert(!rootPath.toFile().isFile)

        registerPaths()
        var needsReregister = false

        coroutineScope.launch(Dispatchers.IO) {

            while (!isClosedForSend) {
                if (needsReregister) {
                    registerPaths()
                    needsReregister = false
                }

                val key = watchService.take()
                val dirPath = key.watchable() as? Path ?: break
                key.pollEvents().forEach {
                    // full path to file
                    val eventPath = dirPath.resolve(it.context() as Path)

                    val eventKind = when (it.kind()) {
                        ENTRY_CREATE -> FileNotification.EventKind.Created
                        ENTRY_DELETE -> FileNotification.EventKind.Deleted
                        else -> FileNotification.EventKind.Modified
                    }

                    try {
                        var mTime: FileTime? = null
                        if (eventKind == FileNotification.EventKind.Created ||
                                eventKind == FileNotification.EventKind.Modified) {
                            mTime = Files.readAttributes(eventPath, BasicFileAttributes::class.java).lastModifiedTime()
                        }

                        val event = FileNotification(eventKind, eventPath, mTime)

                        // if a folder is created or deleted, reregister the whole tree recursively
                        if ((event.eventKind == FileNotification.EventKind.Created
                                        || event.eventKind == FileNotification.EventKind.Deleted)
                                && event.filePath.toFile().isDirectory) {
                            needsReregister = true
                        }

                        if (event.filePath.toFile().isFile) {
                            channel.send(event)
                        }
                    } catch (e: NoSuchFileException) {
                        println(e)
                    }
                }

                if (!key.reset()) {
                    key.cancel()
                    // the watch service closes, we also close our channel
                    close()
                    break
                } else if (isClosedForSend) {
                    break
                }
            }
        }
    }

    override fun close(cause: Throwable?): Boolean {
        watchKeys.apply {
            forEach { it.cancel() }
            clear()
        }

        return channel.close(cause)
    }
}



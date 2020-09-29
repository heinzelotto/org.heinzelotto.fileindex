package org.heinzelotto.fileindex

import com.sun.nio.file.SensitivityWatchEventModifier
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
    val mTime: FileTime?
) {
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
@ExperimentalCoroutinesApi
class FileWatcher(
    /**
     * Path of the directory to watch.
     */
    private val rootPath: Path,
    /**
     * Channel to use for output.
     */
    private val channel: Channel<FileNotification> = Channel()
) : ReceiveChannel<FileNotification> by channel {

    // TODO ?should we use another scope
    private val coroutineScope: CoroutineScope = GlobalScope
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private var watchKeys = ArrayList<WatchKey>()

    // track which folders we currently watch, since we can't get this information when they are deleted
    private val watchedFolders = HashSet<Path>()

    /**
     * Recursively add watched directories, starting at the root.
     */
    private fun registerPaths() {
        watchKeys.apply {
            forEach { it.cancel() }
            clear()
        }
        watchedFolders.clear()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(subPath: Path, attrs: BasicFileAttributes): FileVisitResult {
                watchKeys.add(
                    subPath.register(
                        watchService,
                        arrayOf(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE),
                        SensitivityWatchEventModifier.HIGH
                    )
                )
                watchedFolders.add(subPath)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Recursively send create events for all files in a directory.
     */
    private fun sendCreateEventsForSubdir(dir: Path) {
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(filePath: Path, attrs: BasicFileAttributes): FileVisitResult {

                val mTime = Files.readAttributes(filePath, BasicFileAttributes::class.java).lastModifiedTime()
                val event = FileNotification(FileNotification.EventKind.Created, filePath, mTime)

                runBlocking {
                    channel.send(event)
                }
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

            while (!channel.isClosedForSend) {
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
                            eventKind == FileNotification.EventKind.Modified
                        ) {
                            mTime = Files.readAttributes(eventPath, BasicFileAttributes::class.java).lastModifiedTime()
                        }

                        val event = FileNotification(eventKind, eventPath, mTime)

                        val isDirectory = if (event.eventKind == FileNotification.EventKind.Deleted) {
                            event.filePath in watchedFolders
                        } else {
                            event.filePath.toFile().isDirectory
                        }

                        if (!isDirectory) {
                            channel.send(event)
                        } else {
                            // if a folder is created or deleted, reregister the whole tree recursively
                            if (event.eventKind == FileNotification.EventKind.Created
                                || event.eventKind == FileNotification.EventKind.Deleted
                            ) {
                                needsReregister = true
                            }

                            // if the folder is created, recursively send create events for all contained files.
                            if (event.eventKind == FileNotification.EventKind.Created)
                                sendCreateEventsForSubdir(event.filePath)
                        }
                    } catch (e: NoSuchFileException) {
                        println(
                            "FileWatcher: tried to access a non-existing file while creating an event. " +
                                    "It was probably a short-lived file. ${e.file}"
                        )
                    }
                }

                if (!key.reset()) {
                    key.cancel()
                    // the watch service closes, we also close our channel
                    channel.close()
                    break
                } else if (channel.isClosedForSend) {
                    break
                }
            }
        }
    }

    override fun cancel(cause: CancellationException?) {
        watchKeys.apply {
            forEach { it.cancel() }
                    clear()
        }

        return channel.cancel(cause)
    }
}



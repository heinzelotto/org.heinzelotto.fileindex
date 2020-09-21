package org.heinzelotto.fileindex

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Wrapper around a file loader event.
 */
data class LoadedFileNotification(
    /**
     * The underlying file watcher event.
     */
    val notification: FileNotification,
    /**
     * For Create and Modify events, the text of the file.
     */
    val text: String?,
    /**
     * For Create and Modify events, the time at which the file was read.
     */
    val textTimeStamp: java.time.Instant?
)

/**
 * Watch a directory recursively and load modified files as strings.
 */
class FileLoader(
    /**
     * Path of the directory to watch.
     */
    private val rootPath: Path,
    /**
     * Channel to use for output.
     */
    private val channel: Channel<LoadedFileNotification> = Channel(),
    /**
     * Delay before read, must be higher than you filesystems modification time resolution.
     */
    private val delayBeforeRead: Long = 20
) : Channel<LoadedFileNotification> by channel {

    // TODO ?should we use another scope
    private val coroutineScope: CoroutineScope = GlobalScope
    private val fileWatcher: FileWatcher = FileWatcher(rootPath)

    init {
        coroutineScope.launch(Dispatchers.IO) {

            for (fn in fileWatcher) {

                // TODO: it might be a good idea to preserve the order of the notifications by handling them serially.
                //  File I/O doesn't profit from concurrency anyway.
                launch /*(Dispatchers.IO)*/ { // XXX: ?I assume Dispatchers.IO is inherited by surrounding scope

                    if (fn.eventKind == FileNotification.EventKind.Deleted) {

                        channel.send(LoadedFileNotification(fn, null, null))

                    } else {
                        // We read the file, and if mTime has not changed when we are done reading the file we assume
                        // that it is ok to use the data.
                        //
                        // There is one catch however: The file modification time resolution depends on the filesystem
                        // driver, e. g. ext4 gets it from the kernel HZ value which is on my machine 300Hz ~= 3.3ms.
                        // File modifications in short succession will get the same mTime and by just looking at the
                        // mTime we can't be sure that another modification has not taken place.
                        //
                        // But we can use a trick: Before starting to read we wait a short time. If the file has been
                        // modified again within the filesystem resolution and not after, we will just read that second
                        // file revision instead of the original, which is fine. If the file has been modified after we
                        // will definitely notice the mTime change and can discard the read contents.

                        delay(delayBeforeRead)

                        // we should use a more precise time in the notification than the modification time, lets use
                        // the read time
                        val readTimeStamp = java.time.Instant.now()

                        try {
                            val fileContents = Files.readString(fn.filePath)

                            val mTimeAfterRead =
                                Files.readAttributes(fn.filePath, BasicFileAttributes::class.java).lastModifiedTime()
                            assert(mTimeAfterRead >= fn.mTime)

                            if (mTimeAfterRead == fn.mTime) {
                                channel.send(LoadedFileNotification(fn, fileContents, readTimeStamp))

                                // for later, add some debouncing logic: plug an additional queue before the channel. If the
                                // queue has not been flushed into the channel for a while, fold the queued elements (e. g.
                                // MODIFICATION -> MODIFICATION -> DELETE can just be removed) and flush it, else just add
                                // to the queue.
                            }
                        } catch (e: Exception) {
                            // common cases are java.nio.file.NoSuchFileException (file is deleted again)
                            // or java.nio.charset.MalformedInputException (file is not in utf-8)
                        }
                    }
                }
            }
        }
    }

    override fun close(cause: Throwable?): Boolean {
        fileWatcher.cancel()

        return channel.close(cause)
    }
}
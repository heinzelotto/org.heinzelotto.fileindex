package org.heinzelotto.fileindex

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

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
@ExperimentalCoroutinesApi
class FileLoader(
    /**
     * Path of the directory to watch.
     */
    rootPath: Path,
    /**
     * Channel to use for output.
     */
    private val channel: Channel<LoadedFileNotification> = Channel(),
    /**
     * Delay before read, must be higher than you filesystems modification time resolution.
     */
    delayBeforeRead: Long = 200
) : ReceiveChannel<LoadedFileNotification> by channel {

    // TODO ?should we use another scope
    private val coroutineScope: CoroutineScope = GlobalScope
    private val fileWatcher: FileWatcher = FileWatcher(rootPath)
    private val delayer = Delayer(delayBeforeRead)
    private val notificationQueue = ArrayDeque<FileNotification>()
    private val notificationQueueLock = ReentrantLock()

    init {
        coroutineScope.launch(Dispatchers.IO) {

            for (fn in fileWatcher) {

                // we add the event to a queue, later all the events in the queue will be handled at once
                notificationQueueLock.withLock {
                    notificationQueue.addLast(fn)
                }

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

                // Each time a new event comes in, the processing task is rescheduled into the future. When no events
                // come for a while, all events that are pending to be processed will be compacted (duplicates removed)
                // and handled at once.
                delayer.resetTimeoutAndTask {
                    notificationQueueLock.withLock {

                        removeNotificationRedundancies(notificationQueue)

                        runBlocking {
                            for (noti in notificationQueue) {

                                if (noti.eventKind == FileNotification.EventKind.Deleted) {

                                    channel.send(LoadedFileNotification(noti, null, null))

                                } else {
                                    // we should use a more precise time in the notification than the modification time,
                                    // lets use the read time
                                    val readTimeStamp = java.time.Instant.now()

                                    try {
                                        val fileContents = Files.readString(noti.filePath)

                                        val mTimeAfterRead =
                                            Files.readAttributes(noti.filePath, BasicFileAttributes::class.java)
                                                .lastModifiedTime()
                                        assert(mTimeAfterRead >= noti.mTime)

                                        if (mTimeAfterRead == noti.mTime) {
                                            channel.send(LoadedFileNotification(noti, fileContents, readTimeStamp))
                                        }
                                    } catch (e: MalformedInputException) {
                                        println("FileLoader: cannot decode file, it is not utf-8: ${noti.filePath}")
                                    } catch (e: java.nio.file.NoSuchFileException) {
                                        println(
                                            "FileLoader: tried to access a non-existing file while creating an event. " +
                                                    "It was probably a short-lived file. ${e.file}"
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        }

                        // all have been processed, clear the queue
                        notificationQueue.clear()
                    }
                }
            }
        }
    }

    override fun cancel(cause: CancellationException?) {
        fileWatcher.cancel()

        return channel.cancel(cause)
    }
}

/**
 * This class allows to schedule a task to be run after a delay, and rescheduled further into the future if it has not
 * run yet.
 *
 * This can be used for Delaying and Throttling processing of events. Suppose many events arrive in short succession.
 * Each can reschedule the processing task using the delayer and only one processing run will have to be done. Also,
 * since some events make previous events obsolete, when processing them in bulk the queue can be reduced to a smaller
 * size by removing redundancies.
 */
class Delayer(private val timeout: Long) {
    private var job: CoroutineContext.Element? = null
    private val lock = ReentrantLock()

    /**
     * Schedule a task to be ran after `timeout`, replacing the currently scheduled task if it has not started already.
     *
     * @param task The task to schedule to be run after `timeout` if not itself replaced by a subsequent task.
     */
    fun resetTimeoutAndTask(task: () -> Unit) {
        lock.withLock {

            // cancel previous job
            job?.cancel()

            job = GlobalScope.launch {
                delay(timeout)

                lock.withLock {
                    task()
                }
            }

        }
    }
}

/**
 * This function groups redundant notifications into one.
 *
 * This is done individually per path, and notifications are folded in the following way:
 * created -+ modified => created
 * created -+ deleted => _no notification_
 * modified -+ modified => modified
 * modified -+ deleted => deleted
 * deleted -+ created => modified
 */
fun removeNotificationRedundancies(queue: ArrayDeque<FileNotification>) {
    val m = HashMap<Path, FileNotification>()
    for (fn in queue) {

        if (fn.filePath !in m) {
            m[fn.filePath] = fn
        } else {
            val oldFn = m[fn.filePath]!!

            if (oldFn.eventKind == FileNotification.EventKind.Created && fn.eventKind == FileNotification.EventKind.Modified) {
                m[fn.filePath] = fn.copy(eventKind = FileNotification.EventKind.Created)
            } else if (oldFn.eventKind == FileNotification.EventKind.Created && fn.eventKind == FileNotification.EventKind.Deleted) {
                m.remove(fn.filePath)
            } else if (oldFn.eventKind == FileNotification.EventKind.Modified && fn.eventKind == FileNotification.EventKind.Modified) {
                m[fn.filePath] = fn.copy(eventKind = FileNotification.EventKind.Modified)
            } else if (oldFn.eventKind == FileNotification.EventKind.Modified && fn.eventKind == FileNotification.EventKind.Deleted) {
                m[fn.filePath] = fn.copy(eventKind = FileNotification.EventKind.Deleted, mTime = null)
            } else if (oldFn.eventKind == FileNotification.EventKind.Deleted && fn.eventKind == FileNotification.EventKind.Created) {
                m[fn.filePath] = fn.copy(eventKind = FileNotification.EventKind.Modified)
            }
        }
    }

    queue.clear()
    m.values.toCollection(queue)
}
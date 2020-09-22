package org.heinzelotto.fileindex

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.nio.file.Files

@ExperimentalCoroutinesApi
class FileWatcherTest {
    @Test
    fun `file creation event fired`() {
        runBlocking {
            val cwd = File(System.getProperty("user.dir"))
            val testDirPath = cwd.toPath().resolve("testfilewatcher")
            val testFilePath = testDirPath.resolve("testfilewatcher.txt")
            Files.deleteIfExists(testFilePath)
            Files.createDirectories(testDirPath)

            val watcher = FileWatcher(testDirPath)

            assert(!watcher.isClosedForSend)

            var eventReceived = false
            val job = launch {
                val fileNotification = watcher.receive()
                assertEquals(FileNotification.EventKind.Created, fileNotification.eventKind)
                assertEquals(testFilePath, fileNotification.filePath.toAbsolutePath())
                eventReceived = true
            }

            assert(!watcher.isClosedForSend)

            // add a file and wait until it is registered
            Files.createFile(testFilePath)
            job.join()
            assert(eventReceived)

            watcher.close()

            assert(watcher.isClosedForSend)
        }
    }

    @Test
    fun `recursive watching works, subdir is also watched`() {
        runBlocking {
            val cwd = File(System.getProperty("user.dir"))
            val testDirPath = cwd.toPath().resolve("testfilewatcherrecursive")
            val testDirPathSub = testDirPath.resolve("subDir")
            val testFilePath = testDirPathSub.resolve("testfilewatcherrecursive.txt")

            testDirPath.toFile().deleteRecursively()
            Files.createDirectories(testDirPath)

            val watcher = FileWatcher(testDirPath)

            assert(!watcher.isClosedForSend)

            val job = launch {
                val fileNotification = watcher.receive()
                when (fileNotification.eventKind) {
                    FileNotification.EventKind.Created -> assertEquals(fileNotification.filePath, testFilePath)
                    else -> assert(false)
                }
            }

            assert(!watcher.isClosedForReceive)

            // create a subdir and wait until it is registered
            Files.createDirectory(testDirPathSub)

            // we need a delay here because FileWatcher does not emit directory creation events, so there is no direct
            // way of being notified about the operation finishing
            delay(2000)

            // add a file in the subdir and check that it is registered
            Files.createFile(testFilePath)
            job.join()

            watcher.close()
            assert(watcher.isClosedForReceive)
        }
    }
}
package org.heinzelotto.fileindex

import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.nio.file.Files

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
            launch {
                watcher.consumeEach { fileNotification ->
                    assertEquals(FileNotification.EventKind.Created, fileNotification.eventKind)
                    assertEquals(testFilePath, fileNotification.filePath.toAbsolutePath())
                    eventReceived = true
                }
            }

            assert(!watcher.isClosedForSend)

            // add a file and wait until it is registered
            Files.createFile(testFilePath)
            delay(100)
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

            var eventReceived = false
            launch {
                watcher.consumeEach { fileNotification ->
                    when (fileNotification.eventKind) {
                        FileNotification.EventKind.Created -> assertEquals(fileNotification.filePath, testFilePath)
                        else -> assert(false)
                    }
                    eventReceived = true
                }
            }

            assert(!watcher.isClosedForSend)

            // create a subdir and wait until it is registered
            Files.createDirectory(testDirPathSub)
            delay(100)

            // add a file in the subdir and wait until it is registered
            Files.createFile(testFilePath)
            delay(100)
            assert(eventReceived)

            watcher.close()

            assert(watcher.isClosedForSend)
        }
    }
}
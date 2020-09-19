package org.heinzelotto.fileindex

import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class FileLoaderTest {

    /**
     * A simple creation of a file is observed by a FileLoader correctly reported to us. The file is modified and the
     * updated contents are also yielded by the FileLoader.
     */
    @Test
    fun `content of watched file is loaded correctly`() {
        runBlocking {
            val cwd = File(System.getProperty("user.dir"))
            val testDirPath = cwd.toPath().resolve("testfileloader")
            val testFilePath = testDirPath.resolve("testfileloader.txt")
            Files.deleteIfExists(testFilePath)
            Files.createDirectories(testDirPath)

            val testFileText = "hello world"
            Files.deleteIfExists(testFilePath)

            val loader = FileLoader(testDirPath)

            assert(!loader.isClosedForSend)

            var createEventReceived = false
            var modifyEventReceived = false
            launch {
                loader.consumeEach { loadedFileNotification ->
                    println("$loadedFileNotification")
                    when (loadedFileNotification.notification.eventKind) {
                        FileNotification.EventKind.Created -> {
                            Assertions.assertEquals("", loadedFileNotification.text?.trim())
                            createEventReceived = true
                        }
                        FileNotification.EventKind.Modified -> {
                            Assertions.assertEquals(testFileText, loadedFileNotification.text?.trim())
                            modifyEventReceived = true
                        }
                        FileNotification.EventKind.Deleted -> assert(false)
                    }
                }
            }

            assert(!loader.isClosedForSend)

            // add a file and wait until it is registered
            Files.createFile(testFilePath)
            delay(100)
            assert(createEventReceived)

            // write some data to the file and wait until it is registered
            testFilePath.toFile().printWriter().use { it.println(testFileText) }
            delay(100)
            assert(modifyEventReceived)

            loader.close()

            assert(loader.isClosedForSend)
        }
    }

    /**
     * This test writes to a single file many times consecutively with random jittery delays. The result of reading this
     * file is subject to the race condition that these writes produce. It is tested that the FileLoader correctly
     * discards instances where it cannot be sure that no race has occurred. It is tested that all files that are
     * returned contain consistent data.
     */
    @Test
    fun `file loader consistency`() {
        runBlocking {
            val cwd = File(System.getProperty("user.dir"))
            val testDirPath = cwd.toPath().resolve("testfileloaderconsistency")
            val testFilePath = testDirPath.resolve("testfileloaderconsistency.txt")
            Files.deleteIfExists(testFilePath)
            Files.createDirectories(testDirPath)
            val testFileTextLength = 100000
            val testModificationIterations = 100

            val loader = FileLoader(testDirPath)

            assert(!loader.isClosedForSend)

            val successfulFiles = AtomicInteger(0)
            launch {
                loader.consumeEach { loadedFileNotification ->
                    when (loadedFileNotification.notification.eventKind) {
                        FileNotification.EventKind.Created, FileNotification.EventKind.Modified -> {
                            //println("File Loaded: ${loadedFileNotification.notification} text length: ${loadedFileNotification.text?.length} instant: ${loadedFileNotification.textTimeStamp} text: ${loadedFileNotification.text?.first()}")
                            val trimText = loadedFileNotification.text?.trim()
                            Assertions.assertEquals(testFileTextLength, trimText?.length)

                            // ensure that the text is read correctly
                            val char = trimText!!.first()
                            assert(trimText.all { it == char })

                            successfulFiles.getAndAdd(1)
                        }
                        else -> assert(false)
                    }
                }
            }

            assert(!loader.isClosedForSend)

            // Write to the file in quick succession with varying delays between the writes so that some writes are
            // registered correctly and some are discarded.
            for (i in 0 until testModificationIterations) {

                // each file is filled with a string consisting of a single digit
                val c: Byte = ('0' + (i % 10)).toByte()
                val ar = ByteArray(testFileTextLength) { _ -> c }
                Files.write(testFilePath, ar)

                // introduce varying delays to have some files successfully read and some being discarded
                delay(Random.nextLong() % 100)
            }
            // wait a bit for the watcher to process
            delay(100)

            loader.close()

            assert(loader.isClosedForSend)

            // some modifications are successfully read
            assert(successfulFiles.get() > 2)

            // some modifications were re-modified too quickly so they were discarded. (*2 because currently each
            // modification fires two watcher modification events, one for erase, one for write. We could implement
            // event debouncing in the File loader to get rid of most of these.)
            assert(successfulFiles.get() < 2 * testModificationIterations)
        }
    }
}
package org.heinzelotto.fileindex

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@ExperimentalCoroutinesApi
class FileLoaderTest {

    /**
     * A simple creation of a file is observed by a FileLoader correctly reported to us. The file is modified and the
     * updated contents are also yielded by the FileLoader.
     */
    @Test
    fun `content of watched file is loaded correctly`() {
        runBlocking {

            val testTmpDir = createTempDir("testfileloader")
            val testDirPath = testTmpDir.toPath()
            println("using temporary test dir $testDirPath")

            val testFilePath = testDirPath.resolve("testfileloader.txt")
            Files.deleteIfExists(testFilePath)
            Files.createDirectories(testDirPath)

            val testFileText = "hello world"
            Files.deleteIfExists(testFilePath)

            val loader = FileLoader(testDirPath)

            assert(!loader.isClosedForSend)

            val createJob = launch {
                val loadedFileNotification = loader.receive()
                assertEquals(FileNotification.EventKind.Created, loadedFileNotification.notification.eventKind)

                assertEquals("", loadedFileNotification.text?.trim())
            }

            assert(!loader.isClosedForReceive)

            // add a file and wait until it is registered correctly
            Files.createFile(testFilePath)
            createJob.join()

            val modifyJob = launch {
                val loadedFileNotification = loader.receive()
                assertEquals(FileNotification.EventKind.Modified, loadedFileNotification.notification.eventKind)

                assertEquals(testFileText, loadedFileNotification.text?.trim())
            }

            // write some data to the file and wait until it is registered correctly
            testFilePath.toFile().printWriter().use { it.println(testFileText) }
            modifyJob.join()

            loader.close()
            assert(loader.isClosedForReceive)

            testTmpDir.deleteRecursively()
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

            val testTmpDir = createTempDir("testfileloaderconsistency")
            val testDirPath = testTmpDir.toPath()
            println("using temporary test dir $testDirPath")

            val testFilePath = testDirPath.resolve("testfileloaderconsistency.txt")
            Files.deleteIfExists(testFilePath)
            Files.createDirectories(testDirPath)
            val testFileTextLength = 100000

            val loader = FileLoader(testDirPath)

            assert(!loader.isClosedForReceive)

            val successfulFiles = AtomicInteger(0)
            val job = launch {
                var char = '0'
                while (char != 'z') {
                    val loadedFileNotification = loader.receive()
                    when (loadedFileNotification.notification.eventKind) {
                        FileNotification.EventKind.Created, FileNotification.EventKind.Modified -> {
                            println(
                                "File Loaded: " +
                                        "text: ${loadedFileNotification.text?.first()} " +
                                        "${loadedFileNotification.notification} " +
                                        "instant: ${loadedFileNotification.textTimeStamp} " +
                                        "text length: ${loadedFileNotification.text?.length} "
                            )
                            val trimText = loadedFileNotification.text?.trim()
                            assertEquals(testFileTextLength, trimText?.length)

                            // ensure that the text is read correctly
                            char = trimText!!.first()
                            assert(trimText.all { it == char })

                            successfulFiles.getAndAdd(1)
                        }
                        else -> assert(false)
                    }
                }
            }

            assert(!loader.isClosedForReceive)

            // Write to the file in quick succession with varying delays between the writes so that some writes are
            // registered correctly and some are discarded.
            for (i in '0'..'z') {

                // each file is filled with a string consisting of a single digit
                val c: Byte = i.toByte()
                val ar = ByteArray(testFileTextLength) { c }
                Files.write(testFilePath, ar)

                // introduce varying delays to have some files successfully read and some being discarded
                delay(Random.nextLong() % 250)
            }

            // wait until the last file has been successfully loaded
            job.join()

            loader.close()
            assert(loader.isClosedForReceive)

            // some modifications are successfully read (because of delaying and throttling, this might only be one)
            assert(successfulFiles.get() > 0)

            // some modifications are reduced into a single one by the watcher. At most we get two per modification
            assert(successfulFiles.get() < 2 * ('z' - '0'))

            testTmpDir.deleteRecursively()
        }
    }
}
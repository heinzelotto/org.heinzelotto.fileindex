package org.heinzelotto.fileindex

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.TimeoutException

class FileIndexTest {

    /**
     * Test that both existing files and subsequent file modifications are correctly reflected in the index.
     */
    @Test
    fun `content of watched file is loaded correctly`() {
        runBlocking {

            val testTmpDir = createTempDir("testfileindex")
            val testDirPath = testTmpDir.toPath()
            println("using temporary test dir $testDirPath")

            val testFilePathA = testDirPath.resolve("testfileindexa.txt")
            val testFilePathB = testDirPath.resolve("testfileindxexb.txt")

            testDirPath.toFile().deleteRecursively()
            Files.createDirectories(testDirPath)

            val testFileTextA = "hello world"
            val testFileTextB = "world peace"

            // file A contains some text and file B does not exist yet
            Files.createFile(testFilePathA)
            testFilePathA.toFile().printWriter().use { it.println(testFileTextA) }

            val index = FileIndex(testDirPath)

            // currently one occurrence of "world"
            assertEquals(1, index.query("world").size)

            // write file B's content and wait until it is registered
            Files.createFile(testFilePathB)
            testFilePathB.toFile().printWriter().use { it.println(testFileTextB) }

            // wait until the index has been updated
            assertPredicatePolled(10000, 10) { index.query("world").size != 1 }

            // now two occurrences of "world"
            assertEquals(2, index.query("world").size)

            testTmpDir.deleteRecursively()
        }
    }
}

/**
 * Polls a predicate in steps of `interval` until it is fulfilled or `timeout` has passed.
 */
suspend fun assertPredicatePolled(timeout: Long, interval: Long, predicate: () -> Boolean) {

    var remainingTimeout = timeout
    while (remainingTimeout > 0) {
        if (predicate()) {
            println("Predicate fulfilled, took ${timeout - remainingTimeout}/$timeout ms")
            return
        }

        remainingTimeout -= interval
        delay(interval)
    }

    throw TimeoutException()
}
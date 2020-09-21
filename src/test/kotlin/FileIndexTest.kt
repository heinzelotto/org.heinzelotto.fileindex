package org.heinzelotto.fileindex

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class FileIndexTest {

    /**
     * Test that both existing files and subsequent file modifications are correctly reflected in the index.
     */
    @Test
    fun `content of watched file is loaded correctly`() {
        runBlocking {
            val cwd = File(System.getProperty("user.dir"))
            val testDirPath = cwd.toPath().resolve("testfileindex")
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

            // create empty file B and wait until it is registered
            Files.createFile(testFilePathB)
            delay(100)

            // still only one occurrence of "world"
            assertEquals(1, index.query("world").size)

            // update file B's content and wait until it is registered
            testFilePathB.toFile().printWriter().use { it.println(testFileTextB) }
            delay(100)

            // now two occurrences of "world"
            assertEquals(2, index.query("world").size)
        }
    }
}
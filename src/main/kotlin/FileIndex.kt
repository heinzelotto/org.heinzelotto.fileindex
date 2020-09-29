package org.heinzelotto.fileindex

import kotlinx.coroutines.*
import java.nio.charset.MalformedInputException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * This simple tokenizer returns a list of all occurrences of each word, and disregards whitespace.
 */
fun wordTokenizerIgnoringWhitespace(text: String): Map<String, List<IntRange>> =
    "[\\S]+".toRegex().findAll(text).groupBy(
        keySelector = { match -> match.value },
        valueTransform = { match -> match.range }
    )


/**
 * Watch a directory recursively and maintain an index of all files using the passed tokenizer.
 *
 * The index is automatically updated whenever a file is created, modified or deleted. The index allows for safe
 * concurrent query access.
 */
@ExperimentalCoroutinesApi
class FileIndex(
    /**
     * Path of the directory to watch.
     */
    private val rootPath: Path,
    /**
     * The tokenization algorithm. Defaults to a simple split-at-whitespace tokenizer.
     */
    private val lexer: ((String) -> Map<String, List<IntRange>>) = { wordTokenizerIgnoringWhitespace(it) }
) {
    private val coroutineScope: CoroutineScope = GlobalScope
    private var fileLoaderService: FileLoader? = null
    private val indexDb = IndexDb()
    private val deferredUntilInitialScanComplete = CompletableDeferred<Unit>()

    /**
     * Starts the initial scan and maintaining of the index.
     *
     * @note Must not be called more than once.
     */
    fun start() {
        fileLoaderService = FileLoader(rootPath)

        coroutineScope.launch(Dispatchers.IO) {

            // create initial index before watching
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                    if (attrs != null && attrs.isRegularFile) {

                        try {
                            val fileContents = Files.readString(file!!)

                            indexDb.createFileIndex(
                                file,
                                IndexDb.SingleFileIndex(
                                    tokenizeFile(
                                        file,
                                        fileContents,
                                        lexer
                                    ),
                                    Instant.now()
                                )
                            )
                        } catch (e: MalformedInputException) {
                            println("FileIndex: cannot decode file, it is not utf-8: ${file!!}")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    return super.visitFile(file, attrs)
                }
            })

            // mark initial scan as completed
            deferredUntilInitialScanComplete.complete(Unit)

            // start maintaining the index
            for (noti in fileLoaderService!!) {
                when (noti.notification.eventKind) {

                    FileNotification.EventKind.Created ->
                        indexDb.createFileIndex(
                            noti.notification.filePath,
                            IndexDb.SingleFileIndex(
                                tokenizeFile(
                                    noti.notification.filePath,
                                    noti.text!!,
                                    lexer
                                ),
                                noti.textTimeStamp!!
                            )
                        )

                    FileNotification.EventKind.Modified ->
                        indexDb.modifyFileIndex(
                            noti.notification.filePath,
                            IndexDb.SingleFileIndex(
                                tokenizeFile(
                                    noti.notification.filePath,
                                    noti.text!!,
                                    lexer
                                ),
                                noti.textTimeStamp!!
                            )
                        )

                    FileNotification.EventKind.Deleted ->
                        indexDb.deleteFileIndex(noti.notification.filePath)
                }
            }
        }
    }

    /**
     * Suspends until the initial indexing scan has concluded.
     */
    suspend fun awaitInitialScan() = deferredUntilInitialScanComplete.await()

    /**
     * Check whether initial scan is complete.
     */
    fun initialScanCompleted(): Boolean = deferredUntilInitialScanComplete.isCompleted

    /**
     * Query all occurrences of a lexeme across all indexed files.
     */
    fun query(needle: String): List<FilePosition> =
        indexDb.query(needle)

    /**
     * Given a file, use a lexer to generate an index of lexeme occurrences (as FilePositions).
     */
    private fun tokenizeFile(
        filePath: Path,
        fileText: String,
        lexer: (String) -> Map<String, List<IntRange>>
    ): Map<String, List<FilePosition>> =
        lexer(fileText).mapValues { entry -> entry.value.map { intRange -> FilePosition(filePath, intRange) } }


}
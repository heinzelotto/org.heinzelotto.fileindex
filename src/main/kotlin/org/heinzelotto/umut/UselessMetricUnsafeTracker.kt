package org.heinzelotto.umut

import org.heinzelotto.fileindex.FileIndex
import java.io.File
import kotlin.system.exitProcess

fun usage() {
    println("Rust safety checker. Usage: \"umut directory_to_watch\"")
}

fun main(args: Array<String>) {
    if (args.size != 1) {
        usage()
        exitProcess(1)
    }

    val rootDir = File(args[0])
    val index = FileIndex(rootDir.toPath())

    // since the index accepts queries already during the initial scan, this query is likely to return zero results
    val initialUnsafes = index.query("unsafe")
    var unsafeCount = initialUnsafes.size
    println("Your code contains $unsafeCount usages of the word \"unsafe\":")
    for (iu in initialUnsafes) {
        println(iu)
    }
    println()
    if (unsafeCount > 0)
        println("If this makes you feel uncomfortable, please change it")
    else
        println("Try to keep it that way ;)")

    while(true) {
        val newUnsafeCount = index.query("unsafe").size
        if (newUnsafeCount > unsafeCount) {
            println("ATTENTION: your Rust program just got unsafer!!! count: $newUnsafeCount")
        } else if (newUnsafeCount < unsafeCount) {
            if (newUnsafeCount == 0) {
                println("Your program is totally safe, so that is nice.")
            } else {
                println("CONGRATULATIONS: Your program just got safer! Yay! count: $newUnsafeCount")
            }
        }
        unsafeCount = newUnsafeCount
        Thread.sleep(500)
    }
}

// unsafe fn bla() {
//     let pointer = bla as *const ();
//     let function = unsafe {
//         std::mem::transmute::<*const (), fn() -> i32>(bla) // this is unsafe !
//     };
// }
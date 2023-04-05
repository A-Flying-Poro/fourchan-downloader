package com.aflyingporo.fourchandownloader

import java.io.File
import kotlin.streams.asSequence

object FileUtil {
    // https://stackoverflow.com/a/26420820
    private val illegalCharacters = intArrayOf(34, 60, 62, 124, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 58, 42, 63, 92, 47).apply { sort() }

    operator fun File.div(fileName: String) = File(this, fileName)

    fun String.cleanFileName(): String = this.codePoints()
        .asSequence()
        .map { if (illegalCharacters.binarySearch(it) < 0) it else '_'.code }
        .let {
            buildString {
                it.forEach { appendCodePoint(it) }
            }
        }
}

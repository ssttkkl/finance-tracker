package com.finance.tracker

const val MAX_IMPORT_FILES = 20
const val MAX_IMPORT_FILE_BYTES = 100L * 1024L * 1024L

enum class ImportFileValidation {
    Valid,
    UnsupportedType,
    TooLarge,
    SizeUnavailable,
    TooManyFiles,
}

data class ImportFileIdentity(val name: String, val digest: String, val size: Long)

data class ImportFileSelection(
    val files: List<ImportFileIdentity>,
    val rejections: List<ImportFileValidation>,
)

fun validateImportFile(name: String, size: Long): ImportFileValidation {
    if (size < 0) return ImportFileValidation.SizeUnavailable
    if (size > MAX_IMPORT_FILE_BYTES) return ImportFileValidation.TooLarge
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension !in setOf("csv", "xls", "xlsx", "pdf")) return ImportFileValidation.UnsupportedType
    return ImportFileValidation.Valid
}

fun addImportFiles(existing: List<ImportFileIdentity>, selected: List<ImportFileIdentity>): ImportFileSelection {
    val files = existing.toMutableList()
    val digests = files.mapTo(mutableSetOf()) { it.digest }
    val rejections = mutableListOf<ImportFileValidation>()
    selected.forEach { item ->
        when {
            validateImportFile(item.name, item.size) != ImportFileValidation.Valid ->
                rejections += validateImportFile(item.name, item.size)
            item.digest in digests -> Unit
            files.size >= MAX_IMPORT_FILES -> {
                if (ImportFileValidation.TooManyFiles !in rejections) rejections += ImportFileValidation.TooManyFiles
            }
            else -> {
                files += item
                digests += item.digest
            }
        }
    }
    return ImportFileSelection(files, rejections)
}

/** SHA-1 is used only to match the unchanged statement-import API's content identity contract. */
fun sha1Hex(input: ByteArray): String {
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()
    val words = IntArray(80)

    fun process(block: ByteArray, offset: Int) {
        for (index in 0 until 16) {
            val start = offset + index * 4
            words[index] = ((block[start].toInt() and 0xff) shl 24) or
                ((block[start + 1].toInt() and 0xff) shl 16) or
                ((block[start + 2].toInt() and 0xff) shl 8) or
                (block[start + 3].toInt() and 0xff)
        }
        for (index in 16 until 80) {
            words[index] = rotateLeft(words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16], 1)
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (index in 0 until 80) {
            val function: Int
            val constant: Int
            when (index) {
                in 0..19 -> { function = (b and c) or (b.inv() and d); constant = 0x5A827999 }
                in 20..39 -> { function = b xor c xor d; constant = 0x6ED9EBA1 }
                in 40..59 -> { function = (b and c) or (b and d) or (c and d); constant = 0x8F1BBCDC.toInt() }
                else -> { function = b xor c xor d; constant = 0xCA62C1D6.toInt() }
            }
            val next = rotateLeft(a, 5) + function + e + constant + words[index]
            e = d
            d = c
            c = rotateLeft(b, 30)
            b = a
            a = next
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }

    val completeBytes = input.size - input.size % 64
    var offset = 0
    while (offset < completeBytes) {
        process(input, offset)
        offset += 64
    }
    val remainder = input.size - completeBytes
    val tailLength = if (remainder < 56) 64 else 128
    val tail = ByteArray(tailLength)
    input.copyInto(tail, destinationOffset = 0, startIndex = completeBytes, endIndex = input.size)
    tail[remainder] = 0x80.toByte()
    val bitLength = input.size.toLong() * 8L
    for (index in 0 until 8) {
        tail[tailLength - 1 - index] = (bitLength ushr (index * 8)).toByte()
    }
    process(tail, 0)
    if (tailLength == 128) process(tail, 64)

    return listOf(h0, h1, h2, h3, h4).joinToString("") { value ->
        value.toUInt().toString(16).padStart(8, '0')
    }
}

private fun rotateLeft(value: Int, distance: Int): Int = (value shl distance) or (value ushr (32 - distance))

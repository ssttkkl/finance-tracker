package com.finance.tracker.domain

/** 实现必须在分配前约束实际读取量，不能先调用无界 read()。 */
interface BoundedFileSource : FileSource {
    suspend fun readBounded(maxBytes: Int): ByteArray
}

class FileReadLimitExceeded : Exception("file_read_limit_exceeded")

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

actual fun createPlatformTokenStore(): TokenStore = IOSKeychainTokenStore()

private class IOSKeychainTokenStore : TokenStore {
    override suspend fun get(): String? = memScoped {
        val query = createQuery(returnData = true)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecItemNotFound) return null
        if (status != errSecSuccess) throw ApiFailure("token_store_unavailable", 0)
        val nativeValue: CFTypeRef = result.value ?: throw ApiFailure("token_store_unavailable", 0)
        val data: CFDataRef = nativeValue.reinterpret()
        val length = CFDataGetLength(data).toInt()
        val source = CFDataGetBytePtr(data) ?: throw ApiFailure("token_store_unavailable", 0)
        val bytes = ByteArray(length)
        for (index in bytes.indices) bytes[index] = source[index].toByte()
        CFRelease(data)
        bytes.decodeToString(throwOnInvalidSequence = true)
    }

    override suspend fun set(value: String) {
        if (value.isEmpty()) throw ApiFailure("token_store_unavailable", 0)
        val data = value.encodeToCFData()
        val query = createQuery(value = data)
        val deleteQuery = createQuery()
        val deleteStatus = SecItemDelete(deleteQuery)
        CFRelease(deleteQuery)
        if (deleteStatus != errSecSuccess && deleteStatus != errSecItemNotFound) {
            throw ApiFailure("token_store_unavailable", 0)
        }
        val status = SecItemAdd(query, null)
        CFRelease(query)
        CFRelease(data)
        if (status != errSecSuccess) throw ApiFailure("token_store_unavailable", 0)
    }

    override suspend fun clear() {
        val query = createQuery()
        val status = SecItemDelete(query)
        CFRelease(query)
        if (status != errSecSuccess && status != errSecItemNotFound) throw ApiFailure("token_store_unavailable", 0)
    }
}

private val keychainService = "com.finance.tracker".toCFString()
private val keychainAccount = SESSION_TOKEN_STORAGE_KEY.toCFString()

private fun createQuery(returnData: Boolean = false, value: CFTypeRef? = null) =
    CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        0,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )!!.also { dictionary ->
        CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(dictionary, kSecAttrService, keychainService)
        CFDictionarySetValue(dictionary, kSecAttrAccount, keychainAccount)
        if (returnData) {
            CFDictionarySetValue(dictionary, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
        }
        if (value != null) CFDictionarySetValue(dictionary, kSecValueData, value)
    }

private fun String.toCFString() = memScoped {
    val bytes = encodeToByteArray()
    bytes.usePinned { pinned ->
        CFStringCreateWithBytes(
            kCFAllocatorDefault,
            pinned.addressOf(0).reinterpret<UByteVar>(),
            bytes.size.toLong(),
            kCFStringEncodingUTF8,
            false,
        )!!
    }
}

private fun String.encodeToCFData() = encodeToByteArray().let { bytes ->
    bytes.usePinned { pinned -> CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toLong())!! }
}

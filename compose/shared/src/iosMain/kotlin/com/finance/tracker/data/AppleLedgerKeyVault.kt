@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker.data

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataAppendBytes
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataGetMutableBytePtr
import platform.CoreFoundation.CFDataGetTypeID
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** The cache factory supplies the digest of the complete scope, and owns file checks. */
internal class AppleLedgerKeyVault(private val keyId: String) {
    init {
        require(keyId.length == 64 && keyId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "ledger_keychain_scope_invalid"
        }
    }

    fun read(): ByteArray? = withQuery { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            result.value = null
            val status = SecItemCopyMatching(query, result.ptr)
            try {
                if (status == errSecItemNotFound) return@withQuery null
                requireSuccess(status)
                val value = checkNotNull(result.value) { "ledger_keychain_missing_data" }
                check(CFGetTypeID(value) == CFDataGetTypeID()) { "ledger_keychain_invalid_data" }
                val data: CFDataRef = value.reinterpret()
                check(CFDataGetLength(data) == 32L) { "ledger_keychain_invalid_key" }
                val source = checkNotNull(CFDataGetBytePtr(data)) { "ledger_keychain_missing_data" }
                ByteArray(32) { source[it].toByte() }
            } finally {
                result.value?.let { CFRelease(it) }
            }
        }
    }

    /** Only called for an explicitly new database, never as recovery for a missing key. */
    fun create(): ByteArray {
        val key = ByteArray(32)
        var transferred = false
        try {
            key.usePinned {
                requireSuccess(SecRandomCopyBytes(kSecRandomDefault, key.size.toULong(), it.addressOf(0)))
            }
            val status = withQuery { query ->
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                val data = checkNotNull(CFDataCreateMutable(kCFAllocatorDefault, key.size.toLong())) {
                    "ledger_keychain_allocation_failed"
                }
                try {
                    key.usePinned {
                        CFDataAppendBytes(data, it.addressOf(0).reinterpret<UByteVar>(), key.size.toLong())
                    }
                    CFDictionarySetValue(query, kSecValueData, data)
                    SecItemAdd(query, null)
                } finally {
                    CFDataGetMutableBytePtr(data)?.let { bytes ->
                        for (index in key.indices) bytes[index] = 0u
                    }
                    CFRelease(data)
                }
            }
            if (status == errSecDuplicateItem) {
                return checkNotNull(read()) { "ledger_keychain_item_disappeared" }
            }
            requireSuccess(status)
            transferred = true
            return key
        } finally {
            if (!transferred) key.fill(0)
        }
    }

    fun erase() = withQuery { query ->
        val status = SecItemDelete(query)
        if (status != errSecItemNotFound) requireSuccess(status)
    }

    private fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T {
        val query = checkNotNull(CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)) {
            "ledger_keychain_allocation_failed"
        }
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
            for ((attribute, text) in listOf(kSecAttrService to SERVICE, kSecAttrAccount to keyId)) {
                val encoded = text.encodeToByteArray()
                val value = checkNotNull(encoded.usePinned {
                    CFStringCreateWithBytes(kCFAllocatorDefault, it.addressOf(0).reinterpret(),
                        encoded.size.toLong(), kCFStringEncodingUTF8, false)
                }) { "ledger_keychain_allocation_failed" }
                try { CFDictionarySetValue(query, attribute, value) }
                finally { CFRelease(value) }
            }
            return block(query)
        } finally { CFRelease(query) }
    }

    private fun requireSuccess(status: Int) {
        check(status == errSecSuccess) { "ledger_keychain_unavailable:$status" }
    }

    private companion object {
        const val SERVICE = "com.finance.tracker.ledger-cache"
    }
}

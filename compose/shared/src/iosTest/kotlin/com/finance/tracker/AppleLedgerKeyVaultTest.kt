@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker

import com.finance.tracker.data.AppleLedgerKeyVault
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSUUID
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnAttributes
import platform.Security.kSecValueData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Requires the explicit, entitled Keychain integration test run. */
class AppleLedgerKeyVaultTest {
    @Test
    fun missingReadDoesNotCreateAndRepeatedCreatePreservesTheOriginalKey() = withVault { id, vault ->
        assertNull(vault.read())
        val key = vault.create()
        try {
            assertEquals(32, key.size)
            val persisted = checkNotNull(AppleLedgerKeyVault(id).read())
            try { assertContentEquals(key, persisted) }
            finally { persisted.fill(0) }
            val duplicate = vault.create()
            try { assertContentEquals(key, duplicate) }
            finally { duplicate.fill(0) }
            val readCopy = checkNotNull(vault.read())
            readCopy.fill(0)
            val fresh = checkNotNull(vault.read())
            try { assertContentEquals(key, fresh) }
            finally { fresh.fill(0) }
            withRecord(id) { query ->
                CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    result.value = null
                    assertEquals(errSecSuccess, SecItemCopyMatching(query, result.ptr))
                    val attributes = checkNotNull(result.value)
                    try {
                        val accessibility = checkNotNull(CFDictionaryGetValue(attributes.reinterpret(), kSecAttrAccessible))
                        assertEquals(0L,
                            CFStringCompare(accessibility.reinterpret(), kSecAttrAccessibleWhenUnlockedThisDeviceOnly, 0u))
                    } finally { CFRelease(attributes) }
                }
            }
        } finally { key.fill(0) }
        vault.erase()
        vault.erase()
        assertNull(AppleLedgerKeyVault(id).read())
    }

    @Test
    fun distinctKeyIdsAreIsolatedAndDeletionOnlyAffectsOneItem() = withVault { _, first ->
        withVault { _, second ->
            val a = first.create()
            val b = second.create()
            try {
                assertFalse(a.contentEquals(b))
                first.erase()
                assertNull(first.read())
                val remaining = checkNotNull(second.read())
                try { assertContentEquals(b, remaining) }
                finally { remaining.fill(0) }
            } finally { a.fill(0); b.fill(0) }
        }
    }

    @Test
    fun malformedExistingMaterialIsRejectedByReadAndDuplicateCreate() = withVault { id, vault ->
        vault.create().fill(0)
        withRecord(id) { query ->
            val update = CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
                kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
            val malformed = byteArrayOf(1, 2, 3)
            val data = malformed.usePinned {
                CFDataCreate(kCFAllocatorDefault, it.addressOf(0).reinterpret(), malformed.size.toLong())!!
            }
            try {
                CFDictionarySetValue(update, kSecValueData, data)
                assertEquals(errSecSuccess, SecItemUpdate(query, update))
            } finally { CFRelease(data); CFRelease(update); malformed.fill(0) }
        }
        assertFails { vault.read() }
        assertFails { vault.create() }
        assertFails { AppleLedgerKeyVault(id).read() }
    }

    private fun withVault(block: (String, AppleLedgerKeyVault) -> Unit) {
        val id = NSUUID().UUIDString.replace("-", "").lowercase().repeat(2)
        val vault = AppleLedgerKeyVault(id)
        try { block(id, vault) }
        finally { vault.erase() }
    }

    private fun withRecord(id: String, block: (platform.CoreFoundation.CFMutableDictionaryRef) -> Unit) {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            for ((attribute, value) in listOf(kSecAttrService to "com.finance.tracker.ledger-cache", kSecAttrAccount to id)) {
                val encoded = value.encodeToByteArray()
                val text = encoded.usePinned {
                    CFStringCreateWithBytes(kCFAllocatorDefault, it.addressOf(0).reinterpret(), encoded.size.toLong(), kCFStringEncodingUTF8, false)!!
                }
                try { CFDictionarySetValue(query, attribute, text) }
                finally { CFRelease(text) }
            }
            block(query)
        } finally { CFRelease(query) }
    }
}

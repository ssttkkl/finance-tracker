package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashLegacyProviderReplacementTest {
    private val directory by lazy { Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEGACY_PROVIDER_DIR"))) }
    private val manifest by lazy { Json.parseToJsonElement(Files.readString(directory.resolve("manifest.json"))).jsonObject }
    private fun cases() = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/legacy-provider-replacements.json"))
        .bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject }.also {
            assertEquals(2, it.size)
            assertEquals(2, it.map { case -> case.getValue("source") }.toSet().size)
            assertEquals(4, it.sumOf { case -> case.getValue("original_assertions").jsonArray.size })
        }

    private fun read(source: String, variant: String): SourceOccurrence {
        val info = manifest.getValue(source).jsonObject.getValue(variant).jsonObject
        val bytes = Files.readAllBytes(directory.resolve(info.getValue("file").jsonPrimitive.content))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(info.getValue("sha256").jsonPrimitive.content, digest)
        val payload = info.getValue("payload").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val owner = info["owner"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        val document = CashStatementDocuments.readDocument(bytes, source)
        assertEquals(1, document.observedCount)
        assertEquals(owner, document.sourceAccountIdentifier)
        assertEquals(payload, document.rows.single().payload)
        assertEquals(info.getValue("position").jsonPrimitive.int, document.rows.single().position)
        val parsed = CashStatementRowNormalization.normalizeDocument(document)
        assertEquals(1, parsed.observedCount)
        val row = parsed.rows.single()
        assertEquals("candidate", row.disposition)
        assertNull(row.errorCode)
        assertEquals(emptyList(), row.issueCodes)
        val fields = assertNotNull(row.fields)
        assertEquals("CNY", fields["currency"])
        assertEquals(payload, fields["source_payload"])
        val amount = if (variant == "amount") if (source == "alipay") "-101" else "-31"
            else if (source == "alipay") "-100" else "-30"
        assertEquals(0, BigDecimal(amount).compareTo(BigDecimal(assertIs<String>(fields["amount"]))))
        assertEquals(if (variant == "note") "补充说明" else if (source == "alipay") "买书" else "奶茶", fields["note"])
        val expectedProvider = if (source == "alipay") "2026010122001111111111111111" else "4200000000000000000000000001"
        assertEquals(expectedProvider, row.providerId)
        val merchant = if (source == "alipay") "MEO_1" else "MCH_1"
        assertEquals(merchant, payload[if (source == "alipay") "商家订单号" else "商户单号"])
        assertNotEquals(merchant, row.providerId)
        val file = ParsedImportFile("$source-$variant", digest, "parsed", parsed)
        val occurrence = CashSourceRows.sourceRows(file).values.single()
        assertEquals(expectedProvider, occurrence.providerId)
        assertEquals(fields, occurrence.fields)
        assertEquals(source, occurrence.sourceType)
        val expectedScope = if (source == "wechat") CashSourceScope.WECHAT_DEFAULT_SCOPE
        else CashSourceScope.ALIPAY_TRANSACTION_SCOPE
        assertEquals(expectedScope, occurrence.sourceAccountKey)
        val specs = occurrence.identityBindingSpecs
        if (expectedScope == null) {
            assertNull(occurrence.providerBindingKey)
            assertEquals(listOf("file_local" to "file_occurrence"), specs.map { it.scopeKind to it.identityKind })
        } else {
            assertNotNull(occurrence.providerBindingKey)
            assertEquals(listOf("account" to "provider_id", "account" to "file_occurrence", "file_local" to "file_occurrence"),
                specs.map { it.scopeKind to it.identityKind })
            assertEquals(expectedScope, specs.first().scopeKey)
        }
        return occurrence
    }

    private fun existing(first: SourceOccurrence) = SourceRecord("existing-1", first.sourceType, first.sourceAccountKey,
        first.fields + ("state" to "posted"), coverages = listOf(first.coverage), bindingKeys = first.bindingKeys.toSet(),
        providerId = first.providerId, providerBindingKey = first.providerBindingKey,
        fileOccurrences = mapOf(first.fileScopeKey to setOf(first.occurrenceKey)))

    private fun verify(row: SourceOccurrence, records: List<SourceRecord>, action: String, id: String? = null,
        reason: String = "", bind: Boolean = true, candidates: List<String> = emptyList(), forced: Boolean = false) {
        val result = CashSourceResolver.resolveSourceRows(listOf(row), records,
            if (forced) mapOf(row.occurrenceKey to setOf("existing-1")) else emptyMap()).single()
        assertEquals(SourceResolution(row.occurrenceKey, action, id, candidates, reason, bind), result)
    }

    private fun original(source: String): SourceOccurrence {
        val item = cases().single { it.getValue("source_type").jsonPrimitive.content == source }
        val header = item.getValue("header").jsonArray.map { it.jsonPrimitive.content }
        val values = item.getValue("rows").jsonArray.single().jsonArray.map { it.jsonPrimitive.content }
        assertEquals(10, header.size)
        val expected = header.zip(values).toMap()
        val row = read(source, "original")
        assertEquals(expected, row.fields["source_payload"])
        verify(row, emptyList(), "new")
        val repeated = read(source, "original")
        assertEquals(row.occurrenceKey, repeated.occurrenceKey)
        verify(repeated, listOf(existing(row)), "duplicate", "existing-1")
        return row
    }

    @Test fun alipayOrderNumberIdentityIsGlobalAndKeepsFixedFieldProtection() {
        val first = original("alipay")
        assertEquals(CashSourceScope.ALIPAY_TRANSACTION_SCOPE, first.sourceAccountKey)
        val unknown = read("alipay", "other_file")
        assertNotEquals(first.fileDigest, unknown.fileDigest)
        assertNotEquals(first.occurrenceKey, unknown.occurrenceKey)
        assertEquals(first.providerId, unknown.providerId)
        assertEquals(first.providerBindingKey, unknown.providerBindingKey)
        verify(unknown, listOf(existing(first)), "duplicate", "existing-1")
        val otherOwner = read("alipay", "owner_b")
        assertEquals(first.sourceAccountKey, otherOwner.sourceAccountKey)
        assertEquals(first.providerBindingKey, otherOwner.providerBindingKey)
        verify(otherOwner, emptyList(), "new")
        verify(otherOwner, listOf(existing(first)), "duplicate", "existing-1")
        val note = read("alipay", "note")
        val amount = read("alipay", "amount")
        assertEquals(first.providerBindingKey, note.providerBindingKey)
        assertEquals(first.providerBindingKey, amount.providerBindingKey)
        verify(note, listOf(existing(first)), "match", "existing-1")
        verify(amount, listOf(existing(first)), "conflict", "existing-1", "fixed_field_conflict", bind = false)
        val sameFile = read("alipay", "owner_a_other")
        assertEquals(listOf(SourceResolution(first.occurrenceKey, "new", bind = true),
            SourceResolution(sameFile.occurrenceKey, "batch_duplicate", duplicateOf = first.occurrenceKey)),
            CashSourceResolver.resolveSourceRows(listOf(first, sameFile), emptyList()))
        assertEquals(first.fields + ("state" to "posted"), existing(first).fields)
        assertEquals(first.bindingKeys.toSet(), existing(first).bindingKeys)
    }

    @Test fun wechatTransactionNumberKeepsOneGlobalScopeAcrossFiles() {
        val first = original("wechat")
        assertEquals(CashSourceScope.WECHAT_DEFAULT_SCOPE, first.sourceAccountKey)
        val other = read("wechat", "other_file")
        assertNotEquals(first.fileDigest, other.fileDigest)
        assertNotEquals(first.occurrenceKey, other.occurrenceKey)
        assertEquals(first.fields, other.fields)
        assertNotNull(first.providerBindingKey)
        assertEquals(first.providerBindingKey, other.providerBindingKey)
        verify(other, listOf(existing(first)), "duplicate", "existing-1")
        val note = read("wechat", "note")
        val amount = read("wechat", "amount")
        assertEquals(first.providerBindingKey, note.providerBindingKey)
        assertEquals(first.providerBindingKey, amount.providerBindingKey)
        verify(note, listOf(existing(first)), "match", "existing-1")
        verify(amount, listOf(existing(first)), "conflict", "existing-1", "fixed_field_conflict", bind = false)
        assertEquals(listOf(SourceResolution(first.occurrenceKey, "new", bind = true),
            SourceResolution(other.occurrenceKey, "batch_duplicate", duplicateOf = first.occurrenceKey)),
            CashSourceResolver.resolveSourceRows(listOf(first, other), emptyList()))
    }
}

package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.domain.CashLedgerDateBounds
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.business.domain.normalizeLocalCashLedgerQueryInput
import java.util.Locale
import kotlin.test.*
import kotlinx.serialization.json.*

class LocalCashLedgerInputParityTest {
    private data class Outcome(val filters: Map<String, String?>? = null, val start: Long? = null, val end: Long? = null, val failure: String? = null)
    private fun reference(values: Map<String, String>): Outcome = try {
        val input = CashProjectionListInputs.parse(values)
        Outcome(input.filters, input.start?.epochSecond, input.end?.epochSecond)
    } catch (failure: CashProjectionListFailure) { Outcome(failure = failure.code) }
        catch (_: CashProjectionListBooleanFailure) { Outcome(failure = "bool_parsing") }
        catch (_: NumberFormatException) { Outcome(failure = "internal") }
    private fun bounds(from: String?, to: String?, zone: String?): CashLedgerDateBounds {
        val values = buildMap { from?.let { put("date_from", it) }; to?.let { put("date_to", it) }; zone?.let { put("timezone", it) } }
        val input = try { CashProjectionListInputs.parse(values) }
            catch (failure: CashProjectionListFailure) { throw CashProjectionDomainFailure(failure.code) }
        return CashLedgerDateBounds(input.filters["date_from"], input.filters["date_to"], input.filters.getValue("timezone")!!,
            input.start?.let { kotlin.time.Instant.fromEpochSeconds(it.epochSecond, it.nano) },
            input.end?.let { kotlin.time.Instant.fromEpochSeconds(it.epochSecond, it.nano) })
    }
    private fun actual(values: Map<String, String>): Outcome = try {
        val dto = Json.decodeFromJsonElement<CashFiltersDto>(JsonObject(values.filterKeys { it != "timezone" }.mapValues { JsonPrimitive(it.value) }))
        val input = normalizeLocalCashLedgerQueryInput(dto, values["timezone"], ::bounds)
        Outcome(input.filters, input.start?.epochSeconds, input.end?.epochSeconds)
    } catch (failure: CashProjectionDomainFailure) { Outcome(failure = failure.code) }

    @Test fun normalizedFiltersMatchTheNativeParserWithTheApprovedAmountBoundary() {
        val fields = linkedMapOf(
            "account_id" to listOf("", "0", "-1", "+001", " 1 ", "٠_０9", "1__2", "4_", "_1", "1.0", "1e2", "\u001c1", "1\u001f", "1".repeat(4300), "1".repeat(4301)),
            "amount_min" to listOf("", " ", "0", "-0", "-0.000", "-0e40", "+0010_0.2500e+1", "١٢.３４", "NaN", "Infinity", "-Infinity", "sNaN123", "1e2147483648", "1e-2147483648") + (-50..50).map { "12.3400e$it" },
            "counterparty" to listOf("", "  Cafe  ", "literal%_\\", "\u001cA\u0085", "ßΣ𐐀"),
            "category_id" to listOf("", " ", "root", " root ", "x".repeat(64), "x".repeat(65), "𐐀".repeat(64)),
            "uncategorized" to listOf("", "0", "1", "true", "True", "FALSE", "yes", "No", "Y", "n", "on", "OFF", "t", "f", "yes ", "unknown"),
            "hide_zero_amount" to listOf("", "true", "TRUE", "false", "False", "1", "yes", " true"),
            "currency" to listOf("", "cny", " CNY", "CNY ", "CN1", "ĆY", "𐐨ab", "ßab", "𑼂ab"),
            "economic_type" to listOf("", "income", "expense", "internal_transfer", "bank_security_transfer", "unknown"),
            "business_label" to listOf("", " ", " bank_security_transfer ", "other", "x".repeat(32), "𐐀".repeat(33)),
            "composition" to listOf("", "single", "payment_mirror", "refund_offset", "combined", "transfer_pair"),
            "state" to listOf("", "all", "posted", "draft", "unknown"),
            "confirmation_status" to listOf("", "confirmed", "pending", "unknown"),
            "date_from" to listOf("", "2026-03-08", "20260308", "2026-W10-7", "2026W107", "0001-01-01", "9999-12-31", "2026-02-30", "2026-01-01T00:00:00Z"),
            "date_to" to listOf("", "2026-03-08", "9999-12-31", "2026-W10"),
            "timezone" to listOf("", " UTC ", "America/New_York", "Pacific/Apia", "EST", "MST", "HST", "unknown", "+01:00"))
        val cases = mutableListOf(emptyMap<String, String>())
        for ((name, values) in fields) for (value in values) cases += mapOf(name to value)
        for (economic in fields.getValue("economic_type")) for (label in fields.getValue("business_label"))
            cases += mapOf("economic_type" to economic, "business_label" to label)
        for (category in listOf("root", " ", "")) for (raw in fields.getValue("uncategorized"))
            cases += mapOf("category_id" to category, "uncategorized" to raw)
        for (low in listOf("-10.01", "-0", "0", "1.000", "1e40")) for (high in listOf("-10", "0", "1", "1e40"))
            cases += mapOf("amount_min" to low, "amount_max" to high)
        cases += mapOf("date_from" to "2026-03-09", "date_to" to "2026-03-08")
        cases += mapOf("date_from" to "2026-03-08", "date_to" to "2026-03-08", "timezone" to "America/New_York")
        cases += mapOf("date_from" to "2011-12-30", "date_to" to "2011-12-30", "timezone" to "Pacific/Apia")
        cases += mapOf("date_to" to "9999-12-31", "timezone" to "unknown")
        for (values in cases) {
            // Retain the full original corpus. Only the newly approved input boundary differs.
            val native = reference(values)
            val exceedsApprovedDigits = native.filters?.let { filters ->
                listOfNotNull(filters["amount_min"], filters["amount_max"]).any { text ->
                    val number = java.math.BigDecimal(text)
                    number.scale() > 18 || number.signum() != 0 && number.precision().toLong() - number.scale() > 20
                }
            } == true
            val exceedsNativeExponent = values.any { (field, value) ->
                field in setOf("amount_min", "amount_max") && value in setOf("1e2147483648", "1e-2147483648")
            }
            val expected = if (exceedsApprovedDigits || exceedsNativeExponent) Outcome(failure = "invalid_filter") else native
            assertEquals(expected, actual(values), values.toString())
        }
        assertTrue(cases.size > 300)
    }
    @Test fun everyNativeScalarKeepsLetterQualificationAndRootUppercaseThroughThePublicFactory() {
        val utc = CashLedgerDateBounds(null, null, "UTC", null, null)
        val calendar: (String?, String?, String?) -> CashLedgerDateBounds = { _, _, _ -> utc }
        for (point in 0..0x10ffff) {
            val text = String(Character.toChars(point)) + "ab"
            val expected = if (Character.isLetter(point)) text.uppercase(Locale.ROOT) else null
            val actual = try { normalizeLocalCashLedgerQueryInput(CashFiltersDto(currency = text), null, calendar).filters["currency"] }
                catch (failure: CashProjectionDomainFailure) { assertEquals("invalid_filter", failure.code); null }
            assertEquals(expected, actual, "U+${point.toString(16)}")
        }
    }
}

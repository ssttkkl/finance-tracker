package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class ValuationParityTest {
    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    private fun decimal(value: String?): JsonElement {
        if (value == null) return JsonNull
        val r = LedgerDecimal.parseRepresentationOrNull(value) ?: return JsonPrimitive(value)
        return buildJsonObject { put("negative", r.negative); put("coefficient", r.coefficient); put("exponent", r.exponent.toString()) }
    }
    private fun date(value: QuoteTime?): JsonElement {
        if (value == null) return JsonNull
        val base = value.local.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"))
        val fraction = if (value.local.nano == 0) "" else "." + (value.local.nano / 1000).toString().padStart(6, '0')
        val offset = value.offset?.let { if (it.totalSeconds == 0) "+00:00" else it.id }.orEmpty()
        return JsonPrimitive(base + fraction + offset)
    }
    private fun normal(value: Any?): JsonElement = when (value) {
        is AssetRef -> buildJsonObject { put("identity", value.identity); put("kind", value.kind.value); put("quantity", decimal(value.quantity)) }
        is QuoteResult -> buildJsonObject {
            put("identity", value.identity); put("kind", value.kind.value); put("status", value.status.value)
            put("unit_price", decimal(value.unitPrice)); put("quote_currency", value.quoteCurrency?.let(::JsonPrimitive) ?: JsonNull)
            put("observed_at", date(value.observedAt)); put("quote_session", value.quoteSession?.let(::JsonPrimitive) ?: JsonNull)
            put("market_value", decimal(value.marketValue)); put("quantity", decimal(value.quantity)); put("reason", value.reason)
            put("provider", value.provider?.let(::JsonPrimitive) ?: JsonNull)
        }
        is QuoteBatchResult -> buildJsonObject { put("results", JsonArray(value.results.map(::normal))) }
        is AssetKind -> JsonPrimitive(value.value)
        is QuoteStatus -> JsonPrimitive(value.value)
        is Pair<*, *> -> JsonArray(listOf(normal(value.first), normal(value.second)))
        is List<*> -> JsonArray(value.map(::normal))
        is JsonElement -> value
        else -> sourceJson(value)
    }
    private fun reference(raw: JsonElement?): AssetRef? {
        if (raw == null || raw == JsonNull) return null
        val ref = raw.jsonObject
        return AssetRef(ref.text("identity")!!, AssetKind.entries.first { it.value == ref.text("kind") }, ref.text("quantity"))
    }
    @Test fun allActualPythonValuationResultsErrorsProviderCallsAndBudgetsMatch() {
        val path = Path.of(requireNotNull(System.getenv("FT_KOTLIN_VALUATION_CONTRACT")))
        val cases = Json.parseToJsonElement(path.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(230, cases.size)
        for (value in cases) {
            val case = value.jsonObject; val args = case.getValue("args").jsonObject
            val calls = mutableListOf<JsonElement>(); val monotonicCalls = mutableListOf<Double>()
            val ticks = args["ticks"]?.jsonObject.orEmpty()
            fun tick(identity: String): ProviderTick? {
                val item = ticks[identity]
                if (item == null || item == JsonNull) return null
                if (item == JsonPrimitive("unsupported")) throw UnsupportedQuote(identity)
                if (item == JsonPrimitive("error")) throw IllegalStateException("SYNTHETIC_PROVIDER_SECRET")
                val data = item.jsonObject
                return ProviderTick(data.text("price")!!, if ("currency" in data) data.text("currency") else "USD",
                    data.text("observed_at")?.let(QuoteTime::parse), if ("provider" in data) data.text("provider") else "oracle",
                    if ("session" in data) data.text("session") else "unknown")
            }
            fun single(identity: String, kind: AssetKind): ProviderTick? {
                calls.add(JsonArray(listOf(JsonPrimitive("single"), JsonPrimitive(identity), JsonPrimitive(kind.value))))
                return tick(identity)
            }
            val batch = args["batch"] == JsonPrimitive(true)
            val historical = args["historical"] == JsonPrimitive(true)
            val provider: QuoteProvider = when {
                batch -> object : BatchQuoteProvider {
                    override fun rawQuote(identity: String, kind: AssetKind) = single(identity, kind)
                    override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> {
                        calls.add(JsonArray(listOf(JsonPrimitive("batch"), normal(refs), timeout?.let(::JsonPrimitive) ?: JsonNull)))
                        if (args["batch_error"] == JsonPrimitive(true)) throw IllegalStateException("SYNTHETIC_PROVIDER_SECRET")
                        val keys = args["outcome_keys"]?.jsonArray?.map { it.jsonPrimitive.content } ?: ticks.keys.toList()
                        return keys.associateWith { identity -> try { tick(identity) } catch (failure: Exception) { failure } }
                    }
                }
                historical -> object : HistoricalQuoteProvider {
                    override fun rawQuote(identity: String, kind: AssetKind) = single(identity, kind)
                    override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick? {
                        calls.add(JsonArray(listOf(JsonPrimitive("historical"), JsonPrimitive(identity), JsonPrimitive(kind.value), date(at))))
                        return tick(identity)
                    }
                }
                else -> object : QuoteProvider { override fun rawQuote(identity: String, kind: AssetKind) = single(identity, kind) }
            }
            val clock = QuoteTime.parse(args.text("now") ?: "2026-07-25T00:00:00+00:00")
            val times = args["monotonic"]?.jsonArray?.map { it.jsonPrimitive.double } ?: listOf(0.0)
            val service = ValuationService(provider, clock = { clock }, monotonic = {
                val time = times[minOf(monotonicCalls.size, times.lastIndex)]; monotonicCalls.add(time); time
            })
            val precision = args["precision"]?.jsonPrimitive?.int ?: 28
            val operation = case.getValue("operation").jsonPrimitive.content
            val result = try {
                val raw: Any? = when (operation) {
                    "make_ref" -> makeAssetRef(args.text("identity").orEmpty(), args.text("kind"), args.text("quantity"))
                    "kind" -> parseAssetKind(args.text("value"))
                    "display_currency" -> validateDisplayCurrency(args.text("value"))
                    "identity" -> validateValuationIdentity(args.text("value").orEmpty())
                    "finite" -> isFiniteValuationDecimal(args.text("value") ?: "None")
                    "product" -> decimal(computeMarketValue(args.text("price")!!, args.text("quantity")!!, precision))
                    "freshness" -> quoteFreshness(QuoteTime.parse(args.text("observed_at")!!), clock, AssetKind.entries.first { it.value == args.text("kind") })
                    "infer" -> inferAssetKind(args.text("identity")!!, args["cash"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                        args["configured"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty())
                    "mismatch" -> identityKindMismatch(args.text("identity")!!, AssetKind.entries.first { it.value == args.text("kind") })
                    "symbol" -> ledgerSecuritySymbol(args.text("identity")!!)
                    "prediction" -> parsePredictionMarketIdentity(args.text("identity")!!)
                    "quote" -> if ("ref" in args) service.quote(reference(args["ref"]), precision = precision)
                        else service.quote(identity = args.text("identity").orEmpty(), kind = args.text("kind"), quantity = args.text("quantity"), precision = precision)
                    "batch" -> service.quoteMany(args.getValue("refs").jsonArray.map(::reference), args["timeout"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.double, precision)
                    "historical" -> service.quoteAt(reference(args["ref"]), QuoteTime.parse(args.text("at")!!), precision)
                    else -> error(operation)
                }
                buildJsonObject { put("value", normal(raw)) }
            } catch (failure: ValuationError) {
                buildJsonObject { put("error_type", "ValuationError"); put("message", failure.message); put("code", failure.code); put("details", sourceJson(failure.details)) }
            } catch (failure: CategoryFailure) {
                buildJsonObject { put("error_type", "ValueError"); put("message", failure.code) }
            } catch (failure: IllegalArgumentException) {
                buildJsonObject { put("error_type", "ValueError"); put("message", failure.message) }
            }
            val name = case.getValue("name").jsonPrimitive.content
            assertEquals(case.getValue("expected"), result, name)
            assertEquals(case.getValue("calls"), JsonArray(calls), name + " actual provider calls")
            assertEquals(case.getValue("monotonic_calls").jsonArray.map { it.jsonPrimitive.double }, monotonicCalls, name + " budget clock calls")
        }
    }
}


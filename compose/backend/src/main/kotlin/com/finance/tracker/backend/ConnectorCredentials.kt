package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.resolver.Resolver
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.representer.Representer

internal class ConnectorCredentials(environment: Map<String, String> = System.getenv(), home: Path = Path.of(System.getProperty("user.home"))) {
    private val directory = environment["FT_CREDENTIALS_DIR"]?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: home.resolve(".ft")
    private val path = directory.resolve("credentials.yaml")
    private fun read(): Map<*, *> {
        require(Files.exists(path)) { "Credentials file not found: $path\nCreate it with your provider credentials, e.g.:\n  binance:\n    api_key: \"your-api-key\"\n    api_secret: \"your-api-secret\"\n  polymarket:\n    proxy_wallet: \"0x...\"" }
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        catch (_: java.io.IOException) { } catch (_: UnsupportedOperationException) { }
        Files.createDirectories(directory)
        val gitignore = directory.resolve(".gitignore")
        val lines = if (Files.exists(gitignore)) gitignore.readText().let { if (it.isEmpty()) emptyList() else it.split(Regex("\\r\\n|[\\n\\r\\u000b\\u000c\\u001c-\\u001e\\u0085\\u2028\\u2029]")).let { parts -> if (parts.last().isEmpty()) parts.dropLast(1) else parts } } else emptyList()
        if (lines.none { valuationStrip(it) == "credentials.yaml" }) gitignore.writeText((lines + "credentials.yaml").joinToString("\n", postfix = "\n"))
        val text = try { path.readText() } catch (_: java.io.IOException) { throw IllegalArgumentException("Cannot read credentials file $path") }
        require(valuationStrip(text).isNotEmpty()) { "Credentials file is empty: $path\nAdd provider sections with required fields." }
        val options = LoaderOptions().apply { isAllowDuplicateKeys = true; isWarnOnDuplicateKeys = false; tagInspector = org.yaml.snakeyaml.inspector.TagInspector { true } }
        val data = try { Yaml(CredentialConstructor(options), Representer(DumperOptions()), DumperOptions(), options, CredentialResolver()).load<Any?>(text) }
            catch (failure: CredentialYamlFailure) { throw IllegalArgumentException("Invalid YAML in credentials file $path: ${failure.category}") }
            catch (failure: YAMLException) {
                val category = if (failure is org.yaml.snakeyaml.constructor.ConstructorException) "ConstructorError" else failure.javaClass.simpleName.replace("Exception", "Error")
                throw IllegalArgumentException("Invalid YAML in credentials file $path: $category")
            }
            catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Invalid YAML in credentials file $path: ValueError") }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid YAML in credentials file $path: ValueError") }
        require(data is Map<*, *>) { "Credentials file $path must contain a YAML mapping (provider → config)" }
        return data
    }
    fun exchange(provider: String): Map<String, String> {
        val section = read()[provider]
        require(truthy(section)) { "Missing '$provider' section in credentials file.\nAdd the following to $path:\n  $provider:\n    api_key: \"your-api-key\"\n    api_secret: \"your-api-secret\"" }
        require(section is Map<*, *>) { "Credentials '$provider' must be a mapping" }
        val fields = listOf("api_key", "api_secret"); val missing = fields.filter { !truthy(section[it]) }
        require(missing.isEmpty()) { "Credentials '$provider' is missing required field(s): ${missing.joinToString(", ")}\nRequired fields: api_key, api_secret" }
        return buildMap { for (field in fields) put(field, string(section[field])); if (section.containsKey("password")) put("password", string(section["password"])) }
    }
    fun polymarket(): Map<String, String> {
        val section = read()["polymarket"]
        require(section is Map<*, *> && section.isNotEmpty()) { "Missing 'polymarket' section in credentials file.\nAdd the following to $path:\n  polymarket:\n    proxy_wallet: \"0x...\"  # or wallet: \"0x...\"" }
        require(truthy(section["wallet"]) || truthy(section["proxy_wallet"])) { "Credentials 'polymarket' requires 'wallet' or 'proxy_wallet'.\nAdd to $path:\n  polymarket:\n    proxy_wallet: \"0x...\"" }
        return buildMap {
            for (field in listOf("wallet", "proxy_wallet")) if (truthy(section[field])) {
                val wallet = valuationStrip(string(section[field]))
                require(Regex("0x[a-fA-F0-9]{40}").matches(wallet)) { "Polymarket '$field' must be a valid Ethereum address (0x + 40 hex chars)" }
                put(field, wallet.lowercase())
            }
        }
    }
    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is ByteArray -> value.isNotEmpty()
        else -> true
    }
    private fun string(value: Any?): String = string(value, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
    private fun string(value: Any?, active: MutableSet<Any>): String {
        val container = value is Collection<*> || value is Map<*, *>
        if (container && !active.add(value)) return if (value is Map<*, *>) "{...}" else "[...]"
        try { return when (value) {
        null -> "None"
        is String -> value
        is Boolean -> if (value) "True" else "False"
        is Double -> pythonFloatText(value)
        is Float -> pythonFloatText(value.toDouble())
        is ByteArray -> bytes(value)
        is CredentialTuple -> "(" + repr(value.first, active) + ", " + repr(value.second, active) + ")"
        is Set<*> -> if (value.isEmpty()) "set()" else value.joinToString(", ", "{", "}") { repr(it, active) }
        is List<*> -> value.joinToString(", ", "[", "]") { repr(it, active) }
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { repr(it.key, active) + ": " + repr(it.value, active) }
        else -> value.toString()
        } } finally { if (container) active.remove(value) }
    }
    private fun repr(value: Any?, active: MutableSet<Any>): String = when (value) { is String -> pythonStringRepr(value); is CredentialTimestamp -> value.representation; else -> string(value, active) }
    private fun bytes(value: ByteArray): String {
        val quote = if (39.toByte() in value && 34.toByte() !in value) '"' else '\''
        return buildString {
            append('b'); append(quote)
            for (byte in value) when (val code = byte.toInt() and 255) {
                quote.code, 92 -> { append('\\'); append(code.toChar()) }
                9 -> append("\\t")
                10 -> append("\\n")
                13 -> append("\\r")
                in 32..126 -> append(code.toChar())
                else -> append("\\x" + code.toString(16).padStart(2, '0'))
            }
            append(quote)
        }
    }
}

private class CredentialResolver : Resolver() {
    override fun addImplicitResolvers() {
        addImplicitResolver(Tag.BOOL, java.util.regex.Pattern.compile("^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$"), "yYnNtTfFoO")
        addImplicitResolver(Tag.INT, INT, "-+0123456789")
        addImplicitResolver(Tag.MERGE, MERGE, "<")
        addImplicitResolver(Tag.NULL, NULL, "~nN\u0000")
        addImplicitResolver(Tag.NULL, EMPTY, null)
        addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789")
        addImplicitResolver(Tag.YAML, YAML, "!&*")
        val pattern = "^(?:[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+][0-9]+)?|\\.[0-9][0-9_]*(?:[eE][-+][0-9]+)?|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$"
        addImplicitResolver(Tag.FLOAT, java.util.regex.Pattern.compile(pattern), "-+0123456789.")
    }
}

private class CredentialConstructor(options: LoaderOptions) : SafeConstructor(options) {
    override fun createDefaultMap(size: Int): MutableMap<Any?, Any?> = CredentialMap()
    override fun processDuplicateKeys(node: MappingNode, forceStringKeys: Boolean) {
        for (pair in node.value) if (pair.keyNode.tag != Tag.MERGE) {
            val key = constructObject(pair.keyNode)
            if (key is Collection<*> || key is Map<*, *>) throw object : org.yaml.snakeyaml.constructor.ConstructorException(null, null, "unhashable key", pair.keyNode.startMark) {}
        }
    }
    init {
        yamlConstructors[Tag.BINARY] = object : AbstractConstruct() {
            override fun construct(node: Node): Any {
                val raw = (node as ScalarNode).value
                if (raw.any { it.code > 127 }) throw CredentialYamlFailure("ConstructorError")
                val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                val output = java.io.ByteArrayOutputStream(); var count = 0; var bits = 0; var padding = 0
                for (char in raw) {
                    if (char == '=') {
                        if (count >= 2 && ++padding >= 4 - count) return output.toByteArray()
                        continue
                    }
                    val value = alphabet.indexOf(char)
                    if (value < 0) continue
                    padding = 0; bits = (bits shl 6) or value; count++
                    if (count >= 2) output.write((bits shr (2 * (4 - count))) and 255)
                    if (count == 4) { count = 0; bits = 0 }
                }
                if (count != 0) throw CredentialYamlFailure("ConstructorError")
                return output.toByteArray()
            }
        }
        val bool = yamlConstructors.getValue(Tag.BOOL)
        yamlConstructors[Tag.BOOL] = object : AbstractConstruct() { override fun construct(node: Node): Any = bool.construct(node) ?: throw CredentialYamlFailure("KeyError") }
        val pairs = object : AbstractConstruct() {
            override fun construct(node: Node): Any {
                if (node !is SequenceNode) throw CredentialYamlFailure("ConstructorError")
                return node.value.map { child ->
                    if (child !is MappingNode || child.value.size != 1) throw CredentialYamlFailure("ConstructorError")
                    CredentialTuple(constructObject(child.value[0].keyNode), constructObject(child.value[0].valueNode))
                }
            }
        }
        yamlConstructors[Tag.PAIRS] = pairs; yamlConstructors[Tag.OMAP] = pairs
        yamlConstructors[Tag.TIMESTAMP] = object : AbstractConstruct() {
            override fun construct(node: Node): Any {
                val raw = (node as ScalarNode).value
                val match = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[Tt \\t]+(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\.(\\d*))?(?:[ \\t]*(Z|([-+])(\\d{1,2})(?::(\\d{2}))?))?)?$").matchEntire(raw)
                    ?: throw CredentialYamlFailure("AttributeError")
                val v = match.groupValues
                require(v[1].toInt() in 1..9999)
                val date = java.time.LocalDate.of(v[1].toInt(), v[2].toInt(), v[3].toInt())
                val dateArgs = "${date.year}, ${date.monthValue}, ${date.dayOfMonth}"
                if (v[4].isEmpty()) return CredentialTimestamp(date.toString(), "datetime.date($dateArgs)")
                val micro = v[7].take(6).padEnd(6, '0').toInt()
                val time = java.time.LocalTime.of(v[4].toInt(), v[5].toInt(), v[6].toInt(), micro * 1000)
                val formatted = "%02d:%02d:%02d".format(java.util.Locale.ROOT, time.hour, time.minute, time.second) + if (micro == 0) "" else "." + micro.toString().padStart(6, '0')
                val offset = (v[10].toIntOrNull() ?: 0) * 60 + (v[11].toIntOrNull() ?: 0)
                require(offset < 24 * 60)
                val zone = if (v[8].isEmpty()) "" else (if (offset == 0 || v[9] != "-") "+" else "-") + (offset / 60).toString().padStart(2, '0') + ":" + (offset % 60).toString().padStart(2, '0')
                val timeArgs = ", ${time.hour}, ${time.minute}" + (if (time.second != 0 || micro != 0) ", ${time.second}" else "") + (if (micro != 0) ", $micro" else "")
                val seconds = offset * 60 * if (v[9] == "-") -1 else 1
                val zoneRepr = when { zone.isEmpty() -> ""; seconds == 0 -> ", tzinfo=datetime.timezone.utc"; seconds > 0 -> ", tzinfo=datetime.timezone(datetime.timedelta(seconds=$seconds))"; else -> ", tzinfo=datetime.timezone(datetime.timedelta(days=-1, seconds=${86400 + seconds}))" }
                return CredentialTimestamp("$date $formatted$zone", "datetime.datetime($dateArgs$timeArgs$zoneRepr)")
            }
        }
        yamlConstructors[Tag.SET] = object : org.yaml.snakeyaml.constructor.Construct {
            override fun construct(node: Node): Any = CredentialSet()
            override fun construct2ndStep(node: Node, value: Any) { constructSet2ndStep(node as MappingNode, value as CredentialSet) }
        }
        val scalarTags = setOf(Tag.STR, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.BINARY, Tag.TIMESTAMP)
        for (tag in scalarTags + setOf(Tag.MAP, Tag.SET, Tag.SEQ, Tag.PAIRS, Tag.OMAP)) {
            val original = yamlConstructors.getValue(tag)
            yamlConstructors[tag] = object : org.yaml.snakeyaml.constructor.Construct {
                override fun construct(node: Node): Any? {
                    val valid = when (tag) { in scalarTags -> node is ScalarNode; Tag.MAP, Tag.SET -> node is MappingNode; else -> node is SequenceNode }
                    if (!valid) throw CredentialYamlFailure("ConstructorError")
                    return if (tag == Tag.SET && !node.isTwoStepsConstruction) CredentialSet().also { constructSet2ndStep(node as MappingNode, it) } else original.construct(node)
                }
                override fun construct2ndStep(node: Node, value: Any) = original.construct2ndStep(node, value)
            }
        }
    }
}
private class CredentialYamlFailure(val category: String) : RuntimeException()
private data class CredentialTimestamp(val text: String, val representation: String) { override fun toString() = text }
private data class CredentialTuple(val first: Any?, val second: Any?)
private class CredentialMap : LinkedHashMap<Any?, Any?>() {
    private val keysByIdentity = HashMap<Any?, Any?>()
    private fun identity(key: Any?): Any? = when (key) {
        is Boolean -> if (key) java.math.BigDecimal.ONE else java.math.BigDecimal.ZERO
        is Double -> if (key.isFinite()) java.math.BigDecimal(key).stripTrailingZeros() else key
        is Number -> key.toString().toBigDecimal().stripTrailingZeros()
        else -> key
    }
    override fun put(key: Any?, value: Any?): Any? {
        val identity = identity(key)
        val original = if (keysByIdentity.containsKey(identity)) keysByIdentity[identity] else key.also { keysByIdentity[identity] = it }
        return super.put(original, value)
    }
}
private class CredentialSet : AbstractMutableSet<Any?>() {
    private val values = CredentialMap()
    override val size: Int get() = values.size
    override fun iterator(): MutableIterator<Any?> = values.keys.iterator()
    override fun add(element: Any?): Boolean {
        if (element is Collection<*> || element is Map<*, *>) throw CredentialYamlFailure("ConstructorError")
        val before = size; values[element] = true; return before != size
    }
}

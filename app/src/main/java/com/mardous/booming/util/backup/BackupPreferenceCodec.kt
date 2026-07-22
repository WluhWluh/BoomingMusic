package com.mardous.booming.util.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object BackupPreferenceCodec {
    fun snapshot(
        values: Map<String, *>,
        definitions: Map<String, PortablePreferenceDefinition>,
    ): Map<String, JsonElement> = definitions.keys
        .asSequence()
        .filter(values::containsKey)
        .associateWith { key ->
            encodeValue(key, values[key], requireNotNull(definitions[key]).type)
        }
        .also { BackupContractValidator.validatePreferenceMap(it, definitions) }

    fun filter(
        values: Map<String, JsonElement>,
        definitions: Map<String, PortablePreferenceDefinition>,
    ): Map<String, JsonElement> = values
        .filterKeys(definitions::containsKey)
        .also { BackupContractValidator.validatePreferenceMap(it, definitions) }

    fun encodeLegacyXml(
        values: Map<String, JsonElement>,
        definitions: Map<String, PortablePreferenceDefinition>,
    ): ByteArray {
        BackupContractValidator.validatePreferenceMap(values, definitions)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n")
            append("<map>\n")
            values.toSortedMap().forEach { (key, value) ->
                appendLegacyValue(key, value, requireNotNull(definitions[key]).type)
            }
            append("</map>\n")
        }.encodeToByteArray()
    }

    fun encodeBooleanMapLegacyXml(values: Map<String, Boolean>): ByteArray {
        val definitions = values.keys.associateWith { key ->
            PortablePreferenceDefinition(
                key = key,
                type = PortablePreferenceType.Boolean,
                defaultValue = JsonPrimitive(false),
            )
        }
        return encodeLegacyXml(
            values.mapValues { JsonPrimitive(it.value) },
            definitions,
        )
    }

    fun decodeBooleanMapLegacyXml(contents: ByteArray): Map<String, Boolean> =
        decodeLegacyXml(contents).mapValues { (key, value) ->
            val primitive = value as? JsonPrimitive
                ?: throw BackupContractException("Legacy preference $key is not boolean")
            primitive.booleanOrNull?.takeIf { !primitive.isString }
                ?: throw BackupContractException("Legacy preference $key is not boolean")
        }

    fun decodeLegacyXml(
        contents: ByteArray,
        allowedKeys: Set<String>? = null,
    ): Map<String, JsonElement> {
        val declarationProbe = contents.decodeToString()
        if (declarationProbe.contains("<!DOCTYPE", ignoreCase = true) ||
            declarationProbe.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw BackupContractException("Legacy preferences cannot declare XML entities")
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        }
        val document = ByteArrayInputStream(contents).use { input ->
            factory.newDocumentBuilder().parse(input)
        }
        val root = document.documentElement
        if (root == null || root.tagName != "map") {
            throw BackupContractException("Legacy preferences must contain one map root")
        }

        val result = linkedMapOf<String, JsonElement>()
        val nodes = root.childNodes
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name").takeIf(String::isNotBlank)
                ?: throw BackupContractException("Legacy preference name is empty")
            if (allowedKeys != null && name !in allowedKeys) continue
            if (result.containsKey(name)) {
                throw BackupContractException("Legacy preferences contain duplicate key: $name")
            }
            result[name] = element.decodeLegacyValue(name)
        }
        return result
    }

    private fun encodeValue(
        key: String,
        value: Any?,
        type: PortablePreferenceType,
    ): JsonElement = when (type) {
        PortablePreferenceType.Boolean -> JsonPrimitive(
            value as? Boolean ?: throw typeError(key, type, value),
        )
        PortablePreferenceType.Integer -> JsonPrimitive(
            value as? Int ?: throw typeError(key, type, value),
        )
        PortablePreferenceType.Long -> JsonPrimitive(
            value as? Long ?: throw typeError(key, type, value),
        )
        PortablePreferenceType.Float -> {
            val floatValue = value as? Float ?: throw typeError(key, type, value)
            if (!floatValue.isFinite()) {
                throw BackupContractException("Preference $key is not finite")
            }
            JsonPrimitive(floatValue)
        }
        PortablePreferenceType.String -> JsonPrimitive(
            value as? String ?: throw typeError(key, type, value),
        )
        PortablePreferenceType.StringSet -> {
            val set = value as? Set<*> ?: throw typeError(key, type, value)
            JsonArray(
                set.map { member ->
                    JsonPrimitive(
                        member as? String ?: throw typeError(key, type, member),
                    )
                }.sortedBy { it.content },
            )
        }
    }

    private fun StringBuilder.appendLegacyValue(
        key: String,
        value: JsonElement,
        type: PortablePreferenceType,
    ) {
        val escapedKey = key.escapeXml()
        when (type) {
            PortablePreferenceType.Boolean -> appendScalar("boolean", escapedKey, value)
            PortablePreferenceType.Integer -> appendScalar("int", escapedKey, value)
            PortablePreferenceType.Long -> appendScalar("long", escapedKey, value)
            PortablePreferenceType.Float -> appendScalar("float", escapedKey, value)
            PortablePreferenceType.String -> {
                val primitive = value as JsonPrimitive
                append("    <string name=\"")
                    .append(escapedKey)
                    .append("\">")
                    .append(primitive.content.escapeXml())
                    .append("</string>\n")
            }
            PortablePreferenceType.StringSet -> {
                append("    <set name=\"").append(escapedKey).append("\">\n")
                (value as JsonArray).forEach { member ->
                    append("        <string>")
                        .append((member as JsonPrimitive).content.escapeXml())
                        .append("</string>\n")
                }
                append("    </set>\n")
            }
        }
    }

    private fun StringBuilder.appendScalar(
        tag: String,
        escapedKey: String,
        value: JsonElement,
    ) {
        append("    <")
            .append(tag)
            .append(" name=\"")
            .append(escapedKey)
            .append("\" value=\"")
            .append((value as JsonPrimitive).content.escapeXml())
            .append("\" />\n")
    }

    private fun Element.decodeLegacyValue(name: String): JsonElement = when (tagName) {
        "string" -> JsonPrimitive(textContent.orEmpty())
        "boolean" -> when (val value = requiredValue(name)) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> throw BackupContractException(
                "Legacy preference $name is not a boolean: $value",
            )
        }
        "int" -> JsonPrimitive(
            requiredValue(name).toIntOrNull()
                ?: throw BackupContractException("Legacy preference $name is not an integer"),
        )
        "long" -> JsonPrimitive(
            requiredValue(name).toLongOrNull()
                ?: throw BackupContractException("Legacy preference $name is not a long"),
        )
        "float" -> JsonPrimitive(
            requiredValue(name).toFloatOrNull()?.takeIf(Float::isFinite)
                ?: throw BackupContractException(
                    "Legacy preference $name is not a finite float",
                ),
        )
        "set" -> {
            val values = mutableListOf<JsonElement>()
            val nodes = childNodes
            for (index in 0 until nodes.length) {
                val child = nodes.item(index) as? Element ?: continue
                if (child.tagName != "string") {
                    throw BackupContractException(
                        "Legacy preference $name has an invalid set member",
                    )
                }
                values += JsonPrimitive(child.textContent.orEmpty())
            }
            JsonArray(values)
        }
        else -> throw BackupContractException("Unsupported legacy preference type: $tagName")
    }

    private fun Element.requiredValue(name: String): String {
        if (!hasAttribute("value")) {
            throw BackupContractException("Legacy preference $name has no value")
        }
        return getAttribute("value")
    }

    private fun typeError(
        key: String,
        expected: PortablePreferenceType,
        value: Any?,
    ) = BackupContractException(
        "Preference $key must use $expected, not ${value?.javaClass?.simpleName ?: "null"}",
    )

    private fun String.escapeXml(): String = buildString(length) {
        this@escapeXml.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                in '\u0000'..'\u0008',
                in '\u000b'..'\u000c',
                in '\u000e'..'\u001f' -> append('\uFFFD')
                else -> append(character)
            }
        }
    }

    private const val ACCESS_EXTERNAL_DTD =
        "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA =
        "http://javax.xml.XMLConstants/property/accessExternalSchema"
}

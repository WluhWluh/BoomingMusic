package com.mardous.booming.util.backup

import android.content.SharedPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

internal fun SharedPreferences.Editor.putPortablePreferences(
    values: Map<String, JsonElement>,
    definitions: Map<String, PortablePreferenceDefinition>,
) {
    BackupContractValidator.validatePreferenceMap(values, definitions)
    values.forEach { (key, value) ->
        when (requireNotNull(definitions[key]).type) {
            PortablePreferenceType.Boolean -> putBoolean(key, (value as JsonPrimitive).boolean)
            PortablePreferenceType.Integer -> putInt(key, (value as JsonPrimitive).int)
            PortablePreferenceType.Long -> putLong(key, (value as JsonPrimitive).long)
            PortablePreferenceType.Float -> putFloat(key, (value as JsonPrimitive).float)
            PortablePreferenceType.String -> putString(key, (value as JsonPrimitive).content)
            PortablePreferenceType.StringSet -> putStringSet(
                key,
                (value as JsonArray).map { (it as JsonPrimitive).content }.toSet(),
            )
        }
    }
}

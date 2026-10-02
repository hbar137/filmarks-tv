package net.eulerai.filmarks.tv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Small readers for the API's JSON (fields are optional throughout).
fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it !is JsonNull }?.content?.takeIf { it != "null" } ?: ""
fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
fun JsonObject.dbl(k: String): Double = (this[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
fun JsonObject.strs(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
fun JsonElement?.obj(): JsonObject? = this as? JsonObject

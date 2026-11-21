package com.meteor.memory.core.storage

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Length-prefixed primitive codec shared by the encrypted metadata sidecars
 * (ADR-052). Every sidecar starts with `magic | version` so a file can never be
 * decoded through the wrong parser, and every string is bounded before it is
 * read so a corrupted length cannot allocate an arbitrary buffer.
 */
internal const val MAX_METADATA_STRING_BYTES = 1024 * 1024

internal fun DataOutputStream.writeMetadataHeader(magic: Int, version: Int) {
    writeInt(magic)
    writeInt(version)
}

internal fun DataInputStream.readMetadataHeader(magic: Int, version: Int) {
    check(readInt() == magic) { "invalid encrypted metadata" }
    check(readInt() == version) { "unsupported encrypted metadata format" }
}

internal fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
}

internal fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    if (value != null) writeLong(value)
}

internal fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    if (value != null) writeInt(value)
}

internal fun DataInputStream.readString(): String {
    val size = readInt()
    check(size in 0..MAX_METADATA_STRING_BYTES) { "invalid metadata string size" }
    val bytes = ByteArray(size)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

internal fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readString() else null

internal fun DataInputStream.readNullableLong(): Long? =
    if (readBoolean()) readLong() else null

internal fun DataInputStream.readNullableInt(): Int? =
    if (readBoolean()) readInt() else null

package com.meteor.memory.core.storage

import android.net.Uri
import java.io.File
import java.time.LocalDate

/**
 * FROZEN INTERFACE (Meteor archive §8). Do not modify without an ADR.
 *
 * Encryption is transparent: [read] returns plaintext, [write] encrypts.
 * Callers never see ciphertext and never touch the Keystore.
 */
interface StorageRouter {
    fun route(personId: String?, type: RecordType, date: LocalDate): File
    fun read(file: File): Document
    fun write(file: File, doc: Document)
    suspend fun exportTo(zipUri: Uri)
    suspend fun importFrom(zipUri: Uri)
}

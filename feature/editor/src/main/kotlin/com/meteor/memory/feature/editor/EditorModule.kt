package com.meteor.memory.feature.editor

import androidx.compose.runtime.Composable
import com.meteor.memory.core.storage.Document
import java.io.File

/**
 * FROZEN INTERFACE (Meteor archive §8). Do not modify without an ADR.
 *
 * The implementation is deliberately swappable (archive §11: "EditorModule
 * 内部可替换实现，对外接口不变"): v0.4 ships a Markdown implementation built on
 * plain Compose, so adopting a third-party rich-text engine later touches only
 * the class behind this interface.
 */
interface EditorModule {
    fun open(file: File): Document

    suspend fun save(doc: Document)

    fun render(doc: Document): @Composable () -> Unit

    fun insertImage(doc: Document, imageBytes: ByteArray, personId: String?): Document

    fun insertLink(doc: Document, target: String, label: String?): Document
}

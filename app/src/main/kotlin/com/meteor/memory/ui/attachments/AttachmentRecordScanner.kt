package com.meteor.memory.ui.attachments

import com.meteor.memory.core.storage.AttachmentStore
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.EncryptedAttachmentStore
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StorageLayout
import java.io.File

/** Adds decrypted attachment text only to the per-request AI corpus. */
class AttachmentRecordScanner(
    private val records: RecordScanner,
    private val attachments: AttachmentStore,
    private val rootDir: File,
) : RecordScanner {

    override fun scan(): List<Document> {
        val imported = attachments.list().mapNotNull { attachment ->
            attachments.readIndexedText(attachment.id)?.let { text ->
                val owner = attachment.personId ?: EncryptedAttachmentStore.SELF_DIR_NAME
                Document(
                    file = File(
                        rootDir,
                        "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}/$owner/${attachment.id}.md",
                    ),
                    personId = attachment.personId,
                    type = RecordType.CHAT_LOG,
                    timestamp = attachment.modifiedAt,
                    source = RecordSource.USER,
                    body = text,
                )
            }
        }
        return records.scan() + imported
    }
}

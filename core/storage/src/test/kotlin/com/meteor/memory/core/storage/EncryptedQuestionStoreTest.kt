package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EncryptedQuestionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Reverses the bytes behind a 29-byte envelope, mirroring the AES-GCM
     * overhead (1 byte nonce length + 12 nonce + 16 tag) so a "decrypt" of real
     * ciphertext shape stays faithful without a Keystore.
     */
    private class TestCrypto : FileCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size + ENVELOPE_SIZE).also { encrypted ->
                plaintext.reversedArray().copyInto(encrypted, destinationOffset = ENVELOPE_SIZE)
            }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size >= ENVELOPE_SIZE)
            return ciphertext.copyOfRange(ENVELOPE_SIZE, ciphertext.size).reversedArray()
        }

        private companion object {
            const val ENVELOPE_SIZE = 29
        }
    }

    private fun store(): EncryptedQuestionStore =
        EncryptedQuestionStore(tmp.root, TestCrypto())

    private val created = Instant.parse("2026-11-27T14:10:00Z")

    @Test
    fun createPersistsTextAndReminders() {
        val reminders = listOf(
            Instant.parse("2026-11-28T14:10:00Z"),
            Instant.parse("2026-11-27T20:00:00Z"),
        )
        val created = store().create("我为什么想换工作？", reminders, created)

        val reloaded = store().find(created.id)
        assertEquals("我为什么想换工作？", reloaded?.text)
        // Sorted ascending, so "next reminder" is simply the first element.
        assertEquals(
            listOf(Instant.parse("2026-11-27T20:00:00Z"), Instant.parse("2026-11-28T14:10:00Z")),
            reloaded?.reminders,
        )
        assertFalse(reloaded!!.isAnswered)
    }

    @Test
    fun askedOnPersistsAndDefaultsToTheCreationDay() {
        val store = store()
        val backfilledDay = LocalDate.of(2026, 11, 20)
        val backfilled = store.create("补写的问题", emptyList(), created, backfilledDay)
        assertEquals(backfilledDay, store.find(backfilled.id)?.askedOn)

        val defaulted = store.create("今天的问题", emptyList(), created)
        assertEquals(
            created.atZone(ZoneId.systemDefault()).toLocalDate(),
            defaulted.askedOn,
        )
    }

    @Test
    fun olderFilesWithoutAskedOnFallBackToTheCreationDay() {
        val store = store()
        val question = store.create("旧格式的问题", emptyList(), created)
        val file = File(tmp.root, "每日一问/${question.id}.bin")
        val json = JSONObject(String(TestCrypto().decrypt(file.readBytes()), Charsets.UTF_8))
        json.remove("askedOn")
        file.writeBytes(TestCrypto().encrypt(json.toString().toByteArray(Charsets.UTF_8)))

        assertEquals(
            created.atZone(ZoneId.systemDefault()).toLocalDate(),
            store.find(question.id)?.askedOn,
        )
    }

    @Test
    fun duplicateRemindersCollapse() {
        val at = Instant.parse("2026-11-28T09:00:00Z")
        val created = store().create("问题", listOf(at, at, at), created)
        assertEquals(listOf(at), store().find(created.id)?.reminders)
    }

    @Test
    fun answersAppendAndKeepTheirOwnTimes() {
        val question = store().create("今天想通了吗？", emptyList(), created)
        val first = Instant.parse("2026-11-28T09:01:00Z")
        val second = Instant.parse("2026-11-29T21:30:00Z")

        store().addAnswer(question.id, "想通了一半", first)
        val updated = store().addAnswer(question.id, "彻底想通了", second)

        assertEquals(2, updated?.answers?.size)
        // Order is insertion order, and each answer keeps the minute it was written.
        assertEquals(listOf(first, second), updated?.answers?.map { it.answeredAt })
        assertTrue(updated!!.isAnswered)
        assertEquals(second, updated.lastAnsweredAt)
    }

    @Test
    fun updateRemindersReplacesTheWholeSet() {
        val question = store().create("问题", listOf(Instant.parse("2026-11-28T09:00:00Z")), created)
        val snoozed = Instant.parse("2026-11-28T10:00:00Z")

        val updated = store().updateReminders(question.id, listOf(snoozed))

        assertEquals(listOf(snoozed), updated?.reminders)
        assertEquals(listOf(snoozed), store().find(question.id)?.reminders)
    }

    @Test
    fun upcomingRemindersDropThePast() {
        val now = Instant.parse("2026-11-28T12:00:00Z")
        val question = StoredQuestion(
            id = "a".repeat(32),
            text = "问题",
            createdAt = created,
            reminders = listOf(
                Instant.parse("2026-11-28T11:00:00Z"),
                Instant.parse("2026-11-28T13:00:00Z"),
                Instant.parse("2026-11-29T09:00:00Z"),
            ),
        )

        assertEquals(
            listOf(Instant.parse("2026-11-28T13:00:00Z"), Instant.parse("2026-11-29T09:00:00Z")),
            question.upcomingReminders(now),
        )
        assertEquals(Instant.parse("2026-11-28T13:00:00Z"), question.nextReminder(now))
        assertNull(question.copy(reminders = emptyList()).nextReminder(now))
    }

    @Test
    fun deleteRemovesTheFile() {
        val store = store()
        val question = store.create("问题", emptyList(), created)

        assertTrue(store.delete(question.id))
        assertNull(store.find(question.id))
        assertFalse(store.delete(question.id))
    }

    @Test
    fun listIsNewestFirstAndSkipsUnreadableFiles() {
        val store = store()
        store.create("旧的", emptyList(), Instant.parse("2026-11-20T09:00:00Z"))
        store.create("新的", emptyList(), Instant.parse("2026-11-26T09:00:00Z"))
        // A file that cannot be decrypted must not hide the readable ones. It has
        // to sit in the store's own directory: `list()` only walks that folder.
        val garbage = File(tmp.root, "每日一问/${"b".repeat(32)}.bin")
        garbage.parentFile?.mkdirs()
        garbage.writeText("not a ciphertext")

        assertEquals(listOf("新的", "旧的"), store.list().map { it.text })
    }

    @Test
    fun blankTextAndEmptyAnswerAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            store().create("   ", emptyList(), created)
        }
        val question = store().create("问题", emptyList(), created)
        assertThrows(IllegalArgumentException::class.java) {
            store().addAnswer(question.id, "  ", created)
        }
    }

    @Test
    fun unknownIdIsANoOp() {
        assertNull(store().addAnswer("c".repeat(32), "答案", created))
        assertNull(store().updateReminders("c".repeat(32), emptyList()))
        assertNull(store().find("../../etc/passwd"))
    }

    @Test
    fun ciphertextNeverContainsTheQuestionText() {
        val store = store()
        val question = store.create("不要在磁盘上看到我", emptyList(), created)
        val raw = File(tmp.root, "每日一问/${question.id}.bin").readBytes()

        assertFalse(String(raw, Charsets.ISO_8859_1).contains("不要在磁盘上看到我"))
    }
}

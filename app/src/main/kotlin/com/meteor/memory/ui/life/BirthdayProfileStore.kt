package com.meteor.memory.ui.life

import android.util.AtomicFile
import com.meteor.memory.core.crypto.FileCrypto
import java.io.File
import java.io.FileNotFoundException

class BirthdayProfileStore(
    file: File,
    private val crypto: FileCrypto,
) {
    private val atomicFile = AtomicFile(file)

    fun read(): BirthProfile? {
        val encrypted = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return null
        }
        return BirthProfileCodec.decode(crypto.decrypt(encrypted))
    }

    fun save(profile: BirthProfile) {
        val encrypted = crypto.encrypt(BirthProfileCodec.encode(profile))
        val output = atomicFile.startWrite()
        try {
            output.write(encrypted)
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }
}

package io.nekohasekai.sfa.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Validate a unique temporary file before atomically replacing the last usable rule-set. */
internal object RuleSetFileCommit {
    fun replace(target: File, write: (File) -> Unit, validate: (File) -> Unit) {
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create rule-set directory" }
        val temporary = File.createTempFile(".rule-set-", ".tmp", parent)
        try {
            write(temporary)
            validate(temporary)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }
}

package dev.routinecoach.core.pack

import java.io.File

/** [PackSource] backed by a directory on disk. */
class DirectorySource(private val root: File) : PackSource {
    override fun read(path: String): String? = File(root, path).takeIf { it.isFile }?.readText()
    override fun list(dir: String): List<String> = File(root, dir).list()?.toList().orEmpty()
    override fun size(path: String): Long? = File(root, path).takeIf { it.isFile }?.length()
}

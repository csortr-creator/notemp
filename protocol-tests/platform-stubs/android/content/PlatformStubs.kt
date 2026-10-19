package android.content
import java.io.File
class Assets(private val root: File) { fun open(path: String) = File(root, path).inputStream() }
open class Context(val filesDir: File, val assets: Assets = Assets(filesDir))

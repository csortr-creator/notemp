package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(val looper: Looper)
object Build { object VERSION { var SDK_INT = 35 } }
class ParcelFileDescriptor : java.io.Closeable {
    val fileDescriptor = java.io.FileDescriptor()
    override fun close() {}
    companion object { fun fromFd(fd: Int) = ParcelFileDescriptor() }
}

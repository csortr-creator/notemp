package android.util

object Log {
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, t: Throwable? = null): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, t: Throwable): Int = 0
}
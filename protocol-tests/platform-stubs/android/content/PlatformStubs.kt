package android.content

import java.io.File

class Assets(private val root: File) {
    fun open(path: String) = File(root, path).inputStream()
}

open class Context(val filesDir: File, val assets: Assets = Assets(filesDir)) {
    open fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean = true
    open fun unbindService(conn: ServiceConnection) {}
}

open class Intent(var action: String? = null) {
    constructor(context: Context, clazz: Class<*>) : this()
    fun setAction(action: String): Intent {
        this.action = action
        return this
    }
}

class ComponentName(val pkg: String, val cls: String)

interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, service: android.os.IBinder)
    fun onServiceDisconnected(name: ComponentName?)
    fun onBindingDied(name: ComponentName?) {}
}

with open("app/src/main/java/io/nekohasekai/sfa/bg/ServiceConnection.kt", "r") as f:
    content = f.read()

content = content.replace("""    fun disconnect() {
        try {
            try {
                service?.unregisterCallback(callback)
            } catch (e: RemoteException) {
                Log.e(TAG, "cleanup service connection", e)
            }
            delegate?.let { context.unbindService(it) }
            delegate = null
            service = null
        } catch (_: IllegalArgumentException) {
        }
        Log.d(TAG, "request disconnect")
    }""", """    fun disconnect() {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            Log.e(TAG, "cleanup service connection", e)
        }
        try {
            delegate?.let { context.unbindService(it) }
        } catch (_: IllegalArgumentException) {
        }
        delegate = null
        service = null
        Log.d(TAG, "request disconnect")
    }""")

content = content.replace("""    fun reconnect() {
        try {
            try {
                service?.unregisterCallback(callback)
            } catch (e: RemoteException) {
                Log.e(TAG, "cleanup service connection", e)
            }
            delegate?.let { context.unbindService(it) }
            delegate = null
            service = null
        } catch (_: IllegalArgumentException) {
        }
        val intent =""", """    fun reconnect() {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            Log.e(TAG, "cleanup service connection", e)
        }
        try {
            delegate?.let { context.unbindService(it) }
        } catch (_: IllegalArgumentException) {
        }
        delegate = null
        service = null
        val intent =""")

with open("app/src/main/java/io/nekohasekai/sfa/bg/ServiceConnection.kt", "w") as f:
    f.write(content)


with open("protocol-tests/src/test/kotlin/io/nekohasekai/sfa/bg/ServiceConnectionTest.kt", "r") as f:
    content2 = f.read()

content2 = content2.replace("""connection.onServiceConnected(ComponentName("test", "test"), mockService)""", """(context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService)""")
content2 = content2.replace("""connection.onServiceConnected(ComponentName("test", "test"), mockService1)""", """(context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService1)""")
content2 = content2.replace("""connection.onServiceConnected(ComponentName("test", "test"), mockService2)""", """(context.conn as? android.content.ServiceConnection)?.onServiceConnected(ComponentName("test", "test"), mockService2)""")
content2 = content2.replace("""connection.onServiceDisconnected(null)""", """(context.conn as? android.content.ServiceConnection)?.onServiceDisconnected(null)""")
content2 = content2.replace("""connection.onBindingDied(null)""", """(context.conn as? android.content.ServiceConnection)?.onBindingDied(null)""")

with open("protocol-tests/src/test/kotlin/io/nekohasekai/sfa/bg/ServiceConnectionTest.kt", "w") as f:
    f.write(content2)

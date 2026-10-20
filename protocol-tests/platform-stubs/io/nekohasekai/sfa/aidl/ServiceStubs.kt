package io.nekohasekai.sfa.aidl
interface IServiceCallback : android.os.IInterface {
    fun onServiceStatusChanged(status: Int)
    fun onServiceAlert(type: Int, message: String?)
    abstract class Stub : IServiceCallback, android.os.IBinder {
        override fun asBinder(): android.os.IBinder = this
    }
}
interface IService : android.os.IInterface {
    fun getStatus(): Int
    fun registerCallback(callback: IServiceCallback)
    fun unregisterCallback(callback: IServiceCallback?)
    abstract class Stub : IService, android.os.IBinder {
        companion object {
            fun asInterface(binder: android.os.IBinder): IService = binder as IService
        }
        override fun asBinder(): android.os.IBinder = this
    }
}

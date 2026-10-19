package io.nekohasekai.sfa.aidl

interface IServiceCallback {
    fun onServiceStatusChanged(status: Int)
    fun onServiceAlert(type: Int, message: String?)
}
interface IService {
    fun getStatus(): Int
    fun registerCallback(callback: IServiceCallback)
    fun unregisterCallback(callback: IServiceCallback?)
    abstract class Stub : IService
}

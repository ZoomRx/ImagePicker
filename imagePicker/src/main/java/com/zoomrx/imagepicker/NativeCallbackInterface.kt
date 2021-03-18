package com.zoomrx.camera

interface NativeCallbackInterface {
    fun resolve(filePathArray: ArrayList<String>, captionArray: ArrayList<String>?)
    fun reject(message: String, code: Int)
}
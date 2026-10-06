package com.thereprocase.thermalfield

import android.view.Surface

// The native registry retains each engine for the duration of a JNI call, so
// teardown can race a final telemetry poll without exposing a freed pointer.
internal class NativeBridge {
    init { System.loadLibrary("thermal_core") }
    external fun create(): Long
    external fun destroy(id: Long)
    external fun surface(id: Long, surface: Surface?)
    external fun configure(id: Long, palette: Int, flip: Boolean, rotation: Int, mirror: Boolean, automatic: Boolean, lower: Float, upper: Float)
    external fun correction(id: Long, emissivity: Double, reflectedCelsius: Double, corrected: Boolean)
    external fun geometry(id: Long, measurementId: Int, kind: Int, x0: Double, y0: Double, x1: Double, y1: Double): Int
    external fun eraseGeometry(id: Long, measurementId: Int)
    external fun measurementOptions(id: Long, first: Int, second: Int, isotherm: Int, lower: Float, upper: Float)
    external fun measurementVersion(id: Long): Long
    external fun open(id: Long, fd: Int): String
    external fun replay(id: Long, frame: ByteArray)
    external fun beginNetwork(id: Long)
    external fun networkFrame(id: Long, frame: ByteArray, sequence: Long)
    external fun cancel(id: Long)
    external fun stop(id: Long)
    external fun nuc(id: Long)
    external fun gain(id: Long, high: Boolean)
    external fun summary(id: Long): String
    external fun dump(id: Long): ByteArray
    external fun snapshot(id: Long): ByteArray
    external fun capture(id: Long): ByteArray
}

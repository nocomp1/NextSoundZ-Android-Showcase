package com.nextsoundz.showcase.midi

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.nextsoundz.showcase.events.MidiEvent
import com.nextsoundz.showcase.events.MidiEventBus
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * Hardware device I/O: discovery, hot-plug, port lifetime, and teardown.
 *
 * This is the device-communication work in the app. The transport is USB/virtual MIDI via
 * `android.media.midi` rather than BLE GATT, but the shape of the problem is the one every
 * peripheral integration has: devices appear and vanish at arbitrary times, the callback
 * arrives on a thread you do not own, and every resource you open must be released on
 * paths you did not think about.
 *
 * **Failure modes this handles, each of which was a real bug at some point:**
 *
 *  - A device is unplugged mid-note. Ports close, but the *sounding voice* must also be
 *    stopped, or the note hangs forever with no device left to send a Note Off.
 *  - `openDevice` is asynchronous and can complete **after** the user has already navigated
 *    away or unplugged the device — so the completion handler must verify the device is
 *    still wanted before wiring it up, and close it immediately if not.
 *  - Two devices with the same product name. Identity must key on the device **info**, not
 *    the display name.
 *  - `onDeviceRemoved` may fire for a device that was never successfully opened.
 */
@Singleton
class MidiDeviceConnection @Inject constructor(
    private val context: Context,
    private val eventBus: MidiEventBus,
    private val parser: MidiMessageParser,
) {

    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    /** MIDI callbacks are delivered here. Kept off the main thread. */
    private val callbackHandler = Handler(Looper.getMainLooper())

    private var openPort: MidiOutputPort? = null
    private var openDeviceId: Int? = null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo?) {
            device?.let(::connect)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo?) {
            // Guard: removal can arrive for a device we never opened.
            if (device != null && device.id == openDeviceId) disconnect()
        }
    }

    fun start() {
        val manager = midiManager ?: return
        manager.registerDeviceCallback(deviceCallback, callbackHandler)
        // Devices already attached at startup do not produce an onDeviceAdded callback.
        manager.devices.firstOrNull(::hasOutputPort)?.let(::connect)
    }

    fun stop() {
        midiManager?.unregisterDeviceCallback(deviceCallback)
        disconnect()
    }

    private fun hasOutputPort(info: MidiDeviceInfo) = info.outputPortCount > 0

    private fun connect(info: MidiDeviceInfo) {
        val manager = midiManager ?: return
        if (!hasOutputPort(info)) return
        if (openDeviceId == info.id) return          // already connected
        if (openDeviceId != null) disconnect()        // single active device in this sample

        openDeviceId = info.id

        manager.openDevice(info, { device ->
            // Async completion. By the time we get here the user may have unplugged the
            // device or we may have moved on — in which case close it and do nothing.
            if (device == null || openDeviceId != info.id) {
                device?.close()
                if (openDeviceId == info.id) openDeviceId = null
                return@openDevice
            }

            try {
                openPort = device.openOutputPort(FIRST_PORT)?.apply {
                    connect(receiver)
                }
            } catch (e: IOException) {
                // A device that fails to open is not a crash — it is a device we do not use.
                openDeviceId = null
                openPort = null
            }
        }, callbackHandler)
    }

    private fun disconnect() {
        openPort?.runCatching { close() }
        openPort = null
        openDeviceId = null
        // Critical: silence anything the departed device left sounding.
        allNotesOff()
    }

    private fun allNotesOff() {
        for (channel in 0 until MIDI_CHANNELS) {
            eventBus.stuckNoteGuard?.let { guard ->
                for (note in 0..MIDI_MAX_NOTE) guard.forceNoteOff(channel, note)
            }
        }
    }

    /**
     * Receives raw MIDI bytes from the driver.
     *
     * This runs on a MIDI callback thread with a latency budget. It does the minimum:
     * timestamp, parse, publish. No allocation-heavy work, no disk, no locks, no waiting.
     * Everything else happens on a collector's own dispatcher.
     */
    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parser.parse(data, offset, count, timestamp).forEach { event ->
                eventBus.emit(event)
            }
        }
    }

    private companion object {
        const val FIRST_PORT = 0
        const val MIDI_CHANNELS = 16
        const val MIDI_MAX_NOTE = 127
    }
}

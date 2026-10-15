package com.yeivikas.olyze.eliner.modules.midi

import android.app.Application
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo as AndroidMidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import com.yeivikas.olyze.eliner.api.midi.MidiDeviceInfo
import com.yeivikas.olyze.eliner.api.midi.MidiDeviceState
import com.yeivikas.olyze.eliner.api.midi.MidiEvent
import com.yeivikas.olyze.eliner.api.midi.MidiPortDirection
import com.yeivikas.olyze.eliner.api.midi.MidiPortInfo
import com.yeivikas.olyze.eliner.api.midi.MidiTransport
import com.yeivikas.olyze.eliner.services.TimeProvider

/**
 * §26: the one file in this project allowed to import `android.media.
 * midi.*` for the MIDI Foundation (verified — see [MidiPlatformBackend]'s
 * doc). Everything above [MidiPlatformBackend] only ever sees
 * [com.yeivikas.olyze.eliner.api] types.
 *
 * [context] is [Application], not [Context] — same hardening-pass
 * reasoning already applied to `DeviceCapabilityManager`/
 * `AudioDeviceManager`: this class retains it for its own lifetime.
 * [timeProvider] is constructor-injected, matching this project's
 * established DI-by-constructor style everywhere else (`AudioClock`,
 * `RuntimeContext`, etc.) — used to stamp [MidiEvent.timestampNanos] at
 * the moment each raw MIDI callback arrives (see [openInputPorts]).
 *
 * Runs its own dedicated [HandlerThread] for `MidiManager` callbacks
 * (device discovery, device-open results, port receive callbacks) rather
 * than reusing [com.yeivikas.olyze.eliner.services.ThreadManager]'s
 * `ExecutionLane.IO`/`BACKGROUND` — §27 asks to "determinar cuidadosamente
 * en qué contexto se ejecuta" each of these, and the reason NOT to reuse
 * ThreadManager here specifically is that Android's MIDI APIs require a
 * [Handler] (a `Looper`-based callback target), not a `CoroutineScope`/
 * `Executor` — there is no clean way to hand `MidiManager` one of
 * ThreadManager's coroutine dispatchers directly. This is the one place
 * in the MIDI Foundation with its own thread, and it exists because the
 * platform API leaves no other option, not because ThreadManager was
 * insufficient in general.
 */
class AndroidMidiBackend(
    private val context: Application,
    private val timeProvider: TimeProvider,
) : MidiPlatformBackend {
    private val midiManager: MidiManager? =
        context.getSystemService(android.content.Context.MIDI_SERVICE) as? MidiManager

    private val callbackThread = HandlerThread("eliner-midi-callback").apply { start() }
    private val handler = Handler(callbackThread.looper)

    private var deviceCallback: MidiManager.DeviceCallback? = null

    // ── Audit fix: these three maps are mutated from at least two real
    //    threads — `handler`'s dedicated eliner-midi-callback HandlerThread
    //    (openInputPorts' openDevice callback below, and hot-plug add/remove
    //    delivered through the DeviceCallback registered with `handler` in
    //    startWatching) — AND whatever thread calls send()/closeDevice()/
    //    shutdown() (MidiDeviceManager.stop() and MidiOutputBridge.send()
    //    are called from ordinary control-plane threads, not `handler`'s).
    //    A plain HashMap mutated concurrently like that is a real data
    //    race — the exact same class of bug AudioCommandDispatcher's own
    //    doc comment describes fixing for the native command queue, just
    //    here for MidiDevice/MidiInputPort/MidiOutputPort handles instead
    //    of audio commands. `synchronized(lock)` matches this project's
    //    own established pattern for control-plane (non-realtime-audio)
    //    shared state — see ModuleRegistry/ResourceManager/ServiceRegistry.
    private val handlesLock = Any()
    // deviceId (String, = AndroidMidiDeviceInfo.id.toString()) -> open handles.
    private val openDevices = mutableMapOf<String, MidiDevice>()
    private val openReceivers = mutableMapOf<String, List<MidiReceiverConnection>>()
    private val openOutputPorts = mutableMapOf<String, MidiInputPort>() // keyed by our portId — see send().

    // ── Fase 1.1 §18 fix: generation/token contra callbacks tardíos ────────
    // `MidiManager.openDevice()` es asíncrono — el `callback` que le pasamos
    // en [openInputPorts] puede llegar en `handler`'s HandlerThread en
    // CUALQUIER momento posterior, incluido después de [shutdown] o después
    // de un [closeDevice] posterior para el MISMO `deviceId` (p. ej. el
    // usuario reconecta rápido el mismo dispositivo, o el sistema lo quita
    // y AndroidMidiBackend cierra la sesión anterior mientras el open()
    // previo seguía pendiente). Sin guardarlo, ese callback tardío escribía
    // igual en [openDevices]/[openReceivers] — "resucitando" un handle que
    // el resto del sistema (MidiDeviceManager, EliNer) ya considera cerrado:
    // un MidiDevice sin dueño, nunca cerrado, potencialmente entregando
    // eventos MIDI a un `onEvent` de una sesión que ya terminó.
    //
    // Mecanismo: cada llamada a [openInputPorts] para un `deviceId` obtiene
    // un número de generación nuevo y monótono (mGenerationCounter, un
    // único contador global — no necesita ser por-dispositivo, solo
    // estrictamente creciente) y lo registra en [openGenerationByDevice]
    // ANTES de invocar `manager.openDevice()`. Cuando el callback asíncrono
    // finalmente llega, compara su generación capturada contra la que hay
    // registrada para ese `deviceId` EN ESE MOMENTO, bajo el mismo
    // `handlesLock` que ya protege el resto del estado — si no coincide (el
    // dispositivo fue cerrado, o se abrió una sesión más nueva para el
    // mismo id mientras esta estaba en vuelo) o si el backend ya fue
    // apagado (`isShutDown`), el callback es tratado como STALE: no escribe
    // nada en los mapas, y libera lo que Android ya le entregó (el
    // `MidiDevice` y los `MidiOutputPort` recién abiertos) para no filtrar
    // el handle nativo. [closeDevice] elimina la entrada de
    // [openGenerationByDevice] al cerrar, así que cualquier open() en vuelo
    // para ese id queda automáticamente invalidado sin necesitar cancelar
    // la operación asíncrona en curso (Android no ofrece esa cancelación).
    private var isShutDown = false // guardado por handlesLock, igual que el resto de este estado
    private val mGenerationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val openGenerationByDevice = mutableMapOf<String, Long>()

    private class MidiReceiverConnection(val receiver: MidiReceiver, val androidPort: android.media.midi.MidiOutputPort)

    override fun isAvailable(): Boolean = midiManager != null

    override fun listDevices(): List<MidiDeviceInfo> {
        val manager = midiManager ?: return emptyList()
        return manager.devices.map { it.toEliNer() }
    }

    override fun startWatching(
        onDeviceConnected: (MidiDeviceInfo) -> Unit,
        onDeviceDisconnected: (MidiDeviceInfo) -> Unit,
    ) {
        val manager = midiManager ?: return
        val callback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: AndroidMidiDeviceInfo) {
                onDeviceConnected(device.toEliNer())
            }

            override fun onDeviceRemoved(device: AndroidMidiDeviceInfo) {
                onDeviceDisconnected(device.toEliNer())
            }
        }
        manager.registerDeviceCallback(callback, handler)
        deviceCallback = callback
    }

    override fun stopWatching() {
        deviceCallback?.let { midiManager?.unregisterDeviceCallback(it) }
        deviceCallback = null
    }

    override fun openInputPorts(device: MidiDeviceInfo, onEvent: (MidiEvent) -> Unit): Boolean {
        val manager = midiManager ?: return false
        val androidInfo = manager.devices.firstOrNull { it.id.toString() == device.id } ?: return false

        // §18 fix: reservamos esta generación ANTES de disparar la llamada
        // asíncrona — es el "token" que el callback (más abajo) usará para
        // reconocerse a sí mismo como vigente o stale cuando finalmente
        // llegue. Si ya estamos apagados, ni siquiera lo intentamos.
        val myGeneration = synchronized(handlesLock) {
            if (isShutDown) return false
            mGenerationCounter.incrementAndGet().also { openGenerationByDevice[device.id] = it }
        }

        // `open` is asynchronous on Android; the actual port-opening work
        // below runs on `handler`'s thread once the device handle is ready.
        // §10: the eventual per-message callback stays lightweight — parsing
        // + a single lambda invocation, nothing blocking, no I/O, no
        // allocation beyond what MidiStreamParser/MidiEvent already do.
        manager.openDevice(
            androidInfo,
            { opened ->
                if (opened == null) return@openDevice
                val connections = device.inputPorts.mapNotNull { portInfo ->
                    val androidOutputPort = opened.openOutputPort(portInfo.portIndex) ?: return@mapNotNull null
                    val parser = MidiStreamParser(portInfo.id)
                    val receiver = object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            // Binder callback thread — must stay lightweight (§10).
                            // `timestamp` here is EliNer's own TimeProvider read
                            // at the moment of delivery, not Android's raw
                            // `timestamp` param — see MidiEvent's doc for why.
                            parser.feed(msg, offset, count, timeProvider.nowNanos(), onEvent)
                        }
                    }
                    androidOutputPort.connect(receiver)
                    MidiReceiverConnection(receiver, androidOutputPort)
                }

                // §18 fix: aceptamos este resultado SOLO si nuestra
                // generación sigue siendo la vigente para este deviceId y
                // el backend no fue apagado mientras open() estaba en
                // vuelo — ambas cosas evaluadas atómicamente bajo el mismo
                // lock que protege closeDevice()/shutdown().
                val accepted = synchronized(handlesLock) {
                    if (isShutDown || openGenerationByDevice[device.id] != myGeneration) {
                        false
                    } else {
                        openDevices[device.id] = opened
                        openReceivers[device.id] = connections
                        true
                    }
                }
                if (!accepted) {
                    // Callback stale (device.id ya fue cerrado, reabierto
                    // con una generación más nueva, o el backend entero se
                    // apagó mientras esta llamada estaba en vuelo). Android
                    // ya nos entregó un MidiDevice/MidiOutputPort real —
                    // cerrarlos aquí es lo que evita el leak/handle
                    // resucitado descrito en §18, sin necesitar cancelar la
                    // operación asíncrona en curso (la plataforma no lo
                    // permite).
                    connections.forEach { it.androidPort.close() }
                    opened.close()
                }
            },
            handler,
        )
        return true
    }

    override fun closeDevice(deviceId: String) {
        // Capture (and remove) everything that needs closing while holding
        // the lock, then do the actual I/O (.close() calls) outside it —
        // .close() on a MidiInputPort/MidiOutputPort/MidiDevice can block
        // briefly on the platform side, and there's no reason to hold
        // handlesLock (which openInputPorts' callback and send() also
        // need) for the duration of that I/O.
        val (connections, device, removedPorts) = synchronized(handlesLock) {
            // §18 fix: invalida cualquier openInputPorts() en vuelo para
            // este mismo deviceId — cuando su callback llegue después de
            // este close, `openGenerationByDevice[deviceId] != myGeneration`
            // (la entrada ya no existe) y será descartado como stale en vez
            // de resucitar el handle que estamos cerrando aquí mismo.
            openGenerationByDevice.remove(deviceId)
            val removedPorts = openOutputPorts.keys
                .filter { it.startsWith("$deviceId:") }
                .mapNotNull { portId -> openOutputPorts.remove(portId) }
            Triple(openReceivers.remove(deviceId), openDevices.remove(deviceId), removedPorts)
        }
        connections?.forEach { it.androidPort.close() }
        device?.close()
        removedPorts.forEach { it.close() }
    }

    override fun send(portId: String, event: MidiEvent): Boolean {
        val port = synchronized(handlesLock) {
            openOutputPorts[portId] ?: run {
                val deviceId = portId.substringBefore(':')
                val device = openDevices[deviceId] ?: return@run null
                val portIndex = portId.substringAfterLast(':').toIntOrNull() ?: return@run null
                val opened = device.openInputPort(portIndex) ?: return@run null
                openOutputPorts[portId] = opened
                opened
            }
        } ?: return false
        val bytes = event.toRawBytes() ?: return false
        return try {
            port.send(bytes, 0, bytes.size)
            true
        } catch (e: java.io.IOException) {
            // §34: a disconnected/misbehaving device must not crash the
            // engine — report failure, don't propagate the exception.
            false
        }
    }

    override fun shutdown() {
        stopWatching()
        // §18 fix: marcado ANTES de leer/cerrar nada — cualquier callback
        // de openDevice() que llegue desde este punto en adelante (incluso
        // si ya estaba en la cola de `handler` cuando quitSafely() corra
        // más abajo, que deja terminar los mensajes ya encolados en vez de
        // descartarlos) se verá a sí mismo como stale al tomar
        // `handlesLock` y se autolimpiará en vez de escribir en los mapas.
        val deviceIds = synchronized(handlesLock) {
            isShutDown = true
            openGenerationByDevice.clear()
            openDevices.keys.toList()
        }
        deviceIds.forEach { closeDevice(it) }
        callbackThread.quitSafely()
    }

    private fun AndroidMidiDeviceInfo.toEliNer(): MidiDeviceInfo {
        val idStr = id.toString()
        val inputPorts = mutableListOf<MidiPortInfo>()
        val outputPorts = mutableListOf<MidiPortInfo>()
        for (port in ports) {
            val portInfo = MidiPortInfo(
                id = "$idStr:${port.type}:${port.portNumber}",
                deviceId = idStr,
                portIndex = port.portNumber,
                direction = if (port.type == AndroidMidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                    MidiPortDirection.INPUT // Android's OUTPUT port = data flows to us = our INPUT.
                } else {
                    MidiPortDirection.OUTPUT
                },
                name = port.name ?: "Port ${port.portNumber}",
            )
            if (portInfo.direction == MidiPortDirection.INPUT) inputPorts.add(portInfo) else outputPorts.add(portInfo)
        }
        return MidiDeviceInfo(
            id = idStr,
            name = properties.getString(AndroidMidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device $idStr",
            manufacturer = properties.getString(AndroidMidiDeviceInfo.PROPERTY_MANUFACTURER),
            transport = when (type) {
                AndroidMidiDeviceInfo.TYPE_USB -> MidiTransport.USB
                AndroidMidiDeviceInfo.TYPE_BLUETOOTH -> MidiTransport.BLUETOOTH
                AndroidMidiDeviceInfo.TYPE_VIRTUAL -> MidiTransport.VIRTUAL
                else -> MidiTransport.UNKNOWN
            },
            state = MidiDeviceState.CONNECTED,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
        )
    }

    /**
     * Converts [MidiEvent] back to raw MIDI bytes for [send]. Returns
     * `null` for event types this phase doesn't support sending (SysEx
     * output, MPE-specific messages) — §11 explicitly scopes output to
     * Note On/Off, CC, Pitch Bend, Program Change, Clock, Transport;
     * everything in that list is handled below.
     */
    private fun MidiEvent.toRawBytes(): ByteArray? {
        val ch = channel ?: 0
        return when (type) {
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.NOTE_ON ->
                byteArrayOf((0x90 or ch).toByte(), data1.toByte(), data2.toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.NOTE_OFF ->
                byteArrayOf((0x80 or ch).toByte(), data1.toByte(), data2.toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.CONTROL_CHANGE ->
                byteArrayOf((0xB0 or ch).toByte(), data1.toByte(), data2.toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.PROGRAM_CHANGE ->
                byteArrayOf((0xC0 or ch).toByte(), data1.toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.PITCH_BEND ->
                byteArrayOf((0xE0 or ch).toByte(), (pitchBendValue and 0x7F).toByte(), ((pitchBendValue shr 7) and 0x7F).toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.CLOCK -> byteArrayOf(0xF8.toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.START -> byteArrayOf(0xFA.toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.STOP -> byteArrayOf(0xFC.toByte())
            com.yeivikas.olyze.eliner.api.midi.MidiEventType.CONTINUE -> byteArrayOf(0xFB.toByte())
            else -> null
        }
    }
}

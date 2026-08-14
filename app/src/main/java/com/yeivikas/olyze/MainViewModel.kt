package com.yeivikas.olyze

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.EliNerMidiApi
import com.yeivikas.olyze.eliner.bridge.EliNerAudioBridge
import com.yeivikas.olyze.eliner.diagnostics.LoggerService
import com.yeivikas.olyze.eliner.events.EventBus
import com.yeivikas.olyze.eliner.modules.midi.MidiFoundationModule
import com.yeivikas.olyze.eliner.services.DeviceCapabilityManager
import com.yeivikas.olyze.eliner.services.PerformanceProfileManager
import com.yeivikas.olyze.eliner.services.ThreadManager
import com.yeivikas.olyze.eliner.services.TimeService
import com.yeivikas.olyze.midi.OlyzeMidiManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.M)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Audio engine — accessed only through the EliNer API contract.
    //    The concrete implementation (native Oboe C++ engine, reached via
    //    EliNerAudioBridge/JNI) is an implementation detail hidden behind it. ──
    val audio: EliNerAudioApi = EliNerAudioBridge.getInstance()

    // ── Device capability → performance profile (Fase 6 §14-15) ──
    // Real integration, not a duplicate detector: reuses the existing
    // Fase 2/2.5 services as-is. DeviceCapabilityManager only needs a
    // Context (never held past this constructor call — it's not retained
    // beyond building the one-shot capabilities snapshot inside
    // PerformanceProfileManager), so this doesn't require pulling in any
    // part of the disconnected eliner.modules.audio/dspfoundation stack
    // (see docs/adr/0010-fase6-frontera-native-dsp.md for why that stack
    // stays untouched).
    private val performanceProfileManager =
        PerformanceProfileManager(DeviceCapabilityManager(application))

    // ── MIDI output (to external hardware/DAW) ──
    val midiManager = OlyzeMidiManager(application)

    // ── MIDI Foundation (input — real infrastructure, MIDI Foundation phase) ──
    // Deliberately separate from `midiManager` above (OUTPUT only, pre-existing
    // — see eliner.modules.midi/README.md for why it wasn't touched: migrating
    // it onto EliNerMidiApi is a follow-up, not part of this phase). This is
    // the actual gap that phase closed: receiving events FROM external MIDI
    // controllers, which nothing in this project did before.
    //
    // Same direct-instantiation precedent as performanceProfileManager above:
    // ThreadManager/EventBus/LoggerService/TimeService are constructed here,
    // standalone — NOT via RuntimeContext/EliNerCore/EliNerRuntime — which
    // avoids pulling in the disconnected Runtime stack, and specifically
    // avoids the known A-1 lifecycle bug (see the hardening-phase report):
    // that bug lives in EliNerRuntime's shutdown() sequencing, not in
    // ThreadManager/EventBus/LoggerService themselves, which are perfectly
    // safe to use standalone. This ViewModel owns `midiThreadManager`
    // exclusively (nothing else in the app constructs a ThreadManager), so
    // calling its shutdown() in onCleared() below is genuinely safe — unlike
    // EliNerRuntime, this class doesn't hand out a shared instance it doesn't
    // own the whole lifecycle of.
    private val midiThreadManager = ThreadManager()
    private val midiEventBus = EventBus()
    private val midiLogger = LoggerService()
    val midi: EliNerMidiApi = MidiFoundationModule.create(
        context = application,
        eventBus = midiEventBus,
        logger = midiLogger,
        taskExecutor = midiThreadManager,
        timeProvider = TimeService(),
    )

    // ── State ──
    private val _bpm            = MutableStateFlow(120)
    val bpm: StateFlow<Int>     = _bpm

    private val _isPlaying      = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isRecording    = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _keyboardVisible = MutableStateFlow(true)
    val keyboardVisible: StateFlow<Boolean> = _keyboardVisible

    private val _midiChannel   = MutableStateFlow(0)
    val midiChannel: StateFlow<Int> = _midiChannel

    private val _velocity      = MutableStateFlow(100)
    val velocity: StateFlow<Int> = _velocity

    private var clockJob: Job? = null

    init {
        // Detect device capabilities and start the native engine with the
        // buffer-size strategy that fits this device, instead of always
        // requesting Oboe's generic default.
        val profile = performanceProfileManager.applyRecommended()
        audio.start(profile)   // Start Oboe C++ engine
        midiManager.initialize() // Init Android MIDI for external devices
        midi.start() // Start MIDI Foundation: device discovery + hot-plug watching for external controllers
    }

    // ── Transport ──────────────────────────────────────────────────────────

    fun togglePlay() {
        _isPlaying.value = !_isPlaying.value
        if (_isPlaying.value) {
            midiManager.sendStart()
            startClock()
        } else {
            stopClock()
            midiManager.sendStop()
            audio.allNotesOff()
        }
    }

    fun toggleRecord() { _isRecording.value = !_isRecording.value }

    fun rewind() {
        _isPlaying.value = false
        stopClock()
        midiManager.sendStop()
        audio.allNotesOff()
    }

    fun setBpm(value: Int) {
        _bpm.value = value.coerceIn(20, 300)
        if (_isPlaying.value) { stopClock(); startClock() }
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (isActive) {
                midiManager.sendClock()
                val ms = (60_000.0 / _bpm.value / 24).toLong().coerceAtLeast(1L)
                delay(ms)
            }
        }
    }

    private fun stopClock() { clockJob?.cancel(); clockJob = null }

    // ── Keyboard ───────────────────────────────────────────────────────────

    fun toggleKeyboard() { _keyboardVisible.value = !_keyboardVisible.value }

    fun noteOn(midiNote: Int) {
        // Fire to both: internal Oboe synth + external MIDI
        audio.noteOn(_midiChannel.value, midiNote, _velocity.value)
        midiManager.sendNoteOn(_midiChannel.value, midiNote, _velocity.value)
    }

    fun noteOff(midiNote: Int) {
        audio.noteOff(_midiChannel.value, midiNote)
        midiManager.sendNoteOff(_midiChannel.value, midiNote)
    }

    // ── FX ─────────────────────────────────────────────────────────────────

    fun setReverbMix(mix: Float)     = audio.setReverbMix(mix)
    fun setDelayMix(mix: Float)      = audio.setDelayMix(mix)
    fun setMasterVolume(vol: Float)  = audio.setMasterVolume(vol)

    override fun onCleared() {
        super.onCleared()
        stopClock()
        audio.allNotesOff()
        audio.stop()
        midiManager.close()
        midi.stop()
        midi.shutdown() // Terminal — onCleared() is genuinely final for this ViewModel; see EliNerMidiApi.shutdown()'s doc for why this is a separate call from stop().
        midiThreadManager.shutdown() // Safe here — this ViewModel exclusively owns this instance; see its construction comment.
    }
}

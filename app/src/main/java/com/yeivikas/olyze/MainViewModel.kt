package com.yeivikas.olyze

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import com.yeivikas.olyze.eliner.bridge.EliNerAudioBridge
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

    // ── MIDI output (to external hardware/DAW) ──
    val midiManager = OlyzeMidiManager(application)

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
        audio.start()          // Start Oboe C++ engine
        midiManager.initialize() // Init Android MIDI for external devices
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
    }
}

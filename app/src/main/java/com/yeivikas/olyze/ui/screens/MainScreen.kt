package com.yeivikas.olyze.ui.screens
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yeivikas.olyze.MainViewModel
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.components.channels.AddChannelButton
import com.yeivikas.olyze.ui.components.channels.AddChannelSheet
import com.yeivikas.olyze.ui.components.transport.AppHeader
import com.yeivikas.olyze.ui.components.piano.PianoKeyboard
import com.yeivikas.olyze.ui.theme.FlDark

@RequiresApi(Build.VERSION_CODES.M)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val bpm             by vm.bpm.collectAsStateWithLifecycle()
    val isPlaying       by vm.isPlaying.collectAsStateWithLifecycle()
    val isRecording     by vm.isRecording.collectAsStateWithLifecycle()
    val metronomeEnabled by vm.metronomeEnabled.collectAsStateWithLifecycle()
    val keyboardVisible by vm.keyboardVisible.collectAsStateWithLifecycle()
    val externalActiveNotes by vm.externalActiveNotes.collectAsStateWithLifecycle()

    // Pulso REAL del motor. Se guarda como State SIN leer .value aquí (sin `by`): MainScreen no
    // se recompone en cada pulso; solo lo leen el destello del botón (en dibujo) y los puntos del
    // panel. collectAsStateWithLifecycle detiene el sondeo del motor cuando la app no está visible.
    val pulse = vm.pulse.collectAsStateWithLifecycle(initialValue = BeatPulse.IDLE)

    // Multi-touch active notes set (on-screen keyboard presses)
    val touchActiveNotes = remember { mutableStateOf(setOf<Int>()) }
    // What the keyboard actually highlights: on-screen touches UNION
    // notes currently held by an external MIDI controller (see
    // MainViewModel.externalActiveNotes) — so a physical keyboard playing
    // through the app is visible on screen too, not just audible.
    val activeNotes = touchActiveNotes.value + externalActiveNotes

    // "Add channel" panel visibility
    var showAddChannel by remember { mutableStateOf(false) }

    // Panel de tempo (se abre tocando el número de BPM del header)
    var showTempo by remember { mutableStateOf(false) }
    BackHandler(enabled = showTempo) { showTempo = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(FlDark)
        ) {
            // ── Header (fixed top) ──
            AppHeader(
                bpm              = bpm,
                isPlaying        = isPlaying,
                isRecording      = isRecording,
                metronomeEnabled = metronomeEnabled,
                pulse            = pulse,
                onPlayToggle     = { vm.togglePlay() },
                onRecToggle      = { vm.toggleRecord() },
                onRewind         = { vm.rewind() },
                onMetronomeToggle = { vm.toggleMetronome() },
                onBpmStep        = { vm.stepBpm(it) },
                onBpmClick       = { showTempo = true },
            )

            // ── Work area + piano keyboard share this region; measuring it lets the
            //    keyboard know exactly how tall it's allowed to grow (i.e. until it
            //    touches AppHeader above) when the user drags/expands it. ──
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val availableHeight = maxHeight

                // Layered (not nested-Column) on purpose: the "+" button is
                // aligned to the center of THIS fixed-size region, so it never
                // moves when the piano keyboard below grows/shrinks — only the
                // keyboard itself (anchored to the bottom) resizes.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FlDark)
                ) {
                    // FL Mobile-style "+" entry point to add channels/instruments —
                    // fixed in place, independent of keyboard size.
                    AddChannelButton(
                        onClick  = { showAddChannel = true },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = (-28).dp)
                    )

                    // ── Piano keyboard — anchored to bottom. Always mounted so its own
                    //    header bar (resize handle + row-mode toggle) stays visible even
                    //    when "hidden" — only the keys area collapses away. ──
                    PianoKeyboard(
                        modifier    = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        activeNotes = activeNotes,
                        expanded    = keyboardVisible,
                        onNoteOn    = { note ->
                            touchActiveNotes.value = touchActiveNotes.value + note
                            vm.noteOn(note)
                        },
                        onNoteOff   = { note ->
                            touchActiveNotes.value = touchActiveNotes.value - note
                            vm.noteOff(note)
                        },
                        // Can grow until it fully replaces the work area above
                        // (touching AppHeader), and shrink down to just its own
                        // header bar.
                        maxHeight  = availableHeight,
                    )
                }
            }
        }

        // ── Blank white panel — opens on top of everything when "+" is tapped ──
        AddChannelSheet(
            visible   = showAddChannel,
            onDismiss = { showAddChannel = false }
        )

        // ── Panel de tempo (popover bajo el control de BPM). Ver TempoPanelHost ──
        TempoPanelHost(
            vm = vm,
            visible = showTempo,
            pulse = pulse,
            onDismiss = { showTempo = false },
        )
    }
}

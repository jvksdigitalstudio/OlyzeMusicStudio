# EliNer — Hardware (Hardware Layer)

**Responsabilidad:** integración con hardware externo, aislando el código
específico de detección/permisos del resto del motor.

**Categorías previstas:**

| Categoría | Detalle |
|---|---|
| **USB Audio** | Interfaces de audio profesionales conectadas por USB |
| **USB MIDI** | Controladores/teclados MIDI por USB |
| **Bluetooth MIDI** | Controladores MIDI inalámbricos |
| **Interfaces de audio** | Abstracción común para USB Audio + audio interno del dispositivo |
| **Micrófonos externos** | Selección de fuente de entrada distinta al mic interno |
| **Controladores MIDI** | Mapeo de controles físicos (knobs, pads, faders) |

**Objetivo:** que `modules/audio` y `modules/midi` solo vean "un
dispositivo disponible", sin importar cómo se conectó (USB, Bluetooth,
interno).

**Futuro uso:** soporte para interfaces de audio profesionales, teclados
MIDI USB/Bluetooth, control surfaces.

**Dependencias:** entrega dispositivos detectados a `modules/audio` y
`modules/midi`. Usa `eliner.diagnostics` para reportar desconexiones/errores.

**Estado actual:** no existe implementación todavía. Carpeta vacía a
propósito — únicamente reserva el espacio en la arquitectura.

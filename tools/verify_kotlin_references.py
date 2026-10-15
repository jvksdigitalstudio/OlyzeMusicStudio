#!/usr/bin/env python3
"""Verificación estática de REFERENCIAS Kotlin del proyecto (ADR 0028, auditoría).

Detecta dos clases de error de compilación que `verify_architecture.py` no ve y
que, sin compilador, solo aparecían al llegar al CI:

  R1  `variable.miembro` (o una cadena `a.b.c`) donde el TIPO declarado de la
      variable no tiene ese miembro. Caso real que motivó esta herramienta:
      `EliNerAudioBridge.getInstance()` devuelve la interfaz `EliNerAudioApi`, y
      el código llamaba a un método que solo existe en la clase concreta →
      «Unresolved reference» en CI.
  R2  Identificador de tipo en MAYÚSCULA usado sin declararse ni importarse
      (p. ej. `Executors` sin `import java.util.concurrent.Executors`).
  R3  Delegado de Compose (`val x by remember…`, `var x by mutableStateOf…`,
      `by collectAsStateWithLifecycle()`…) sin `import androidx.compose.runtime.getValue`
      (y `setValue` si es `var`). El nombre no aparece escrito en el código, así que ni
      el ojo ni un podador de imports lo ven; el compilador dice «State<…> has no method
      getValue(…), so it cannot serve as a delegate». Caso real: ADR 0030.
  R4  Nombre de test con acentos graves que contiene un carácter prohibido en nombres de
      método JVM (punto, punto y coma, corchetes, barra, «<», «>», dos puntos o barra
      invertida): el compilador de Kotlin lo rechaza.

Cómo infiere tipos (y por qué es fiable pero no completa): usa el tipo explícito
de parámetros/propiedades, el tipo de un constructor `Tipo(...)` o el tipo de
retorno declarado de una función conocida `Clase.fn(...)`; resuelve cadenas de
propiedades paso a paso e incluye miembros heredados, de companion y funciones
de extensión del proyecto. Descarta variables con tipos contradictorios en un
mismo archivo (sin análisis de ámbitos) y no opina de tipos de librerías
externas ni de archivos con imports con comodín de librerías externas.

CALIBRACIÓN: sobre el proyecto original de 120 archivos (que compila) da 0
hallazgos; reproduce el error de CI de arriba y detecta errores inyectados
(miembro inexistente, import eliminado). NO sustituye al compilador: no valida
tipos de argumentos, nulabilidad ni genéricos.

Uso:  python3 tools/verify_kotlin_references.py [archivo.kt ...]
"""
import collections
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))


def check_members(root, only=()):
    """R1: miembros inexistentes en el tipo declarado."""
    SRC = [f'{root}/eliner/src/main/java', f'{root}/app/src/main/java', f'{root}/eliner/src/test/java', f'{root}/app/src/test/java']

    def strip(s):
        s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
        s = re.sub(r'"""(.*?)"""', '""', s, flags=re.S)
        s = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s)
        return re.sub(r'//[^\n]*', '', s)

    def block(s, i):          # contenido entre { } que empieza en s[i]=='{'
        d = 0; j = i
        while j < len(s):
            d += (s[j] == '{') - (s[j] == '}')
            j += 1
            if d == 0: break
        return s[i+1:j-1], j

    files = {}
    for r in SRC:
        for d, _, fs in os.walk(r):
            for f in fs:
                if f.endswith('.kt'):
                    p = os.path.join(d, f); files[p] = strip(open(p, encoding='utf-8').read())

    TYPE = re.compile(r'(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|value|fun|inline)\s+)*(class|interface|object)\s+(\w+)\s*(?:<[^({]*?>(?=\s*[(:{]))?\s*(\([^)]*\))?\s*(?::\s*([^{=\n]+))?\s*\{?')
    types = {}   # nombre -> dict(members=set, supers=[...], funs={name:ret}, props={name:type})
    def add_type(name, body, ctor, supers):
        t = types.setdefault(name, dict(members=set(), supers=[], funs={}, props={}))
        # quitar tipos anidados del cuerpo (se indexan aparte) para no mezclar miembros
        flat = body; out = ''; i = 0
        while i < len(flat):
            m = re.compile(r'\b(?:companion\s+object|(?:private\s+|internal\s+|data\s+|enum\s+|sealed\s+|abstract\s+|open\s+)*(?:class|interface|object))\b[^{;\n]*\{').search(flat, i)
            if not m: out += flat[i:]; break
            out += flat[i:m.start()]
            b, j = block(flat, m.end()-1)
            if m.group(0).lstrip().startswith('companion'):
                out += ' ' + b + ' '          # miembros de companion accesibles como Clase.miembro
            i = j
        for m in re.finditer(r'(?:^|\s)(?:val|var)\s+(\w+)\s*(?::\s*([\w.<>?, ]+))?', out):
            t['members'].add(m.group(1))
            if m.group(2): t['props'][m.group(1)] = m.group(2).strip().rstrip('?').split('<')[0].split('.')[-1]
        for m in re.finditer(r'\bfun\s+(?:<[^>]+>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\([^)]*\)\s*(?::\s*([\w.<>?]+))?', out):
            t['members'].add(m.group(1))
            if m.group(2): t['funs'][m.group(1)] = m.group(2).strip().rstrip('?').split('<')[0].split('.')[-1]
        if ctor:
            for m in re.finditer(r'(?:val|var)\s+(\w+)', ctor): t['members'].add(m.group(1))
        for sp in (supers or '').split(','):
            sp = sp.strip().split('(')[0].split('<')[0].strip()
            if sp and re.match(r'^[A-Z]\w*$', sp): t['supers'].append(sp)
        for m in re.finditer(r'(?:^|\s)(\w+)\s*(?:\([^)]*\))?\s*,', out) if False else []: pass
        if body is not None and 'enum' in body[:0]: pass

    HEAD = re.compile(r'(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|value|fun|inline)\s+)*(class|interface|object)\s+(\w+)')
    def skip_ws(s, i):
        while i < len(s) and s[i].isspace(): i += 1
        return i
    def balanced(s, i, o, c):
        d = 0; j = i
        while j < len(s):
            d += (s[j] == o) - (s[j] == c); j += 1
            if d == 0: break
        return s[i:j], j
    for p, s in files.items():
        for m in HEAD.finditer(s):
            kind, name = m.group(1), m.group(2)
            i = skip_ws(s, m.end()); ctor = None
            if i < len(s) and s[i] == '<':
                _, i = balanced(s, i, '<', '>'); i = skip_ws(s, i)
            mc = re.compile(r'(?:(?:private|internal|protected|public)\s+)?constructor\b').match(s, i)
            if mc: i = skip_ws(s, mc.end())
            if i < len(s) and s[i] == '(':
                ctor, i = balanced(s, i, '(', ')'); i = skip_ws(s, i)
            supers = None
            if i < len(s) and s[i] == ':':
                j = i + 1; depth = 0
                while j < len(s) and not (s[j] in '{=\n' and depth == 0):
                    depth += (s[j] in '(<') - (s[j] in ')>'); j += 1
                supers = s[i+1:j]; i = j
                i2 = skip_ws(s, i)
                if i2 < len(s) and s[i2] == '{': i = i2
            body = ''
            if i < len(s) and s[i] == '{': body, _ = block(s, i)
            add_type(name, body, ctor, supers)
    for p, s in files.items():
        # enum entries
        for m in re.finditer(r'enum class (\w+)[^{]*\{([^;}]*)', s):
            for e in re.findall(r'\b([A-Z][A-Z0-9_]*)\b', m.group(2)): types[m.group(1)]['members'].add(e)

    for p, s_ in files.items():
        for m in re.finditer(r'\bfun\s+(?:<[^>]+>\s*)?([A-Z]\w*)(?:<[^>]*>)?\.(\w+)\s*\(', s_):
            if m.group(1) in types: types[m.group(1)]['members'].add(m.group(2))

    def all_members(name, seen=None):
        seen = seen or set()
        if name in seen or name not in types: return set()
        seen.add(name); r = set(types[name]['members'])
        for sp in types[name]['supers']: r |= all_members(sp, seen)
        return r

    COMMON = {'toString','equals','hashCode','javaClass','let','also','apply','run','with','takeIf','takeUnless','to','copy','component1','component2','component3','name','ordinal','values','valueOf','entries','Companion','invoke','compareTo','plus','minus','times','div','rem','inc','dec','unaryMinus','isFinite','isNaN','coerceIn','coerceAtLeast','coerceAtMost','roundToInt','toInt','toFloat','toLong','toDouble','toString','size','value','first','second','collectAsState','asStateFlow','asSharedFlow','launch','cancel','join','isActive','await','map','filter','forEach'}
    bad = []
    targets = [p for p in files if not only or any(p.endswith(o) for o in only)]
    for p in targets:
        s = files[p]
        env = {}   # variable -> tipo
        seen = collections.defaultdict(set)
        external = set(re.findall(r'^import\s+(?:android|androidx|java|javax|kotlin|kotlinx|org)\.[\w.]*\.(\w+)\s*$', open(p, encoding='utf-8').read(), re.M))
        # parámetros y propiedades con tipo explícito
        for m in re.finditer(r'\b(?:val|var)?\s*(\w+)\s*:\s*([A-Z]\w*)(?:<[^>]*>)?\??\s*[,)=\n{]', s):
            seen[m.group(1)].add(m.group(2))
            if m.group(2) in types: env.setdefault(m.group(1), m.group(2))
        # propiedades con inicializador: Tipo(...)  /  Clase.fn(...)
        for m in re.finditer(r'\b(?:val|var)\s+(\w+)\s*(?::\s*[\w<>?., ]+)?\s*=\s*([A-Z]\w*)(\.(\w+))?\s*\(', s):
            var, a, _, fn = m.group(1), m.group(2), m.group(3), m.group(4)
            if fn is None and a in types: env[var] = a
            elif fn and a in types and fn in types[a]['funs']:
                rt = types[a]['funs'][fn]
                if rt in types: env[var] = rt
        # un tipo explícito en la declaración manda sobre el inferido
        for m in re.finditer(r'\b(?:val|var)\s+(\w+)\s*:\s*([A-Z]\w*)', s):
            seen[m.group(1)].add(m.group(2))
            if m.group(2) in types: env[m.group(1)] = m.group(2)
        for v in [v for v, t in seen.items() if len(t) > 1]: env.pop(v, None)
        def member_type(ty, name, seen=None):
            seen = seen or set()
            if ty in seen or ty not in types: return None
            seen.add(ty)
            t = types[ty]
            if name in t['props']: return t['props'][name]
            if name in t['funs']: return t['funs'][name]
            for sp in t['supers']:
                r = member_type(sp, name, seen)
                if r: return r
            return None
        for var, ty0 in env.items():
            if ty0 in external: continue
            for m in re.finditer(r'(?<![\w.])' + re.escape(var) + r'(?=((?:\s*\??\.\s*\w+(?:\([^()]*\))?)+))', s):
                segs = re.findall(r'\.\s*(\w+)', re.sub(r'\([^()]*\)', '', m.group(1)))
                ty = ty0; cur = var
                for seg in segs:
                    if ty is None or ty not in types or ty in external: break
                    if seg not in all_members(ty) and seg not in COMMON:
                        line = s[:m.start()].count('\n') + 1
                        bad.append((os.path.relpath(p, root), line, f'{cur}: {ty} no tiene miembro «{seg}»'))
                        break
                    ty = member_type(ty, seg); cur = f'{cur}.{seg}'
    return sorted(set(bad)), len(targets), len(types)


def check_imports(root, only=()):
    """R2: tipos usados sin import."""
    SRC = [f'{root}/eliner/src/main/java', f'{root}/app/src/main/java', f'{root}/eliner/src/test/java', f'{root}/app/src/test/java']
    def strip(s):
        s = re.sub(r'/\*.*?\*/', '', s, flags=re.S); s = re.sub(r'"""(.*?)"""', '""', s, flags=re.S)
        s = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s); s = re.sub(r'`[^`\n]*`', '``', s); return re.sub(r'//[^\n]*', '', s)
    files = {}
    for r in SRC:
        for d, _, fs in os.walk(r):
            for f in fs:
                if f.endswith('.kt'):
                    p = os.path.join(d, f); files[p] = open(p, encoding='utf-8').read()
    decl = {}   # paquete -> nombres declarados
    DECL = re.compile(r'^\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|value|fun|inline|const)\s+)*(?:class|interface|object|typealias)\s+(\w+)', re.M)
    for p, s in files.items():
        pkg = re.search(r'^package\s+(\S+)', s, re.M).group(1)
        decl.setdefault(pkg, set()).update(DECL.findall(strip(s)))
        decl[pkg].update(re.findall(r'^(?:\s*(?:internal|private|public)\s+)?(?:const\s+)?val\s+([A-Z]\w*)', strip(s), re.M))
        decl[pkg].update(re.findall(r'^\s*(?:(?:internal|private|public|inline)\s+)*fun\s+(?:<[^>]+>\s*)?([A-Z]\w*)\s*\(', strip(s), re.M))
    # Kotlin importa por defecto: kotlin.*, kotlin.annotation.*, kotlin.collections.*, kotlin.comparisons.*, kotlin.io.*, kotlin.ranges.*, kotlin.sequences.*, kotlin.text.*, java.lang.*, kotlin.jvm.*
    DEFAULT = set('''Any Unit Nothing String Int Long Short Byte Char Float Double Boolean Number Array IntArray LongArray FloatArray DoubleArray BooleanArray ByteArray CharArray
    List MutableList Map MutableMap Set MutableSet Collection MutableCollection Iterable MutableIterable Iterator Pair Triple Comparable Throwable Exception RuntimeException IllegalStateException
    IllegalArgumentException UnsupportedOperationException IndexOutOfBoundsException NoSuchElementException ArithmeticException NumberFormatException Error AssertionError Result Lazy Regex Sequence
    CharSequence StringBuilder Thread Runnable Math System Class Process Runtime Object Enum Override Deprecated Suppress JvmStatic JvmField JvmOverloads JvmName JvmInline Volatile Synchronized Throws
    Cloneable Void Character Integer Boolean ArrayDeque ArrayList HashMap HashSet LinkedHashMap LinkedHashSet Annotation Retention Target Strictfp Transient Unit Function Function0 Function1 Function2
    KClass KProperty KFunction Comparator ClassCastException NullPointerException OutOfMemoryError StackOverflowError InterruptedException CharProgression IntRange LongRange ClosedRange
    OptIn RequiresOptIn ExperimentalStdlibApi UByte UShort UInt ULong Suppress Metadata TestOnly'''.split())
    bad = []
    for p, raw in files.items():
        if only and not any(p.endswith(o) for o in only): continue
        s = strip(raw)
        pkg = re.search(r'^package\s+(\S+)', raw, re.M).group(1)
        imported = set(); star = []
        for m in re.finditer(r'^import\s+([\w.]+)(\.\*)?(?:\s+as\s+(\w+))?\s*$', raw, re.M):
            if m.group(2): star.append(m.group(1))
            else: imported.add(m.group(3) or m.group(1).split('.')[-1])
        declared_here = set(decl.get(pkg, set()))
        local = set(re.findall(r'\b(?:class|interface|object|typealias|enum class)\s+(\w+)', s))
        typeparams = set(re.findall(r'<\s*([A-Z]\w*)\b', s)) | set(re.findall(r',\s*([A-Z])\b(?=\s*[>:])', s))
        body = re.sub(r'^(import|package) .*$', '', s, flags=re.M)
        used = set(re.findall(r'(?<![\w.@"])([A-Z][A-Za-z0-9_]*)\b(?=\s*[(.<:?,>)\s={\[\n])', body))
        for name in sorted(used):
            if name in imported or name in declared_here or name in local or name in DEFAULT or name in typeparams: continue
            if re.fullmatch(r'[A-Z][A-Z0-9_]*', name): continue      # CONSTANTES
            if len(name) == 1: continue
            # ¿miembro de un tipo declarado en el proyecto (anidado, companion) o import con comodín?
            if any(not x.startswith('com.yeivikas') for x in star): continue      # no se puede saber qué exporta una librería externa
            if any(name in decl.get(x, set()) for x in star): continue
            bad.append((os.path.relpath(p, root), name, 'SIN IMPORT'))
    return sorted(set(bad)), len([p for p in files if not only or any(p.endswith(o) for o in only)])


_DELEGATE = re.compile(
    r'^\s*(val|var)\s+\w+\s*(?::[^=\n]+)?\s+by\s+'
    r'(remember\w*|mutableState\w*|derivedStateOf|collectAs\w+|animate\w+AsState|produceState)\b', re.M)


def check_delegates(root, only=()):
    """R3: delegados de Compose sin import de getValue/setValue."""
    out = []
    for d, _, fs in os.walk(root):
        if '/build' in d or '/.git' in d:
            continue
        for f in fs:
            path = os.path.join(d, f)
            if not f.endswith('.kt') or (only and path not in only and f not in only):
                continue
            src = open(path, encoding='utf-8').read()
            imports = set(re.findall(r'^import\s+(\S+)', src, re.M))
            wild = 'androidx.compose.runtime.*' in imports
            for m in _DELEGATE.finditer(src):
                needed = ['getValue'] + (['setValue'] if m.group(1) == 'var' else [])
                for n in needed:
                    if not wild and f'androidx.compose.runtime.{n}' not in imports:
                        line = src.count('\n', 0, m.start()) + 1
                        out.append((os.path.relpath(path, root), line, n))
    return sorted(set(out))


_TEST_NAME = re.compile(r'fun `([^`]*)`')
_JVM_FORBIDDEN = re.compile(r'[.;\[\]/<>:\\]')


def check_test_names(root, only=()):
    """R4: nombres de test con caracteres no permitidos en la JVM."""
    out = []
    for d, _, fs in os.walk(root):
        if '/build' in d or '/.git' in d:
            continue
        for f in fs:
            path = os.path.join(d, f)
            if not f.endswith('.kt') or '/src/test/' not in path or (only and path not in only and f not in only):
                continue
            for m in _TEST_NAME.finditer(open(path, encoding='utf-8').read()):
                if _JVM_FORBIDDEN.search(m.group(1)):
                    out.append((os.path.relpath(path, root), m.group(1)))
    return sorted(set(out))


def main():
    only = sys.argv[1:]
    members, n_files, n_types = check_members(ROOT, only)
    imports, _ = check_imports(ROOT, only)
    for f, line, msg in members:
        print(f'R1 {f}:{line}: {msg}')
    for f, name, why in imports:
        print(f'R2 {f}: «{name}» {why}')
    delegates = check_delegates(ROOT, only)
    for f, line, n in delegates:
        print(f'R3 {f}:{line}: delegado de Compose sin «import androidx.compose.runtime.{n}»')
    names = check_test_names(ROOT, only)
    for f, n in names:
        print(f'R4 {f}: nombre de test con carácter prohibido en la JVM: `{n}`')
    if members or imports or delegates or names:
        print(f'FALLO: {len(members)} referencia(s) a miembros inexistentes, {len(imports)} import(s) ausente(s), '
              f'{len(delegates)} delegado(s) sin import (R3) y {len(names)} nombre(s) de test inválido(s) (R4) '
              f'en {n_files} archivos Kotlin.')
        return 1
    print(f'OK: {n_files} archivos Kotlin ({n_types} tipos indexados), sin referencias a miembros inexistentes ni imports ausentes (R1, R2, R3, R4).')
    return 0


if __name__ == '__main__':
    sys.exit(main())

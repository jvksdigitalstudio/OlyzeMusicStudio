#!/usr/bin/env python3
"""Informe de código Kotlin de producción NO alcanzable desde la app (solo lectura).

Responde con datos a la decisión «conectar o retirar» del stack paralelo
(kernel/runtime/foundation, ver ADR 0010 y ADR 0030): qué archivos de
`src/main` no son referenciados, directa ni transitivamente, desde las raíces
reales de ejecución:

  - todo `:app` (src/main), y
  - la composición del motor: `EliNerEngineFactory` y `DefaultEliNerEngine`.

Método: análisis léxico (nombre de tipo declarado ↔ nombre usado como palabra en
otro archivo). Es conservador a favor de «alcanzable» (un nombre repetido cuenta
como referencia), así que NO produce falsos «muertos»; sí puede pasar por vivo
algo que solo comparte nombre. No sustituye a una prueba de compilación.

Uso:  python3 tools/report_unreachable.py            # informe legible
      python3 tools/report_unreachable.py --list     # solo rutas, una por línea
Código de salida: 0 siempre (es un informe, no una barrera de CI).
"""
import collections
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
DECL = re.compile(
    r'^\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|'
    r'annotation|inline|value|fun)\s+)*(?:class|interface|object|typealias)\s+([A-Z]\w*)', re.M)


def strip_comments(s):
    return re.sub(r'//[^\n]*|/\*.*?\*/', '', s, flags=re.S)


def main():
    files = []
    for d, _, fs in os.walk(ROOT):
        if '/build' in d or '/.git' in d:
            continue
        files += [os.path.join(d, f) for f in fs if f.endswith('.kt')]
    main_files = sorted(f for f in files if '/src/main/' in f)
    test_text = ' '.join(open(f, encoding='utf-8').read() for f in files if '/src/test/' in f)

    src, decl = {}, collections.defaultdict(set)
    for f in main_files:
        src[f] = open(f, encoding='utf-8').read()
        for m in DECL.finditer(src[f]):
            decl[m.group(1)].add(f)

    refs = collections.defaultdict(set)
    for f, s in src.items():
        for t in set(re.findall(r'\b[A-Z]\w*\b', strip_comments(s))):
            for g in decl.get(t, ()):
                if g != f:
                    refs[f].add(g)

    roots = [f for f in main_files if '/app/src/' in f or
             f.endswith(('EliNerEngineFactory.kt', 'DefaultEliNerEngine.kt'))]
    seen, stack = set(roots), list(roots)
    while stack:
        for g in refs[stack.pop()]:
            if g not in seen:
                seen.add(g)
                stack.append(g)
    dead = [f for f in main_files if f not in seen]

    rel = lambda f: os.path.relpath(f, ROOT)
    if '--list' in sys.argv:
        print('\n'.join(rel(f) for f in dead))
        return 0

    print(f'{len(main_files)} archivos Kotlin de producción; {len(dead)} no alcanzables desde la app.\n')
    by_pkg = collections.defaultdict(list)
    for f in dead:
        by_pkg[os.path.dirname(rel(f)).split('/olyze/')[-1]].append(f)
    for pkg in sorted(by_pkg):
        print(f'  {pkg}  ({len(by_pkg[pkg])})')
        for f in by_pkg[pkg]:
            tested = any(re.search(r'\b' + t + r'\b', test_text) for t, fs in decl.items() if f in fs)
            print(f'      {os.path.basename(f)}{"   [lo usan tests]" if tested else ""}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

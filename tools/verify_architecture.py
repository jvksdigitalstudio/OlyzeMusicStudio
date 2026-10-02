#!/usr/bin/env python3
"""Verificador estático de arquitectura de Olyze Music Studio (ADR 0027).

NO sustituye a la compilación (kotlinc/Gradle). Es una barrera rápida y
reproducible, ejecutable sin Android SDK, que detecta las clases de error
que una reorganización de paquetes introduce típicamente:

  V1  paquete declarado == directorio del archivo
  V2  todo import de eliner.* resuelve a una declaración existente
  V3  todo tipo de eliner usado por nombre simple es visible (mismo
      paquete, import explícito o declarado en el propio archivo)
  V4  ningún FQN declarado dos veces
  V5  reglas de capas (ver LAYER_RULES)
  V6  el grafo de dependencias entre paquetes no tiene ciclos
  V7  :app solo importa de eliner.api.* y eliner.composition

Uso:  python3 tools/verify_architecture.py [--graph]
Salida: código 0 si todo OK, 1 si hay violaciones.
"""
import collections
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
BASE = 'com.yeivikas.olyze.eliner'
SRC_ROOTS = {
    'eliner-main': f'{ROOT}/eliner/src/main/java',
    'eliner-test': f'{ROOT}/eliner/src/test/java',
    'app-main': f'{ROOT}/app/src/main/java',
}

# paquete (prefijo relativo a BASE) -> prefijos relativos que NO puede importar
# Se expresa como lista de PERMITIDOS: todo lo demás está prohibido.
# '' = raíz de BASE. Un prefijo permite también sus subpaquetes.
LAYER_RULES = {
    'api':             ['api'],
    'core':            ['core'],
    'contracts':       ['contracts'],
    'configuration':   ['configuration'],
    'resources':       ['resources'],
    'diagnostics':     ['diagnostics', 'core'],
    'events':          ['events', 'api', 'core'],
    'services':        ['services', 'api', 'core'],
    'runtime':         ['runtime', 'api', 'core', 'events', 'diagnostics',
                        'services', 'configuration', 'resources'],
    'audiofoundation': ['contracts', 'audiofoundation', 'api', 'core', 'events', 'diagnostics',
                        'services', 'configuration', 'resources', 'runtime'],
    'dspfoundation':   ['contracts', 'dspfoundation', 'api', 'core', 'events', 'diagnostics',
                        'services', 'configuration', 'resources', 'runtime'],
    'modules.audio':   ['modules.audio', 'api', 'contracts', 'core', 'events', 'diagnostics',
                        'services', 'configuration', 'resources', 'runtime',
                        'audiofoundation'],  # NO dspfoundation: módulos hermanos solo vía contracts
    'modules.midi':    ['modules.midi', 'api', 'core', 'events', 'diagnostics', 'services'],
    'bridge':          ['bridge', 'api', 'services'],
    'composition':     [''],  # raíz de composición: puede ver todo
}
APP_ALLOWED = ['api', 'composition']

DECL = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|data|sealed|enum|abstract|open|annotation|value|fun|const|inline)\s+)*'
    r'(?:class|interface|object|typealias)\s+(\w+)', re.M)
TOPFN = re.compile(r'^(?:(?:public|internal|inline)\s+)*(?:fun(?!\s+interface\b)|val|const\s+val)\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?(\w+)', re.M)


def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)


def strip_strings(s):
    return re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s)


def rel(pkg):
    """paquete completo -> relativo a BASE ('' para la raíz)."""
    if pkg == BASE:
        return ''
    if pkg.startswith(BASE + '.'):
        return pkg[len(BASE) + 1:]
    return None


def layer_of(relpkg):
    best = None
    for k in LAYER_RULES:
        if relpkg == k or relpkg.startswith(k + '.'):
            if best is None or len(k) > len(best):
                best = k
    return best


def allowed(relpkg_from, relpkg_to, rules_key):
    for a in LAYER_RULES[rules_key]:
        if a == '' or relpkg_to == a or relpkg_to.startswith(a + '.'):
            return True
    return False


def main():
    errors = []
    files = {}  # path -> dict
    for tag, root in SRC_ROOTS.items():
        for d, _, fs in os.walk(root):
            for f in fs:
                if not f.endswith('.kt'):
                    continue
                p = os.path.join(d, f)
                s = open(p, encoding='utf-8').read()
                m = re.search(r'^package\s+(\S+)', s, re.M)
                if not m:
                    errors.append(f'{p}: sin declaración package')
                    continue
                pkg = m.group(1)
                exp = os.path.relpath(d, root).replace(os.sep, '.')
                if pkg != exp:  # V1
                    errors.append(f'V1 {os.path.relpath(p, ROOT)}: package {pkg} != directorio {exp}')
                body = strip_strings(strip_comments(s))
                files[p] = dict(tag=tag, pkg=pkg, src=s, body=body)

    decl = collections.defaultdict(set)       # pkg -> nombres (tipos+funs)
    types_by_name = collections.defaultdict(set)
    fq_seen = collections.defaultdict(list)
    for p, fi in files.items():
        types = set(DECL.findall(fi['body']))
        funs = set(TOPFN.findall(fi['body']))
        fi['types'], fi['funs'] = types, funs
        for n in types | funs:
            decl[fi['pkg']].add(n)
        for n in types:
            types_by_name[n].add(fi['pkg'])
            fq_seen[f"{fi['pkg']}.{n}"].append(p)
    for fq, ps in fq_seen.items():  # V4
        if len(ps) > 1:
            errors.append(f'V4 {fq} declarado en {len(ps)} archivos: {[os.path.relpath(x, ROOT) for x in ps]}')

    graph = collections.defaultdict(set)
    for p, fi in files.items():
        r = os.path.relpath(p, ROOT)
        imports = re.findall(r'^import\s+([\w.]+)(?:\.\*)?(?:\s+as\s+\w+)?\s*$', fi['src'], re.M)
        wildcard = re.findall(r'^import\s+([\w.]+)\.\*\s*$', fi['src'], re.M)
        explicit_names = set()
        for imp in imports:
            explicit_names.add(imp.rsplit('.', 1)[-1])
            if not imp.startswith(BASE + '.'):
                continue
            parts = imp.split('.')
            ok = False
            for i in range(len(parts) - 1, 0, -1):  # V2 (permite clases anidadas)
                pk, nm = '.'.join(parts[:i]), parts[i]
                if nm in decl.get(pk, ()):
                    ok = True
                    target_pkg = pk
                    break
            if not ok:
                errors.append(f'V2 {r}: import sin resolver {imp}')
                continue
            rp = rel(target_pkg)
            rf = rel(fi['pkg'])
            if fi['tag'] == 'app-main':
                if not any(rp == a or rp.startswith(a + '.') for a in APP_ALLOWED):  # V7
                    errors.append(f'V7 {r}: :app importa interno {imp}')
            elif fi['tag'] == 'eliner-main' and rf is not None:
                lk = layer_of(rf)
                if lk is None:
                    errors.append(f'V5 {r}: paquete {rf!r} sin regla de capa')
                elif not allowed(rf, rp, lk):
                    errors.append(f'V5 {r}: {rf} -> {rp} prohibido por capa ({imp})')
                if rf != rp:
                    graph[rf].add(rp)
        for w in wildcard:
            if w.startswith(BASE):
                errors.append(f'V2 {r}: import comodín no permitido: {w}.*')
        # V3 tipos usados sin ser visibles
        if fi['tag'] != 'app-main' or True:
            body = re.sub(r'^(import|package) .*$', '', fi['body'], flags=re.M)
            for name, pkgs in types_by_name.items():
                if name in fi['types'] or name in explicit_names:
                    continue
                if fi['pkg'] in pkgs:
                    continue
                if not re.search(r'(?<![\w.])' + re.escape(name) + r'(?![\w])', body):
                    continue
                if len(pkgs) == 1 and next(iter(pkgs)).startswith(BASE):
                    # usado sin import; solo es error si no hay otro símbolo homónimo externo
                    # (p.ej. android.content.res.Configuration) importado — ya cubierto por explicit_names.
                    # También se admite uso calificado `pkg.Name`, excluido por el lookbehind.
                    if fi['tag'] == 'eliner-test' or fi['tag'] == 'eliner-main' or fi['tag'] == 'app-main':
                        errors.append(f"V3 {r}: usa {name} sin import (vive en {next(iter(pkgs))})")

    # V6 ciclos
    def find_cycle():
        color = {}
        stack = []

        def dfs(n):
            color[n] = 1
            stack.append(n)
            for m in sorted(graph.get(n, ())):
                if color.get(m, 0) == 0:
                    c = dfs(m)
                    if c:
                        return c
                elif color[m] == 1:
                    return stack[stack.index(m):] + [m]
            stack.pop()
            color[n] = 2
            return None
        for n in sorted(graph):
            if color.get(n, 0) == 0:
                c = dfs(n)
                if c:
                    return c
        return None
    cyc = find_cycle()
    if cyc:
        errors.append('V6 ciclo de paquetes: ' + ' -> '.join(cyc))

    if '--graph' in sys.argv:
        print('Grafo de dependencias entre paquetes (eliner main):')
        for k in sorted(graph):
            print(f'  {k or "(root)":18s} -> {", ".join(sorted(x or "(root)" for x in graph[k]))}')
    n_files = len(files)
    if errors:
        print(f'FALLO: {len(errors)} violación(es) en {n_files} archivos Kotlin\n')
        for e in sorted(set(errors)):
            print(' ', e)
        return 1
    print(f'OK: {n_files} archivos Kotlin verificados (V1–V7), sin violaciones.')
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Package notices from resolved runtime artifacts and their Maven declarations.

Run the Gradle runtimeLicenseInventory task first. Local artifact paths remain
in the ignored build directory; published notices contain only coordinates,
filenames and primary-source Maven URLs.
"""
import argparse
import io
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def declaration(group, name, version, cache, depth=0):
    if depth > 5:
        raise RuntimeError('Maven license inheritance exceeded the bound')
    base = 'https://dl.google.com/dl/android/maven2' if group.startswith('androidx.') else 'https://repo.maven.apache.org/maven2'
    url = f'{base}/{group.replace(".", "/")}/{name}/{version}/{name}-{version}.pom'
    files = list((cache / group / name / version).glob('*/*.pom'))
    data = files[0].read_bytes() if files else urllib.request.urlopen(url, timeout=20).read(2_000_000)
    root = ET.fromstring(data)
    licenses = [(item.findtext('m:name', '', NS), item.findtext('m:url', '', NS)) for item in root.findall('m:licenses/m:license', NS)]
    if not licenses:
        parent = root.find('m:parent', NS)
        if parent is None:
            raise RuntimeError(f'No license declaration for {group}:{name}:{version}')
        return declaration(*(parent.findtext(f'm:{key}', '', NS) for key in ['groupId', 'artifactId', 'version']), cache, depth + 1)
    return licenses, url


def embedded_notices(archive):
    notices = []
    for name in archive.namelist():
        if not name.endswith('/') and any(word in name.lower() for word in ['license', 'notice', 'copying', 'copyright', 'al2.0', 'lgpl2.1']):
            notices.append((name, archive.read(name).decode('utf-8')))
        elif name == 'classes.jar':
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as classes:
                notices.extend((f'classes.jar/{path}', text) for path, text in embedded_notices(classes))
    return notices


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', default='app/build/runtime-artifacts.tsv')
    parser.add_argument('--cache', default=str(Path.home() / '.gradle/caches/modules-2/files-2.1'))
    args = parser.parse_args()
    cache = Path(args.cache)
    records, notices = [], []
    for line in Path(args.inventory).read_text().splitlines():
        coordinate, filename = line.split('\t', 1)
        group, name, version = coordinate.split(':')
        licenses, url = declaration(group, name, version, cache)
        # This snapshot resolves Apache-2.0 runtime artifacts. Any later license
        # change requires explicit handling, rather than silently applying it.
        if any('apache' not in (label + address).lower() or '2.0' not in label + address for label, address in licenses):
            raise RuntimeError(f'License needs review: {coordinate}: {licenses}')
        records.append(f'{coordinate} ({Path(filename).name})\nDeclared license: {licenses}\nDeclaration: {url}\n')
        with zipfile.ZipFile(filename) as archive:
            notices.extend(f'{coordinate} — {path}\n{text}' for path, text in embedded_notices(archive))
    destination = Path('app/src/main/assets/licenses/runtime-dependencies.txt')
    destination.write_text('Runtime artifact notices\n\n' + '\n'.join(records) + '\nEmbedded distribution notices\n\n' + '\n\n'.join(notices))
    Path('docs/RUNTIME_DEPENDENCIES.txt').write_text('\n'.join(records))
    print(f'Packaged declarations for {len(records)} resolved artifacts and {len(notices)} embedded notices')


if __name__ == '__main__':
    main()

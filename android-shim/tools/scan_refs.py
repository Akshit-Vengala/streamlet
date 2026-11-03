#!/usr/bin/env python3
"""Member-level reference scanner (javap based) used to drive/verify the shim.
usage: scan_refs.py out.json libclasses.txt plugin.jar...
libclasses.txt: one internal class name per line (classes available in `library` jar).
Output: JSON {classes:{name:{iface,enum}}, valid:[...], members:[[kind,owner,name,desc,static]]}
Only refs to android.*, androidx.*, and com/lagradost classes absent from library + plugin jar."""
import sys, subprocess, zipfile, re, json

out = sys.argv[1]
lib = set(open(sys.argv[2]).read().split())
jars = sys.argv[3:]
members = {}
classes = {}


def want(c, own):
    if c in own or c in lib:
        return False
    return c.startswith(('android/', 'androidx/', 'com/lagradost/', 'org/xmlpull/', 'com/google/android/material/'))


def cls(n):
    return classes.setdefault(n, {'iface': False, 'enum': False})


tok = re.compile(r'\b((?:android|androidx|com\.lagradost|org\.xmlpull|com\.google\.android\.material)[./][\w./$]*[\w$])')
for j in jars:
    own = {n[:-6] for n in zipfile.ZipFile(j).namelist() if n.endswith('.class')}
    names = [n.replace('/', '.') for n in own]
    for i in range(0, len(names), 200):
        p = subprocess.run(['javap', '-c', '-p', '-cp', j] + names[i:i + 200],
                           capture_output=True, text=True, errors='replace')
        if p.returncode != 0:
            print('javap failed for batch', i, j, p.stderr[:200], file=sys.stderr)
        r = p.stdout
        for line in r.splitlines():
            ls = line.strip()
            m = re.match(r'(?:\w+ )*(?:class|interface) \S+(?: extends ([\w.$]+))?(?: implements ([\w.$,\s]+))? \{', ls)
            if m and not ls.endswith(');'):
                if m.group(1):
                    s = m.group(1).replace('.', '/')
                    if want(s, own):
                        cls(s)
                for x in (m.group(2) or '').split(','):
                    x = x.strip().replace('.', '/')
                    if x and want(x, own):
                        cls(x)['iface'] = True
            m = re.match(r'\d+: (invoke\w+|getstatic|putstatic|getfield|putfield)\s+#\d+\s+// (InterfaceMethod|Method|Field) ([^.\s]+)\.(\S+?):(\S+)$', ls)
            if m:
                op, kind, owner, name, desc = m.groups()
                if owner.startswith('"['):
                    continue
                if not want(owner, own):
                    continue
                if kind == 'InterfaceMethod':
                    cls(owner)['iface'] = True
                if name == 'values' and op == 'invokestatic' and desc.startswith('()['):
                    cls(owner)['enum'] = True
                members[(kind, owner, name.strip('"'), desc)] = op in ('invokestatic', 'getstatic', 'putstatic')
                cls(owner)
                for d in re.findall(r'L([\w/$]+);', desc):
                    if want(d, own):
                        cls(d)
            else:
                for t in tok.findall(ls):
                    t = t.replace('.', '/')
                    if want(t, own):
                        cls(t)
valid = set(o for (_, o, _, _) in members)
for (_, _, _, d) in members:
    for x in re.findall(r'L([\w/$]+);', d):
        if x in classes:
            valid.add(x)
for n, v in list(classes.items()):
    if v['iface'] or v['enum']:
        valid.add(n)
json.dump({'classes': classes, 'valid': sorted(valid),
           'members': [[k, o, n, d, s] for (k, o, n, d), s in sorted(members.items())]},
          open(out, 'w'), indent=0)
print(len(valid), 'classes', len(members), 'members')

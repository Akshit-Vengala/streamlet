import sys, zipfile, re, collections, pathlib
jars = pathlib.Path(sys.argv[1]).glob("*.jar")
pat = re.compile(rb"(?:Landroid|Landroidx|android|androidx|com/lagradost/cloudstream3/(?:ui|utils|plugins|mvvm|network|syncproviders|services|CommonActivity|AcraApplication|MainActivity)|com/lagradost/cloudstream3/[A-Za-z]+Activity)[A-Za-z0-9_/$]*")
total = collections.defaultdict(set)
for j in jars:
    with zipfile.ZipFile(j) as z:
        for n in z.namelist():
            if n.endswith(".class"):
                for m in pat.findall(z.read(n)):
                    s = m.decode().lstrip("L")
                    if s.startswith(("android","com/lagradost")): total[s].add(j.stem)
rows = sorted(total.items(), key=lambda kv: (-len(kv[1]), kv[0]))
for k, v in rows[:70]: print(f"{len(v)}  {k}  [{','.join(sorted(v))}]")
print("distinct:", len(rows))

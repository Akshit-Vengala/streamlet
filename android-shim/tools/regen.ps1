# Regenerate android-shim/src/generated from the plugin jars' member-level references.
# usage (from repo root): powershell android-shim/tools/regen.ps1 -Jars D:\path\to\jars -Libs D:\path\to\libs
param(
    [string]$Jars = "D:\projects\cloudstream-desktop\spike\jar",
    [string]$Libs = "D:\projects\cloudstream-desktop\spike\libs"
)
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$shim = Split-Path -Parent $here
$work = Join-Path $here "work"
New-Item -ItemType Directory -Force $work | Out-Null
# classes already provided by library + its runtime deps (not the shim's job)
$all = foreach ($j in Get-ChildItem "$Libs\*.jar") { & jar tf $j.FullName | Where-Object { $_ -like '*.class' } | ForEach-Object { $_.Substring(0, $_.Length - 6) } }
Set-Content "$work\libclasses.txt" $all
Push-Location $Jars
python "$here\scan_refs.py" "$work\refs.json" "$work\libclasses.txt" (Get-ChildItem *.jar).Name
Pop-Location
if (Test-Path "$shim\src\generated") { Remove-Item -Recurse -Force "$shim\src\generated" }
python "$here\gen_stubs.py" "$work\refs.json" "$shim\src\generated\java" "$shim\src\main\java" "$shim\src\main\kotlin"

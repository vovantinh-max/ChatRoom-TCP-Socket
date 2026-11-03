$ErrorActionPreference = 'Stop'
$metadata = Join-Path $env:windir 'System32\WinMetadata'
$runtime = [System.Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()
$gac = Join-Path $env:windir 'Microsoft.NET\assembly\GAC_MSIL'
$references = @('System.Runtime.WindowsRuntime.dll')
$references += 'System.Runtime','System.Runtime.InteropServices.WindowsRuntime' | ForEach-Object {
    (Get-ChildItem (Join-Path $gac $_) -Recurse -Filter "$_.dll" | Select-Object -First 1).FullName
}
$references += 'Foundation','Graphics','Media','Storage' | ForEach-Object { Join-Path $metadata "Windows.$_.winmd" }
$assembly = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../build/TcpChatCamera.dll'))
try {
    if ($args -contains '-Build') {
        $source = Join-Path $PSScriptRoot 'TcpChatCamera.cs'
        if ((Test-Path -LiteralPath $assembly) -and
            (Get-Item -LiteralPath $assembly).LastWriteTimeUtc -ge (Get-Item -LiteralPath $source).LastWriteTimeUtc) { exit 0 }
        $options = @('/nologo','/target:library',"/out:$assembly")
        $options += $references | ForEach-Object { "/reference:$_" }
        & (Join-Path $runtime 'csc.exe') @options $source
        exit $LASTEXITCODE
    }
    $null = [Reflection.Assembly]::Load([IO.File]::ReadAllBytes($assembly))
    [TcpChatCamera]::Run([int]$args[0])
} catch {
    [Console]::Error.WriteLine($_.ToString())
    exit 1
}

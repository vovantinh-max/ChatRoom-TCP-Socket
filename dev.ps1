param(
    [ValidateSet('build', 'test', 'server', 'client')]
    [string]$Action = 'build'
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force 'build/classes' | Out-Null
    $sources = @(Get-ChildItem client,server,shared,test -Recurse -Filter *.java | ForEach-Object FullName)
    & javac -encoding UTF-8 -d build/classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed. Install JDK 23 or newer.' }
    if ($env:OS -eq 'Windows_NT') {
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'client/media/video/windows-camera.ps1' -Build
        if ($LASTEXITCODE -ne 0) { throw 'Windows camera helper compilation failed.' }
    }
    $classpath = 'build/classes;lib/*'
    switch ($Action) {
        'test' {
            $suites = @(
                'UiThemeRenderingTest',
                'AuthenticationAndLifecycleIntegrationTest',
                'FriendshipWorkflowIntegrationTest',
                'CoreMessagingReliabilityIntegrationTest',
                'Phase1ProtocolIntegrationTest',
                'Phase2DatabaseAndFriendsIntegrationTest',
                'Phase3RichMessagingIntegrationTest',
                'Phase4To7MediaAndFileIntegrationTest',
                'Phase8To10GroupsAndSystemStressTest'
            )
            $runId = [Guid]::NewGuid().ToString('N')
            foreach ($suite in $suites) {
                # Each JVM uses its own database, never the user's chat history.
                $database = "build/test-$runId-$suite.db"
                $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
                $probe.Start()
                $testPort = $probe.LocalEndpoint.Port
                $probe.Stop()
                & java "-Dchat.db.path=$database" "-Dchat.port=$testPort" -cp $classpath "test.$suite"
                if ($LASTEXITCODE -ne 0) { throw "Test suite failed: $suite" }
            }
        }
        'server' { & java -cp $classpath server.ChatServer }
        'client' { & java -cp $classpath client.ui.LoginFrame }
    }
} finally {
    Pop-Location
}

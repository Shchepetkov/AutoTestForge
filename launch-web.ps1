[CmdletBinding()]
param(
    [string[]]$ProjectRoot = @(),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenHome = $env:MAVEN_HOME,
    [ValidateRange(1, 65535)][int]$Port = 8080,
    [switch]$NoBuild,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$repository = $PSScriptRoot
$originalPath = $env:PATH
$originalJavaHome = $env:JAVA_HOME

function Read-NativeVersion([string]$Executable) {
    # Windows PowerShell 5 represents java -version's stderr as ErrorRecords.
    $ErrorActionPreference = 'Continue'
    $output = (& $Executable -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "Cannot run $Executable. $output" }
    return $output
}

try {
    if ($JavaHome) {
        $java = Join-Path $JavaHome 'bin/java.exe'
    } else {
        $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
        if (-not $javaCommand) { throw 'JDK 17+ is required. Set JAVA_HOME or pass -JavaHome "C:\path\to\jdk-17".' }
        $java = $javaCommand.Source
        $JavaHome = Split-Path (Split-Path $java -Parent) -Parent
    }
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java was not found at '$java'. Set JAVA_HOME to an installed JDK 17+ directory." }
    $javaVersion = Read-NativeVersion $java
    if ($javaVersion -notmatch 'version\s+"(?<major>\d+)(?:\.(?<minor>\d+))?') { throw "Cannot determine Java version: $javaVersion" }
    $javaMajor = [int]$Matches['major']
    if ($javaMajor -eq 1) { $javaMajor = [int]$Matches['minor'] }
    if ($javaMajor -lt 17) { throw "Java $javaMajor is too old. Install JDK 17+ and set JAVA_HOME or pass -JavaHome." }
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe') -PathType Leaf)) { throw "'$JavaHome' must contain a full JDK (javac.exe), not only a Java runtime. Pass -JavaHome to the JDK directory." }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$(Join-Path $JavaHome 'bin');$originalPath"

    if (-not $MavenHome) { $MavenHome = $env:M2_HOME }
    if ($MavenHome) {
        $maven = Join-Path $MavenHome 'bin/mvn.cmd'
    } else {
        $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($mavenCommand) { $maven = $mavenCommand.Source } else { $maven = $null }
    }
    if ($maven -and -not (Test-Path -LiteralPath $maven -PathType Leaf)) { throw "Maven was not found at '$maven'. Pass -MavenHome to the directory containing bin\mvn.cmd." }
    if ($maven) {
        $mavenVersion = Read-NativeVersion $maven
        if ($mavenVersion -notmatch 'Apache Maven (?<major>\d+)\.(?<minor>\d+)') { throw "Cannot determine Maven version: $mavenVersion" }
        if ([int]$Matches['major'] -lt 3 -or ([int]$Matches['major'] -eq 3 -and [int]$Matches['minor'] -lt 9)) { throw 'Maven 3.9+ is required.' }
        # Test validation starts Maven as a child process, so it must inherit this PATH.
        $env:PATH = "$(Split-Path $maven -Parent);$env:PATH"
    } elseif (-not $NoBuild) {
        throw 'Maven 3.9+ is required for the first build. Install Maven, add bin to PATH, or pass -MavenHome "C:\path\to\apache-maven". An IntelliJ IDEA installation may include plugins\maven\lib\maven3.'
    } else {
        Write-Warning 'Maven is unavailable. The packaged site can run, but Maven test validation requires Maven or a working project wrapper.'
    }

    $roots = @($repository)
    foreach ($directory in $ProjectRoot) {
        $resolved = (Resolve-Path -LiteralPath $directory).ProviderPath
        if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { throw "Project root is not a directory: $resolved" }
        if ($resolved.Contains(',')) { throw 'Project roots containing commas are not supported. Choose a parent directory without commas.' }
        $roots += $resolved
    }
    $allowedRoots = if ($env:ATF_ALLOWED_ROOTS -and $ProjectRoot.Count -eq 0) { $env:ATF_ALLOWED_ROOTS } else { ($roots | Select-Object -Unique) -join ',' }
    Write-Host "Java: $java"
    if ($maven) { Write-Host "Maven: $maven" }
    Write-Host "Allowed project roots: $allowedRoots"
    if ($Check) { Write-Host 'Prerequisites OK. No build or server was started.'; exit 0 }

    Push-Location -LiteralPath $repository
    try {
        if (-not $NoBuild) {
            & $maven -B -pl atf-web -am package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'Build failed. Check the Maven output above (company mirror/proxy settings belong in ~/.m2/settings.xml).' }
        }
        $jar = Join-Path $repository 'atf-web/target/atf-web-0.1.0.jar'
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Web JAR is missing. Run this script once without -NoBuild.' }
        Write-Host "Open http://localhost:$Port in your browser. Keep this window open; Ctrl+C stops the site."
        & $java -jar $jar '--server.address=127.0.0.1' "--server.port=$Port" "--atf.workspace.allowed-roots=$allowedRoots"
        if ($LASTEXITCODE -ne 0) { throw "The web server stopped with exit code $LASTEXITCODE. Check the output above." }
    } finally {
        Pop-Location
    }
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
} finally {
    $env:PATH = $originalPath
    $env:JAVA_HOME = $originalJavaHome
}

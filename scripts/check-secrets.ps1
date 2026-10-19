$ErrorActionPreference = 'Stop'

$tracked = @(git ls-files)
$forbiddenNames = @(
    'google-services.json',
    'firebase.properties',
    '.firebaserc',
    'local.properties'
)
$forbiddenExtensions = @('.jks', '.keystore', '.apk', '.aab', '.apks', '.idsig', '.class')
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($path in $tracked) {
    $name = [System.IO.Path]::GetFileName($path)
    $extension = [System.IO.Path]::GetExtension($path).ToLowerInvariant()
    if ($forbiddenNames -contains $name -or $forbiddenExtensions -contains $extension) {
        $violations.Add($path)
    }
}

$secretPatterns = @(
    'AIza[0-9A-Za-z_-]{35}',
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
)
foreach ($pattern in $secretPatterns) {
    $matches = @(git grep --cached -I -l -E $pattern -- 2>$null)
    foreach ($path in $matches) { $violations.Add($path) }
}

if ($violations.Count -gt 0) {
    Write-Error "Security check failed. Remove these tracked credential or generated files:`n$($violations | Sort-Object -Unique | ForEach-Object { " - $_" } | Out-String)"
}

Write-Output 'SECURITY_CHECK_PASSED'

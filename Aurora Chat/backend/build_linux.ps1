$env:GOOS='linux'
$env:GOARCH='amd64'
$env:CGO_ENABLED='0'
Set-Location $PSScriptRoot
& "go" @("build", "-v", "-ldflags=-s -w", "-o", "aurora-server", ".")

@echo off
cd /d "%~dp0"
set GOOS=linux
set GOARCH=amd64
set CGO_ENABLED=0
go build -ldflags="-s -w" -o aurora-server .

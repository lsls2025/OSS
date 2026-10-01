@echo off
chcp 65001 >nul
title Aurora Chat 后端服务

echo ========================================
echo    Aurora Chat 后端服务启动脚本
echo ========================================
echo.

:: 检查 Go 是否安装
go version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Go 环境！
    echo 请先安装 Go：https://go.dev/dl/
    echo 安装完成后请重新运行此脚本
    pause
    exit /b 1
)

echo [信息] Go 环境检测通过
echo.

:: 进入脚本所在目录
cd /d "%~dp0"

:: 下载依赖（首次运行需要）
echo [信息] 正在检查依赖...
go mod tidy
if %errorlevel% neq 0 (
    echo [错误] 依赖下载失败，请检查网络连接
    pause
    exit /b 1
)

echo.
echo [信息] 依赖检查完成
echo.
echo ========================================
echo  服务器地址: http://localhost:5004
echo  按 Ctrl+C 停止服务器
echo ========================================
echo.

:: 启动服务器
go run .
if %errorlevel% neq 0 (
    echo.
    echo [错误] 服务器启动失败！
    pause
    exit /b 1
)

pause

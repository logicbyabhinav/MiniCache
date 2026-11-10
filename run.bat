@echo off
setlocal
echo ========================================================
echo   Starting MiniCache Daemon (Port: 6379)
echo   Built from first principles in Java 21
echo ========================================================
if not exist "bin" mkdir bin
javac -d "bin" -sourcepath "src" src/com/minicache/*.java src/com/minicache/engine/*.java src/com/minicache/command/*.java src/com/minicache/server/*.java
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed.
    exit /b %ERRORLEVEL%
)
java -cp "bin" com.minicache.MiniCache %*

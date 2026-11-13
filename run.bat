@echo off
REM Compile and start the Auto Workshop website (needs Java 21+)
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\com\autoshop\*.java
if errorlevel 1 (
  echo Compilation failed.
  pause
  exit /b 1
)
echo Starting Auto Workshop at http://localhost:8080  (press Ctrl+C to stop)
start "" http://localhost:8080
java -cp out com.autoshop.AutoShopServer

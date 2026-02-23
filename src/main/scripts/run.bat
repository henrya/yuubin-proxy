@echo off
REM Wrapper for Windows

SET BASE_DIR=%~dp0..
SET JAR_FILE=%BASE_DIR%\lib\${project.build.finalName}.jar
SET DEFAULT_CONFIG=%BASE_DIR%\conf\application.yml

IF NOT EXIST "%JAR_FILE%" (
    echo Error: JAR file not found at %JAR_FILE%
    exit /b 1
)

SET HAS_CONFIG=false
FOR %%A IN (%*) DO (
    IF "%%A"=="-c" SET HAS_CONFIG=true
    IF "%%A"=="--config" SET HAS_CONFIG=true
)

IF "%HAS_CONFIG%"=="false" (
    java -jar "%JAR_FILE%" --config "%DEFAULT_CONFIG%" %*
) ELSE (
    java -jar "%JAR_FILE%" %*
)

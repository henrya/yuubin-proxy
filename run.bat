@echo off
REM Wrapper for Windows. Supports all CLI options (e.g., run.bat --help)
SET JAR_FILE=target\yuubin-proxy-1.0.0.jar

SET IS_HELP=false
FOR %%A IN (%*) DO (
    IF "%%A"=="-h" SET IS_HELP=true
    IF "%%A"=="--help" SET IS_HELP=true
    IF "%%A"=="-V" SET IS_HELP=true
    IF "%%A"=="--version" SET IS_HELP=true
)

IF NOT EXIST "%JAR_FILE%" (
    IF "%IS_HELP%"=="false" (
        echo JAR file not found. Building with Maven...
        call mvn clean package -DskipTests
    )
)

IF EXIST "%JAR_FILE%" (
    java -jar "%JAR_FILE%" %*
) ELSE (
    IF "%IS_HELP%"=="true" (
        echo Warning: JAR file not found. Building with Maven for help/version...
        call mvn clean package -DskipTests
        java -jar "%JAR_FILE%" %*
    ) ELSE (
        echo Error: JAR file not found.
        exit /b 1
    )
)

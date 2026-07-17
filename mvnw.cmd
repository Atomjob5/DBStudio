@echo off
setlocal
set MVN_VERSION=3.9.11
if "%MAVEN_USER_HOME%"=="" set MAVEN_USER_HOME=%USERPROFILE%\.m2
set WRAPPER_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MVN_VERSION%
set MVN_BIN=%WRAPPER_HOME%\apache-maven-%MVN_VERSION%\bin\mvn.cmd
if not exist "%MVN_BIN%" (
  if not exist "%WRAPPER_HOME%" mkdir "%WRAPPER_HOME%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VERSION%/apache-maven-%MVN_VERSION-bin.zip' -OutFile '%WRAPPER_HOME%\maven.zip'; Expand-Archive -Force '%WRAPPER_HOME%\maven.zip' '%WRAPPER_HOME%'"
)
call "%MVN_BIN%" -f "%~dp0pom.xml" %*

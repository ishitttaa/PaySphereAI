@REM ----------------------------------------------------------------------------
@REM Maven Wrapper startup batch script for Windows
@REM
@REM Usage:
@REM   .\mvnw.cmd spring-boot:run        <- run the app
@REM   .\mvnw.cmd test                   <- run all tests
@REM   .\mvnw.cmd verify                 <- compile + test + package
@REM   .\mvnw.cmd package -DskipTests    <- build JAR, skip tests
@REM
@REM The first time you run this, it downloads Maven 3.9.9 (~10MB).
@REM Subsequent runs use the cached download.
@REM ----------------------------------------------------------------------------
@SETLOCAL

@SET "JAVA_HOME=C:\Program Files\Java\jdk-26.0.2"
@SET "MAVEN_PROJECTBASEDIR=%~dp0"

@IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

@SET "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
@SET "WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"

@"%JAVA_HOME%\bin\java.exe" ^
  -jar "%WRAPPER_JAR%" ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  %*

@ENDLOCAL

@ECHO OFF
SETLOCAL
SET DIRNAME=%~dp0
IF "%DIRNAME%"=="" SET DIRNAME=.
SET APP_HOME=%DIRNAME%
SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL

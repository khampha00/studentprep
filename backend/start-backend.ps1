$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
Write-Host "Set JAVA_HOME to $env:JAVA_HOME for this session." -ForegroundColor Green
mvn spring-boot:run

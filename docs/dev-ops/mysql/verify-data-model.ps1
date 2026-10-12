param(
    [string]$ConfigPath,
    [string]$JdbcJar,
    [string]$MyBatisJar,
    [string]$JavaPath
)

$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $projectPath 'spoken-server-app/src/main/resources/application-dev.yml'
}
if (-not $JdbcJar) {
    $driverPaths = @(
        (Join-Path $env:USERPROFILE '.m2/repository/mysql/mysql-connector-java/*/*.jar'),
        (Join-Path $env:USERPROFILE '.m2/repository/com/mysql/mysql-connector-j/*/*.jar')
    )
    $driver = Get-ChildItem -Path $driverPaths -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object FullName | Select-Object -Last 1
    if (-not $driver) { throw 'MySQL JDBC driver not found. Supply -JdbcJar.' }
    $JdbcJar = $driver.FullName
}
if (-not $JavaPath) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $JavaPath = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        $JavaPath = (Get-Command java -ErrorAction Stop).Source
    }
}

if (-not $MyBatisJar) {
    $mybatis = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/org/mybatis/mybatis/*/*.jar') |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object FullName | Select-Object -Last 1
    if (-not $mybatis) { throw 'MyBatis jar not found. Supply -MyBatisJar.' }
    $MyBatisJar = $mybatis.FullName
}
$outputPath = Join-Path $projectPath 'spoken-server-app/target/schema-verifier'
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$sourceRoot = Join-Path $projectPath 'spoken-server-infrastructure/src/main/java/cn/adinall/ai/infrastructure/dao'
$sources = @((Join-Path $PSScriptRoot 'VerifyDataModel.java'), (Join-Path $sourceRoot 'DatabaseTime.java'))
$sources += (Get-ChildItem (Join-Path $sourceRoot 'po/*.java') | Select-Object -ExpandProperty FullName)
$sources += (Get-ChildItem (Join-Path $sourceRoot 'I*Dao.java') | Select-Object -ExpandProperty FullName)
$compilerPath = Join-Path (Split-Path $JavaPath) 'javac.exe'
$classpath = "$JdbcJar;$MyBatisJar;$outputPath"
& $compilerPath -encoding UTF-8 -cp $classpath -d $outputPath @sources
if ($LASTEXITCODE -ne 0) { throw 'Verification source compilation failed.' }
# JDK 17+；使用项目已有 JDBC/MyBatis 库，不启动 Spring，不修改业务库。
& $JavaPath --class-path $classpath VerifyDataModel $projectPath $ConfigPath
if ($LASTEXITCODE -ne 0) { throw "Database verification failed (exit $LASTEXITCODE)." }

#requires -Version 7.0
param(
    [switch]$Apply,
    [ValidatePattern('^[A-Za-z0-9_@.-]+$')][string]$DatabaseUser = 'root',
    [ValidateRange(1,65535)][int]$DatabasePort = 3306,
    [string]$StorageRoot = 'E:/Codex/data/knowingorder/knowledge'
)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$utf8 = [Text.UTF8Encoding]::new($false)
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sqlPath = Join-Path $projectRoot 'backend/sql/demo/20261024_replace_school_data.sql'
Write-Host '目标：127.0.0.1 / rag_qa / 企业1；保留adminW（用户25、成员1）及原密码。'
Write-Host '范围：9个旧空间、18篇旧文档、44个片段、旧问答与入库任务、3个已停用测试成员。'
if (!$Apply) {
    Write-Host '当前仅预览。执行替换须在IDEA停止dev后端，再以-Apply运行本脚本。'
    exit 0
}
if ([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq 8080 }) {
    throw '8080仍在监听。请先在IDEA停止原dev后端，避免清理期间新增任务；未读取密码或修改数据库。'
}
foreach ($program in @('E:/MySQL/bin/mysql.exe','E:/MySQL/bin/mysqldump.exe')) {
    if (!(Test-Path -LiteralPath $program -PathType Leaf)) { throw '未找到本机MySQL工具，未修改数据库。' }
}
if (!(Test-Path -LiteralPath $sqlPath -PathType Leaf)) { throw '替换SQL不存在。' }
$storage = [IO.Path]::GetFullPath($StorageRoot)
if ($storage -eq [IO.Path]::GetPathRoot($storage) -or !(Test-Path -LiteralPath $storage -PathType Container)) {
    throw '私有原件目录未确认，停止替换；请核对KNOWLEDGE_STORAGE_ROOT并通过-StorageRoot指定实际目录。'
}
$backupRoot = Join-Path 'E:/Codex/backups' ('school-replacement-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
$null = New-Item -ItemType Directory -Path $backupRoot
$backupFile = Join-Path $backupRoot 'rag_qa.sql'
$securePassword = Read-Host '输入本机MySQL密码（只在本窗口输入，不发到对话）' -AsSecureString
$native = [IntPtr]::Zero
$localPassword = $null
try {
    $native = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $localPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($native)
    function Invoke-DatabaseProgram([string]$Executable, [string[]]$Arguments, [string]$InputSql = '') {
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = $Executable
        $start.UseShellExecute = $false
        $start.RedirectStandardInput = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $start.StandardInputEncoding = $utf8
        $start.StandardOutputEncoding = $utf8
        $start.StandardErrorEncoding = $utf8
        $start.CreateNoWindow = $true
        $start.Environment['MYSQL_PWD'] = $localPassword
        foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $start
        $null = $process.Start()
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if ($InputSql) { $process.StandardInput.Write($InputSql) }
        $process.StandardInput.Close()
        if (!$process.WaitForExit(60000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw '数据库步骤超过60秒，已停止客户端；提交结果须先核对，备份保留，禁止盲目重跑。'
        }
        $text = $output.GetAwaiter().GetResult()
        $errorText = $errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            $code = if ($errorText -match 'ERROR (\d+)') { $Matches[1] } else { '未知' }
            throw "数据库步骤失败，错误码=$code；未输出数据库正文或密码。备份保留，请反馈错误码和失败阶段。"
        }
        return $text
    }
    # 禁止--force，保证SQL任何错误立即终止连接、回滚未提交事务；不读取用户选项文件。
    $connection = @('--no-defaults','--host=127.0.0.1',"--port=$DatabasePort", "--user=$DatabaseUser",'--default-character-set=utf8mb4')
    Write-Host '阶段1/4：核对数据库与管理员身份。'
    $identitySql = "SELECT CONCAT(DATABASE(),'|',COUNT(*)) FROM sys_user WHERE id=25 AND BINARY username=BINARY 'adminW' AND status=0 AND deleted=0;"
    $identity = Invoke-DatabaseProgram 'E:/MySQL/bin/mysql.exe' ($connection + @('--connect-timeout=5','--batch','--skip-column-names','--database=rag_qa')) $identitySql
    if ($identity.Trim() -ne 'rag_qa|1') { throw '目标库或管理员与盘点不符，未清理。' }
    $originals = Invoke-DatabaseProgram 'E:/MySQL/bin/mysql.exe' ($connection + @('--connect-timeout=5','--batch','--raw','--skip-column-names','--database=rag_qa')) 'SELECT storage_key,file_size,content_sha256 FROM rag_document_version ORDER BY id;'
    $originalRows = @($originals.Trim() -split "`n" | Where-Object { $_.Trim() })
    if ($originalRows.Count -ne 18) { throw '原件版本数与盘点不符，未清理。' }
    foreach ($row in $originalRows) {
        $parts = $row.Trim() -split "`t"
        if ($parts.Count -ne 3 -or $parts[0] -notmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or $parts[2] -notmatch '^[0-9a-f]{64}$') { throw '原件元数据不符合预期，未清理。' }
        $source = Join-Path $storage $parts[0]
        if (!(Test-Path -LiteralPath $source -PathType Leaf) -or (Get-Item -LiteralPath $source).Length -ne [long]$parts[1] -or (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $parts[2]) {
            throw '实际原件目录或完整性与数据库不符，未清理；请核对KNOWLEDGE_STORAGE_ROOT。'
        }
    }
    Write-Host '阶段2/4：备份完整开发库与私有原件到E盘。'
    $null = Invoke-DatabaseProgram 'E:/MySQL/bin/mysqldump.exe' ($connection + @('--single-transaction','--routines','--triggers','--no-tablespaces',"--result-file=$backupFile",'--databases','rag_qa'))
    if (!(Test-Path -LiteralPath $backupFile) -or (Get-Item -LiteralPath $backupFile).Length -lt 100) { throw '备份未生成或过小，未清理。' }
    Copy-Item -LiteralPath $storage -Destination (Join-Path $backupRoot 'private-originals') -Recurse
    foreach ($row in $originalRows) {
        $parts = $row.Trim() -split "`t"
        $copy = Join-Path (Join-Path $backupRoot 'private-originals') $parts[0]
        if (!(Test-Path -LiteralPath $copy -PathType Leaf) -or (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -ne $parts[2]) { throw '原件副本校验失败，未清理。' }
    }
    $null = Copy-Item -LiteralPath $sqlPath -Destination (Join-Path $backupRoot 'replacement.sql') -PassThru
    $backupHash = (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash
    Write-Host '阶段3/4：事务校验、清理已盘点旧数据并修改学校名称。'
    $sql = [IO.File]::ReadAllText($sqlPath, $utf8)
    $result = Invoke-DatabaseProgram 'E:/MySQL/bin/mysql.exe' ($connection + @('--connect-timeout=5','--batch','--database=rag_qa')) $sql
    if (!$result.Contains('SCHOOL_REPLACEMENT_COMMITTED')) { throw '未读到提交标记，先核对数据库，不重跑。' }
    Write-Host '阶段4/4：记录备份与提交结果。'
    $record = [ordered]@{ status='COMMITTED'; database='rag_qa'; enterpriseId='1'; schoolName='知序大学'; preservedUsername='adminW'; backupFile=$backupFile; backupSha256=$backupHash; storageSource=$storage; completedAt=[DateTimeOffset]::Now.ToString('o') }
    [IO.File]::WriteAllText((Join-Path $backupRoot 'result.json'), (($record | ConvertTo-Json) + "`n"), $utf8)
    Write-Host "清理已提交，管理员账号和密码保留。备份：$backupRoot"
    Write-Host '下一步：IDEA启动原dev后端，再导入学校资料；当前尚未导入新知识。'
} finally {
    $localPassword = $null
    if ($native -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($native) }
    $securePassword.Dispose()
}

#requires -Version 5.1
param(
    [switch]$Apply,
    [switch]$Migrate,
    [ValidatePattern('^[A-Za-z0-9_@.-]+$')][string]$DatabaseUser = 'root',
    [ValidateRange(1,65535)][int]$DatabasePort = 3306,
    [string]$MysqlBin = 'E:/MySQL/bin'
)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$utf8 = [Text.UTF8Encoding]::new($false)
$projectRoot = if ($env:SEED_PROJECT_ROOT) { [IO.Path]::GetFullPath($env:SEED_PROJECT_ROOT) }
               else { [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')) }
$sqlPath = Join-Path $projectRoot 'backend/sql/demo/20261025_seed_demo_data.sql'

Write-Host '目标：127.0.0.1 / rag_qa / 首个启用企业。'
Write-Host '内容：5个部门、100名虚构员工、演示管理员与财务账号、知识空间/授权/文档/片段、'
Write-Host '      示例会话与私有问答、入库与向量控制面、已到期访客、补贴规则与4条申请、结算与退款调整。'
Write-Host '口令：Demo@2026（仅本机演示）。凭证表不伪造，需真实原件才能上传下载。'
if (!$Apply) {
    Write-Host '当前仅预览，未连接数据库。确认后加 -Apply 运行；脚本只做INSERT与受限UPDATE，可重复执行。'
    Write-Host '若预检查只缺 rag_knowledge_context，可加 -Migrate 一并补建该表。'
    return
}
$mysql = Join-Path $MysqlBin 'mysql.exe'
if (!(Test-Path -LiteralPath $mysql -PathType Leaf)) { throw '未找到本机mysql.exe，未修改数据库。' }
if (!(Test-Path -LiteralPath $sqlPath -PathType Leaf)) { throw '种子SQL不存在，未修改数据库。' }

$securePassword = Read-Host '输入本机MySQL密码（只在本窗口输入，不发到对话）' -AsSecureString
$native = [IntPtr]::Zero
$localPassword = $null
try {
    $native = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $localPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($native)
    # 用MYSQL_PWD传密码，不出现在命令行、日志或对话；stdin按UTF-8文件原样送入，避免Windows控制台936编码破坏中文。
    $env:MYSQL_PWD = $localPassword
    function New-TempPath([string]$Extension) {
        return [IO.Path]::Combine([IO.Path]::GetTempPath(), [guid]::NewGuid().ToString('N') + $Extension)
    }
    function Invoke-Mysql([string[]]$Arguments, [string]$InputFile = '') {
        $outFile = New-TempPath '.out'
        $errFile = New-TempPath '.err'
        try {
            $options = @{
                FilePath = $mysql; ArgumentList = $Arguments; NoNewWindow = $true; PassThru = $true;
                RedirectStandardOutput = $outFile; RedirectStandardError = $errFile
            }
            if ($InputFile) { $options.RedirectStandardInput = $InputFile }
            $process = Start-Process @options
            if (!$process.WaitForExit(60000)) {
                $process.Kill()
                $process.WaitForExit()
                throw '数据库步骤超过60秒，已停止客户端；事务是否提交须先核对，禁止盲目重跑。'
            }
            $text = [IO.File]::ReadAllText($outFile, $utf8)
            $errorText = [IO.File]::ReadAllText($errFile, $utf8)
            if ($errorText -match 'ERROR (\d+)') {
                $code = $Matches[1]
                if ($code -eq '1062') {
                    throw '前置条件不满足（无启用企业或演示成员缺失），已回滚；未输出数据库正文或密码。'
                }
                $hint = ''
                if ($errorText -match "Table '([A-Za-z0-9_.]+)' doesn't exist") { $hint = '（涉及表=' + $Matches[1] + '）' }
                elseif ($errorText -match "Unknown column '([^']+)'") { $hint = '（涉及列=' + $Matches[1] + '）' }
                throw "数据库步骤失败，错误码=$code$hint；未输出数据库正文或密码。请反馈错误码和阶段。"
            }
            return $text
        } finally {
            Remove-Item -LiteralPath $outFile,$errFile -ErrorAction SilentlyContinue
        }
    }
    # 不读取用户选项文件，禁止--force：任何SQL错误立即终止并回滚未提交事务。
    $connection = @('--no-defaults','--host=127.0.0.1',"--port=$DatabasePort", "--user=$DatabaseUser",
                    '--default-character-set=utf8mb4','--connect-timeout=5','--batch','--database=rag_qa')
    Write-Host '阶段1/3：核对目标库、启用企业与所需数据表。'
    $required = @(
        'sys_user','sys_enterprise','sys_department','sys_enterprise_member',
        'rag_knowledge_space','rag_knowledge_grant','rag_document','rag_document_version','rag_chunk',
        'rag_conversation','rag_message','rag_task_reference','rag_guest_identity',
        'rag_knowledge_chat','rag_knowledge_job','rag_knowledge_context','rag_knowledge_feedback',
        'rag_ingest_job','rag_vector_job','rag_vector_publication',
        'rag_affair_rule','rag_affair_application','rag_affair_application_version','rag_affair_participant',
        'rag_affair_quota','rag_affair_settlement','rag_affair_adjustment'
    )
    $queryFile = New-TempPath '.sql'
    [IO.File]::WriteAllText($queryFile, "SELECT table_name FROM information_schema.tables WHERE table_schema='rag_qa';", $utf8)
    try {
        $existing = @((Invoke-Mysql ($connection + @('--skip-column-names')) $queryFile) -split "`r?`n" |
                      Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
    } finally {
        Remove-Item -LiteralPath $queryFile -ErrorAction SilentlyContinue
    }
    $missing = @($required | Where-Object { $existing -notcontains $_ })
    # 仅对唯一已知缺表按项目迁移补建；其余缺表仍中止，避免擅自改库结构。
    if ($Migrate -and $missing.Count -eq 1 -and $missing[0] -eq 'rag_knowledge_context') {
        $migration = Join-Path $projectRoot 'backend/sql/migrations/20261019_02_knowledge_context.sql'
        if (!(Test-Path -LiteralPath $migration -PathType Leaf)) { throw '补建迁移不存在，未写入。' }
        Write-Host '缺表 rag_knowledge_context，按20261019_02补建（单条CREATE TABLE）。'
        $null = Invoke-Mysql $connection $migration
        $queryFile = New-TempPath '.sql'
        [IO.File]::WriteAllText($queryFile, "SELECT table_name FROM information_schema.tables WHERE table_schema='rag_qa';", $utf8)
        try {
            $existing = @((Invoke-Mysql ($connection + @('--skip-column-names')) $queryFile) -split "`r?`n" |
                          Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
        } finally {
            Remove-Item -LiteralPath $queryFile -ErrorAction SilentlyContinue
        }
        $missing = @($required | Where-Object { $existing -notcontains $_ })
    }
    if ($missing.Count -gt 0) { throw ('缺少数据表：' + ($missing -join '、') + '；未写入，请先执行对应迁移后再运行。') }
    $queryFile = New-TempPath '.sql'
    [IO.File]::WriteAllText($queryFile, "SELECT CONCAT(DATABASE(),'|',(SELECT COUNT(*) FROM sys_enterprise WHERE status=0));", $utf8)
    try {
        $check = (Invoke-Mysql ($connection + @('--skip-column-names')) $queryFile).Trim()
    } finally {
        Remove-Item -LiteralPath $queryFile -ErrorAction SilentlyContinue
    }
    $parts = $check -split '\|'
    if ($parts.Count -ne 2 -or $parts[0] -ne 'rag_qa') { throw '目标库不是rag_qa，未写入。' }
    if ([int]$parts[1] -lt 1) { throw '没有启用企业，未写入；请先完成企业初始化。' }

    Write-Host '阶段2/3：事务写入演示数据（失败自动回滚）。'
    $result = Invoke-Mysql $connection $sqlPath
    if (!$result.Contains('DEMO_SEED_COMMITTED')) { throw '未读到提交标记，先核对数据库，不重跑。' }

    Write-Host '阶段3/3：提交成功，汇总如下。'
    $summary = @($result -split "`n" | Where-Object { $_ -match 'DEMO_SEED_COMMITTED' })
    $summary | ForEach-Object { Write-Host ('marker|users|members|spaces|documents|applications = ' + $_.Trim()) }
    Write-Host '演示账号：zhixuadmin（管理员）、zhixufinance（财务），员工如 chenzihan / linyutong / wanghaoran / lisiyuan。'
    Write-Host '提示：重复执行本脚本只补缺，不重复插入，也不清理既有数据。'
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    $localPassword = $null
    if ($native -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($native) }
    $securePassword.Dispose()
}

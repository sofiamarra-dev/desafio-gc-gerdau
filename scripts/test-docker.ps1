param(
    [ValidatePattern('^gerdau-smoke-[a-z0-9-]+$')][string]$ProjectName = 'gerdau-smoke-local',
    [int]$JavaPort = 18080,
    [int]$AiPort = 18001,
    [int]$DbPort = 15433,
    [switch]$SkipBuild,
    [switch]$KeepContainers
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'compose.yml'
Add-Type -AssemblyName System.Net.Http
$httpClient = [System.Net.Http.HttpClient]::new()
$httpClient.Timeout = [TimeSpan]::FromSeconds(180)
$previous = @{}
foreach($name in 'JAVA_PORT','AI_PORT','DB_PORT') { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Invoke-Compose {
    param([string[]]$Arguments)
    & docker compose --project-directory $projectRoot --env-file (Join-Path $projectRoot '.env.example') -f $composeFile -p $ProjectName @Arguments
    if($LASTEXITCODE -ne 0) { throw "Docker Compose falhou: $($Arguments -join ' ')" }
}
function Post-Json {
    param([string]$Path, $Data)
    $json = $Data | ConvertTo-Json -Depth 12
    # Decodifica UTF-8 explicitamente, inclusive no Windows PowerShell 5.
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "http://127.0.0.1:$JavaPort$Path")
    $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
    $response = $null
    try {
        $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [Text.Encoding]::UTF8.GetString($bytes)
        if(-not $response.IsSuccessStatusCode) { throw "HTTP $([int]$response.StatusCode): $text" }
        return ($text | ConvertFrom-Json)
    } finally { if($response) { $response.Dispose() }; $request.Dispose() }
}
try {
    $env:JAVA_PORT = "$JavaPort"; $env:AI_PORT = "$AiPort"; $env:DB_PORT = "$DbPort"
    if(-not $SkipBuild) { Invoke-Compose -Arguments @('build') }
    # O nome de projeto de teste cria rede e volumes separados do banco de desenvolvimento.
    Invoke-Compose -Arguments @('up','-d','--wait','--wait-timeout','900')
    $page = Invoke-WebRequest -Uri "http://127.0.0.1:$JavaPort/menu.html" -UseBasicParsing
    if($page.StatusCode -ne 200) { throw 'Menu indisponível.' }
    $python = Invoke-RestMethod -Uri "http://127.0.0.1:$AiPort/status"
    if($python.model -ne 'sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2') { throw 'Modelo inesperado.' }
    $description = 'SV manutenção de motor H.EXTRA 50%'
    $saved = Post-Json '/api/catalog/services' @{descricao_item=$description; request_id=[guid]::NewGuid().ToString()}
    $search = Post-Json '/api/search' @{query='manutenção de motor hora extra 50%'}
    if($search.total -lt 1 -or $search.query_parts.activity -ne 'manutenção de motor' -or $search.query_parts.qualifiers -notcontains 'HE 50%') { throw 'Integração Java/Python não retornou a separação esperada.' }
    if(-not ($search.results | Where-Object catalog_id -EQ $saved.catalog_id)) { throw 'Serviço salvo não foi encontrado.' }
    $matched = $search.results | Where-Object catalog_id -EQ $saved.catalog_id | Select-Object -First 1
    if($saved.descricao_item -ne $description -or $matched.atividade -ne 'manutenção de motor' -or $matched.qualificadores -notcontains 'HE 50%') { throw 'Falha em UTF-8 ou na variante pontuada do índice.' }
    $testCode = 'T' + [guid]::NewGuid().ToString('N').Substring(0,10).ToUpperInvariant()
    $meaning = 'qualificador de teste ' + $testCode
    $createdDictionary = Post-Json '/api/abbreviations' @{code=$testCode; meaning=$meaning; variants=@(); active=$false}
    $entry = $createdDictionary.entries | Where-Object code -EQ $testCode
    $corrected = Post-Json "/api/abbreviations/$testCode/corrections" @{meaning=('significado corrigido '+$testCode);variants=@();reason='Validação de correção no Docker';version=$entry.version;confirmed=$true}
    $history = @(Invoke-RestMethod -Uri "http://127.0.0.1:$JavaPort/api/abbreviations/$testCode/corrections")
    if($history.Count -ne 1 -or $history[0].before.meaning -ne $meaning) { throw 'Histórico de correções inválido.' }
    $template = Invoke-WebRequest -Uri "http://127.0.0.1:$JavaPort/api/catalog/batches/template" -UseBasicParsing
    if($template.StatusCode -ne 200 -or $template.Headers['Content-Type'] -notlike '*spreadsheetml.sheet*') { throw 'Modelo XLSX indisponível.' }
    $batch = Post-Json '/api/catalog/batches/text' @{text='SV manutenção de motor H. EXTRA 50%'}
    $items = @(Invoke-RestMethod -Uri "http://127.0.0.1:$JavaPort/api/catalog/batches/$($batch.id)/items")
    if($items.Count -ne 1 -or $items[0].status -ne 'PENDING') { throw 'Lote de revisão inválido.' }
    $decision = Post-Json "/api/catalog/batches/$($batch.id)/items/$($items[0].id)/decision" @{action='EXISTING';service_id=$saved.catalog_id}
    if($decision.catalog_id -ne $saved.catalog_id) { throw 'A decisão não reutilizou o código esperado.' }
    # Recria os contêineres com os mesmos volumes, sem rebuild das imagens.
    Invoke-Compose -Arguments @('down')
    Invoke-Compose -Arguments @('up','-d','--wait','--wait-timeout','900')
    $again = Post-Json '/api/search' @{query=$saved.codigo_servico}
    if(-not ($again.results | Where-Object catalog_id -EQ $saved.catalog_id)) { throw 'O cadastro não persistiu após recriar os contêineres.' }
    $resumed = @(Invoke-RestMethod -Uri "http://127.0.0.1:$JavaPort/api/catalog/batches/$($batch.id)/items")
    if($resumed[0].status -ne 'EXISTING') { throw 'A decisão do lote não persistiu.' }
    $persistedHistory = @(Invoke-RestMethod -Uri "http://127.0.0.1:$JavaPort/api/abbreviations/$testCode/corrections")
    if($persistedHistory.Count -ne 1) { throw 'O histórico da sigla não persistiu.' }
    Write-Output 'OK: menu, MiniLM real, cadastro, busca, UTF-8, modelo XLSX, lote, correção de sigla e persistência após recriação.'
    Invoke-Compose -Arguments @('ps')
} finally {
    try {
        if(-not $KeepContainers) {
            # Somente o projeto de teste validado pelo parâmetro; nunca gerdau-spring.
            Invoke-Compose -Arguments @('down','--volumes')
        }
    } finally {
        $httpClient.Dispose()
        foreach($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    }
}

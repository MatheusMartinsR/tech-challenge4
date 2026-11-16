$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()

function Send-Feedback([string]$Body, [int]$ExpectedStatus) {
    $content = [System.Net.Http.StringContent]::new($Body, [Text.Encoding]::UTF8, 'application/json')
    try {
        $response = $client.PostAsync('http://127.0.0.1:3000/avaliacao', $content).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne $ExpectedStatus) {
            throw "Esperado HTTP $ExpectedStatus, recebido $([int]$response.StatusCode): $text"
        }
        return ($text | ConvertFrom-Json)
    } finally {
        $content.Dispose()
    }
}

try {
    $normal = Send-Feedback '{"descricao":"Teste local - boa aula","nota":8}' 201
    if ($normal.urgencia -ne 'NORMAL') { throw 'Urgencia normal incorreta' }
    $critical = Send-Feedback '{"descricao":"Teste local - aula sem audio","nota":2}' 201
    if ($critical.urgencia -ne 'ALTA') { throw 'Urgencia critica incorreta' }
    $invalid = Send-Feedback '{"descricao":"Teste invalido","nota":11}' 400
    if ($invalid.codigo -ne 'ENTRADA_INVALIDA') { throw 'Erro de validacao incorreto' }
    $blank = Send-Feedback '{"descricao":"   ","nota":4}' 400
    $malformed = Send-Feedback '{' 400

    # Apenas comandos dentro do emulador, com endpoint local e credenciais ficticias.
    $compose = Join-Path $PSScriptRoot '../compose.local.yaml'
    $savedJson = & docker compose -f $compose exec -T localstack awslocal dynamodb scan --table-name tc4-local-feedbacks --consistent-read --output json
    if ($LASTEXITCODE -ne 0) { throw 'Nao foi possivel consultar o DynamoDB local' }
    $saved = ($savedJson -join "`n") | ConvertFrom-Json
    $ids = @($saved.Items | ForEach-Object { $_.id.S })
    if ($ids -notcontains $normal.id -or $ids -notcontains $critical.id) {
        throw 'Feedbacks nao encontrados no DynamoDB local'
    }
    Write-Host 'OK: HTTP 201 normal e critico; HTTP 400 nota invalida, descricao vazia e JSON malformado; itens gravados no DynamoDB local.'
    Write-Host "Feedback critico: $($critical.id). Consulte a fila local para conferir o evento."
} finally {
    $client.Dispose()
}

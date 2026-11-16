$ErrorActionPreference = 'Stop'
$marker = [guid]::NewGuid().ToString()
Add-Type -AssemblyName System.Net.Http
$mailClient = [System.Net.Http.HttpClient]::new()

function Get-LocalMailJson([string]$Path) {
    $text = $mailClient.GetStringAsync("http://127.0.0.1:8025$Path").GetAwaiter().GetResult()
    return ($text | ConvertFrom-Json)
}

function Send-LocalFeedback([int]$Rating, [string]$Description) {
    $body = @{ descricao = $Description; nota = $Rating } | ConvertTo-Json
    return Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:3000/avaliacao' `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
}

function Find-LocalEmail([string]$Query) {
    return Get-LocalMailJson ('/api/v1/search?query=' + [uri]::EscapeDataString($Query))
}

$criticalDescription = "Teste notificacao critica $marker"
$critical = Send-LocalFeedback 2 $criticalDescription
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    $result = Find-LocalEmail $critical.id
    if ($result.messages.Count -gt 0) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)

if ($result.messages.Count -eq 0) { throw "Email nao encontrado para feedback $($critical.id). Consulte logs notification." }
$mail = Get-LocalMailJson "/api/v1/message/$($result.messages[0].ID)"
if (!$mail.Text.Contains($criticalDescription) -or !$mail.Text.Contains($critical.id) `
        -or $mail.Text -notmatch 'Urg.ncia: ALTA' -or $mail.Text -notmatch 'Data de envio:' `
        -or $mail.Text -notmatch 'Nota: 2') {
    throw 'O email capturado nao contem todos os campos esperados'
}
if ($mail.To[0].Address -ne 'admin@tc4.test') { throw 'Destinatario local incorreto' }

$normalDescription = "Teste notificacao normal $marker"
$normal = Send-LocalFeedback 8 $normalDescription
Start-Sleep -Seconds 3
$normalMessages = Find-LocalEmail $normal.id
if ($normalMessages.messages.Count -gt 0) { throw 'Feedback normal gerou email indevidamente' }

Write-Host 'OK: API -> DynamoDB/SNS -> SQS -> NotificationHandler -> Mailpit.'
Write-Host 'Email critico contem descricao, nota, urgencia, data e ID; feedback normal nao gerou email.'
Write-Host "Abra http://localhost:8025 e procure pelo feedback $($critical.id)."
$mailClient.Dispose()

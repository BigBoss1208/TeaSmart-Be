$ErrorActionPreference = 'Stop'
$keys = @('RAG_LLM_URL', 'RAG_LLM_MODEL', 'RAG_LLM_API_KEY')
$previous = @{}
foreach ($key in $keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    if ([string]::IsNullOrWhiteSpace($env:RAG_LLM_URL)) { $env:RAG_LLM_URL = Read-Host 'Full HTTPS chat/completions endpoint (or localhost HTTP)' }
    if ([string]::IsNullOrWhiteSpace($env:RAG_LLM_MODEL)) { $env:RAG_LLM_MODEL = Read-Host 'Existing provider model ID' }
    if ([string]::IsNullOrWhiteSpace($env:RAG_LLM_API_KEY)) {
        $secureKey = Read-Host 'LLM API key (hidden; do not paste in chat)' -AsSecureString
        $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
        try { $env:RAG_LLM_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer); $secureKey.Dispose() }
    }
    if ([string]::IsNullOrWhiteSpace($env:RAG_LLM_API_KEY)) { throw 'Empty key; service not started.' }
    $python = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
    if (!(Test-Path -LiteralPath $python)) {
        $projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
        $python = Join-Path $projectRoot 'ai-service/.venv/Scripts/python.exe'
    }
    if (!(Test-Path -LiteralPath $python)) { throw 'Existing AI venv unavailable. No package installed.' }
    Write-Host 'Starting local RAG service on 127.0.0.1:8002. No key is written to source/history/log.'
    & $python -m uvicorn rag_app.main:app --app-dir $PSScriptRoot --host 127.0.0.1 --port 8002 --no-access-log --log-level warning
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}

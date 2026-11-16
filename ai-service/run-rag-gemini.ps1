param(
    [string]$Model='gemini-2.5-flash',
    [switch]$AllowProviderCalls,
    [switch]$ConfirmFreeTier
)
$ErrorActionPreference='Stop'
# Key is read as SecureString by the existing launcher, never a command-line parameter.
& (Join-Path $PSScriptRoot 'run-rag-local.ps1') -Provider Gemini -Model $Model -AllowProviderCalls:$AllowProviderCalls -ConfirmFreeTier:$ConfirmFreeTier

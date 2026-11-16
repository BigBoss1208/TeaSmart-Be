param(
    [ValidateSet('gemini-3.5-flash-lite','gemini-2.5-flash')][string]$Model='gemini-3.5-flash-lite',
    [switch]$AllowProviderCalls,
    [switch]$ConfirmFreeTier
)
$ErrorActionPreference='Stop'
# Key is read as SecureString by the existing launcher, never a command-line parameter.
& (Join-Path $PSScriptRoot 'run-rag-local.ps1') -Provider Gemini -Model $Model -AllowProviderCalls:$AllowProviderCalls -ConfirmFreeTier:$ConfirmFreeTier

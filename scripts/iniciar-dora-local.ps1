# Arranque local optativo de DORA real. Docker consume la configuración privada;
# este script no lee ni modifica su contenido, ni inicia conversaciones de prueba.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    if (-not (Test-Path -LiteralPath '.env' -PathType Leaf)) {
        throw 'Falta tu configuración local .env; completa la configuración privada antes de iniciar.'
    }
    docker compose -p talenti --env-file .env -f docker-compose.yml -f docker-compose.dora-local.yml up -d --build
    if ($LASTEXITCODE -ne 0) {
        throw 'No se pudo iniciar TALENTI. Revisa el estado de los servicios sin mostrar claves.'
    }
    Write-Output 'TALENTI iniciado en http://localhost:3000. DORA real queda disponible según clave, acceso y cupo.'
    Write-Output 'La prueba de conversación la realiza Favio; límite mensual estimado configurado: USD 2.'
}
finally {
    Pop-Location
}

#Requires -Version 5.1
<#
.SYNOPSIS
  Prueba del reintento M06b contra un broker RabbitMQ REAL, aislado y temporal.

.DESCRIPTION
  Levanta un contenedor rabbitmq:3.13-management-alpine con nombre, puerto,
  vhost, exchange y colas ÚNICOS para esta ejecución, compila un helper Java
  con el classpath real del repo (target/classes + dependencias Maven) y
  ejecuta 4 casos comprobando el estado real de las colas por la API de
  management (no solo logs). El contenedor se borra siempre en un finally.

  No se usa trustedPackages=*: el @RabbitListener real se invoca directamente
  y el converter es el Jackson2JsonMessageConverter de RabbitMQConfig.

.EJEMPLO
  powershell -ExecutionPolicy Bypass -File scripts/probar-m06b-rabbit.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

# ---------------------------------------------------------------------------
# Identificadores únicos de esta corrida (nada compartido con otros scripts,
# con AWS/Azure ni con el clúster de ms-admin).
# ---------------------------------------------------------------------------
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sufijo = 'm06b' + (Get-Date -Format 'yyyyMMddHHmmss') + [guid]::NewGuid().ToString('N').Substring(0, 6)
$container = "p360-$sufijo"
$vhost = "p360-$sufijo"
$usuario = "u$sufijo"
$password = [guid]::NewGuid().ToString('N').Substring(0, 16)
$exchange = "p360.$sufijo.eventos"
$dlx = "p360.$sufijo.dlx"
$auditExchange = "p360.$sufijo.audit"
$cola = "p360.$sufijo.notificaciones.queue"
$dlq = "p360.$sufijo.notificaciones.dlq"
$auditQueue = "p360.$sufijo.audit.queue"
$vhostEnc = [uri]::EscapeDataString($vhost)
$tmp = Join-Path $env:TEMP "p360-$sufijo"
$clases = Join-Path $tmp 'classes'
$helper = Join-Path $repo 'scripts\M06bRabbitHelper.java'
$pom = Join-Path $repo 'pom.xml'

$script:ejecutadas = 0
$script:fallidas = 0
$script:errores = 0
$script:omitidas = 0
$containerCreado = $false

function Escribir([string]$texto) { Write-Host $texto }

function Rutaj([string]$ruta) { return $ruta.Replace('\', '/') }

function Get-PuertoLibre {
  $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
  $listener.Start()
  $p = $listener.LocalEndpoint.Port
  $listener.Stop()
  return $p
}

function Invoke-Nativo([string]$exe, [string[]]$parametros, [int[]]$codigosAceptados = @(0)) {
  $anterior = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & $exe @parametros 2>&1 | ForEach-Object { Write-Host $_ }
    $codigo = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $anterior
  }
  if ($codigosAceptados -notcontains $codigo) {
    throw "$exe terminó con código de salida $codigo"
  }
  return $codigo
}

function Escribir-ArgFile([string]$ruta, [string[]]$lineas) {
  $contenido = ($lineas -join "`n") + "`n"
  [System.IO.File]::WriteAllText($ruta, $contenido, [System.Text.UTF8Encoding]::new($false))
}

function Resultado([string]$caso, [bool]$ok, [string]$detalle) {
  if ($ok) {
    $script:ejecutadas++
    Escribir ("RESULTADO {0}: OK | {1}" -f $caso, $detalle)
  } else {
    $script:fallidas++
    Escribir ("RESULTADO {0}: FALLIDO | {1}" -f $caso, $detalle)
  }
}

function ResultadoError([string]$caso, [string]$detalle) {
  $script:errores++
  Escribir ("RESULTADO {0}: ERROR | {1}" -f $caso, $detalle)
}

# ---------------------------------------------------------------------------
# CPU de la API de management (estado real de colas y mensajes).
# ---------------------------------------------------------------------------
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$usuario`:$password"))

function Api([string]$metodo, [string]$ruta, [string]$cuerpo = $null) {
  $uri = "http://127.0.0.1:$puertoMgmt$ruta"
  $headers = @{ Authorization = "Basic $basic" }
  if (-not [string]::IsNullOrEmpty($cuerpo)) {
    return Invoke-RestMethod -Method $metodo -Uri $uri -Headers $headers `
      -ContentType 'application/json' -Body $cuerpo
  }
  return Invoke-RestMethod -Method $metodo -Uri $uri -Headers $headers
}

function Nativo-Lineas([string]$exe, [string[]]$parametros) {
  $anterior = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    return & $exe @parametros 2>$null
  } finally {
    $ErrorActionPreference = $anterior
  }
}

function EstadoCola([string]$nombre) {
  # rabbitmqctl consulta el nodo directamente: sin caché de estadísticas.
  $lineas = Nativo-Lineas docker @('exec', $container, 'rabbitmqctl', 'list_queues',
    '-p', $vhost, 'name', 'messages', 'messages_ready', 'messages_unacknowledged')
  foreach ($linea in $lineas) {
    $partes = ("$linea").Trim() -split "`t"
    if ($partes.Count -ge 4 -and $partes[0] -eq $nombre) {
      return [pscustomobject]@{
        Total = [int]$partes[1]
        Listos = [int]$partes[2]
        SinConfirmar = [int]$partes[3]
      }
    }
  }
  return [pscustomobject]@{ Total = -1; Listos = -1; SinConfirmar = -1 }
}

function Purgar([string]$nombre) {
  # DELETE /api/queues/{vhost}/{name}/contents es el purge real de la API de management.
  Api 'DELETE' ("/api/queues/$vhostEnc/{0}/contents" -f [uri]::EscapeDataString($nombre)) | Out-Null
}

# Las estadísticas de management se refrescan de forma asíncrona (~5 s por
# defecto): esperamos a que el estado real converja antes de verificar.
function EstadoColaTras([string]$nombre, [int]$totalEsperado, [int]$segundos = 12) {
  $deadline = (Get-Date).AddSeconds($segundos)
  do {
    $estado = EstadoCola $nombre
    if ($estado.Total -eq $totalEsperado) { return $estado }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  return $estado
}

function ObtenerAuditoria([string]$caso, [string]$orden) {
  $cuerpo = @{ count = 500; ackmode = 'ack_requeue_true'; encoding = 'auto' } | ConvertTo-Json -Compress
  $mensajes = Api 'POST' ("/api/queues/$vhostEnc/{0}/get" -f [uri]::EscapeDataString($auditQueue)) $cuerpo
  $salida = @()
  foreach ($mensaje in $mensajes) {
    $payload = $mensaje.payload | ConvertFrom-Json
    if ($payload.caso -eq $caso -and $payload.orden -eq $orden) { $salida += $payload }
  }
  return $salida
}

function ObtenerDlq {
  $cuerpo = @{ count = 20; ackmode = 'ack_requeue_true'; encoding = 'auto' } | ConvertTo-Json -Compress
  $mensajes = Api 'POST' ("/api/queues/$vhostEnc/{0}/get" -f [uri]::EscapeDataString($dlq)) $cuerpo
  $salida = @()
  foreach ($mensaje in $mensajes) {
    $payload = $mensaje.payload | ConvertFrom-Json
    # En la API de management los headers NO están en el objeto mensaje,
    # sino en properties.headers (aquí viaja el x-death real del broker).
    $deathCount = -1
    $propsH = $mensaje.properties.PSObject.Properties['headers']
    if ($null -ne $propsH) {
      $propDeath = $propsH.Value.PSObject.Properties['x-death']
      if ($null -ne $propDeath -and @($propDeath.Value).Count -gt 0) {
        $deathCount = [int]@($propDeath.Value)[0].count
      }
    }
    $salida += [pscustomobject]@{
      Payload = $payload
      DeathCount = $deathCount
      TieneOrden = ($null -ne $payload.PSObject.Properties['ordenId'])
    }
  }
  return $salida
}

# ---------------------------------------------------------------------------
Escribir '============================================================================'
Escribir 'M06b - reintento con broker RabbitMQ REAL y aislado'
Escribir '============================================================================'
Escribir ("COMANDO REPRODUCIBLE: powershell -ExecutionPolicy Bypass -File `"{0}`"" -f (Rutaj $PSCommandPath))
Escribir 'Requiere: Docker (imagen rabbitmq:3.13-management-alpine), JDK 17+ y Maven con dependencias en .m2.'
Escribir ("Prefijo único: {0} | contenedor: {1} | vhost: {2}" -f $sufijo, $container, $vhost)
Escribir ("Exchanges: {0}, {1}, {2}" -f $exchange, $dlx, $auditExchange)
Escribir ("Colas: {0} | {1} | {2}" -f $cola, $dlq, $auditQueue)
Escribir 'trustedPackages: NO se configura (listener real invocado directamente + converter del repo).'
Escribir 'Politica de espera: 1000 ms en permanente (prueba que NO espera) y transitorio'
Escribir '  (mide 3 esperas reales); 0 en exito y crash porque no hay reintentos. Documentado abajo.'
Escribir 'Nota at-least-once: BaseConsumer no deduplica; la caida se simula tras recibir y'
Escribir '  antes del efecto, para verificar redelivery sin efecto duplicado.'
Escribir '----------------------------------------------------------------------------'

try {
  New-Item -ItemType Directory -Path $tmp -Force | Out-Null
  New-Item -ItemType Directory -Path $clases -Force | Out-Null

  $puerto = Get-PuertoLibre
  do { $puertoMgmt = Get-PuertoLibre } while ($puertoMgmt -eq $puerto)
  Escribir ("Puertos aislados: AMQP {0} | management {1}" -f $puerto, $puertoMgmt)

  # 1) Broker temporal -------------------------------------------------------
  Escribir ("docker run -d --name {0} -e RABBITMQ_DEFAULT_USER=... -p {1}:5672 -p {2}:15672 rabbitmq:3.13-management-alpine" -f $container, $puerto, $puertoMgmt)
  Invoke-Nativo docker @(
    'run', '-d', '--name', $container,
    '-e', "RABBITMQ_DEFAULT_USER=$usuario",
    '-e', "RABBITMQ_DEFAULT_PASS=$password",
    '-p', "${puerto}:5672", '-p', "${puertoMgmt}:15672",
    'rabbitmq:3.13-management-alpine'
  ) | Out-Null
  $containerCreado = $true

  Escribir 'Esperando a RabbitMQ (API de management: app rabbit completamente arrancada)...'
  $deadline = (Get-Date).AddSeconds(120)
  $listo = $false
  while ((Get-Date) -lt $deadline) {
    try {
      Api 'GET' '/api/overview' | Out-Null
      $listo = $true
      break
    } catch {
      Start-Sleep -Seconds 2
    }
  }
  if (-not $listo) { throw 'RabbitMQ management no respondió en 120 s' }

  Invoke-Nativo docker @('exec', $container, 'rabbitmqctl', 'add_vhost', $vhost) | Out-Null
  Invoke-Nativo docker @('exec', $container, 'rabbitmqctl', 'set_permissions', '-p', $vhost,
    $usuario, '.*', '.*', '.*') | Out-Null
  Escribir "Vhost y permisos creados (solo para esta corrida)."

  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    try { Api 'GET' '/api/overview' | Out-Null; break } catch { Start-Sleep -Milliseconds 700 }
  }

  # 2) Classpath real del repo + helper Java --------------------------------
  if (-not (Test-Path (Join-Path $repo 'target\classes'))) {
    Invoke-Nativo mvn @('-o', '-q', '-f', (Rutaj $pom), '-DskipTests', 'compile') | Out-Null
  }
  $archivoCp = Join-Path $tmp 'cp.txt'
  Invoke-Nativo mvn @('-o', '-q', '-f', (Rutaj $pom), 'dependency:build-classpath',
    "-Dmdep.outputFile=$(Rutaj $archivoCp)") | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'No se pudo construir el classpath Maven' }
  $dependencias = (Get-Content $archivoCp -Raw).Trim().Replace('\', '/')
  $classpath = (Rutaj (Join-Path $repo 'target\classes')) + ';' + $dependencias
  Escribir ("Classpath: target/classes + {0} dependencias Maven (.{1})" -f
    ($dependencias -split ';').Count, 'm2/repository')

  $javacArgs = Join-Path $tmp 'javac.args'
  Escribir-ArgFile $javacArgs @(
    '--release', '17', '-encoding', 'UTF-8',
    '-cp', ("`"$classpath`""), '-d', ("`"{0}`"" -f (Rutaj $clases)),
    ("`"{0}`"" -f (Rutaj $helper))
  )
  Invoke-Nativo javac @("@$javacArgs") | Out-Null
  Escribir 'Helper Java compilado contra target/classes + dependencias del repo.'

  function Nuevo-ArgJava([string]$espera, [string]$nombre) {
    $ruta = Join-Path $tmp $nombre
    Escribir-ArgFile $ruta @(
      '-Dp360.m06b.host=127.0.0.1',
      "-Dp360.m06b.port=$puerto",
      "-Dp360.m06b.vhost=$vhost",
      "-Dp360.m06b.user=$usuario",
      "-Dp360.m06b.pass=$password",
      "-Dp360.m06b.ex=$exchange",
      "-Dp360.m06b.dlx=$dlx",
      "-Dp360.m06b.audit-ex=$auditExchange",
      "-Dp360.m06b.queue=$cola",
      "-Dp360.m06b.dlq=$dlq",
      "-Dp360.m06b.audit-queue=$auditQueue",
      "-Dp360.retry.espera-ms=$espera",
      '-cp', ("`"{0};{1}`"" -f (Rutaj $clases), $classpath),
      'M06bRabbitHelper'
    )
    return $ruta
  }

  $argsCero = Nuevo-ArgJava '0' 'java-espera0.args'
  $argsMil = Nuevo-ArgJava '1000' 'java-espera1000.args'

  Invoke-Nativo java @("@$argsCero", 'preparar') | Out-Null

  # 3) CASO 1: éxito -> basicAck, sin mensajes en principal ni DLQ -----------
  Escribir '----------------------------------------------------------------------------'
  Escribir 'CASO 1/4 exito: basicAck y cola principal + DLQ vacías (espera-ms=0: no hay reintentos)'
  try {
    Purgar $cola; Purgar $dlq; Purgar $auditQueue
    $orden = [guid]::NewGuid().ToString()
    Invoke-Nativo java @("@$argsCero", 'publicar', 'exito', 'valido', $orden) | Out-Null
    Invoke-Nativo java @("@$argsCero", 'consumir', 'exito') | Out-Null
    $evs = @(ObtenerAuditoria 'exito' $orden)
    $efectos = @($evs | Where-Object { $_.event -eq 'efecto' })
    $acks = @($evs | Where-Object { $_.event -eq 'ack' })
    $nacks = @($evs | Where-Object { $_.event -eq 'nack' })
    $principal = EstadoColaTras $cola 0
    $muerta = EstadoColaTras $dlq 0
    $ok = ($principal.Total -eq 0 -and $muerta.Total -eq 0 -and $efectos.Count -eq 1 `
      -and $acks.Count -eq 1 -and $nacks.Count -eq 0)
    Resultado 'exito' $ok ("principal={0} dlq={1} efectos={2} ack={3} nack={4}" -f
      $principal.Total, $muerta.Total, $efectos.Count, $acks.Count, $nacks.Count)
  } catch {
    ResultadoError 'exito' $_.Exception.Message
  }

  # 4) CASO 2: IllegalArgumentException -> DLQ inmediato, 0 reintentos -------
  Escribir '----------------------------------------------------------------------------'
  Escribir 'CASO 2/4 permanente: IllegalArgumentException -> basicNack(requeue=false), DLQ inmediato'
  Escribir '         usa espera-ms=1000 a propósito: si reintentara tardaría >=1 s; no debe.'
  try {
    Purgar $cola; Purgar $dlq; Purgar $auditQueue
    $orden = [guid]::NewGuid().ToString()
    Invoke-Nativo java @("@$argsMil", 'publicar', 'permanente', 'permanente', $orden) | Out-Null
    $cronometro = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Nativo java @("@$argsMil", 'consumir', 'permanente') | Out-Null
    $cronometro.Stop()
    $evs = @(ObtenerAuditoria 'permanente' $orden)
    $efectos = @($evs | Where-Object { $_.event -eq 'efecto' })
    $nacks = @($evs | Where-Object { $_.event -eq 'nack' })
    $acks = @($evs | Where-Object { $_.event -eq 'ack' })
    $principal = EstadoColaTras $cola 0
    $muerta = EstadoColaTras $dlq 1
    $mensajesDlq = @(ObtenerDlq)
    $death = if ($mensajesDlq.Count -gt 0) { $mensajesDlq[0].DeathCount } else { -1 }
    $requeue = if ($nacks.Count -gt 0) { [bool]$nacks[0].requeue } else { $true }
    $recibidos = @($evs | Where-Object { $_.event -eq 'recibido' })
    $deltaEventos = if ($recibidos.Count -gt 0 -and $nacks.Count -gt 0) {
      [long]$nacks[0].ts - [long]$recibidos[0].ts
    } else { -1 }
    # El tiempo dentro del consumidor (recibido -> nack) es el que prueba que
    # NO hubo reintentos: con espera-ms=1000, un solo reintento costaría >=1 s.
    # El tiempo de proceso incluye arranque de JVM (~0.5 s) y no es concluyente.
    $ok = ($principal.Total -eq 0 -and $muerta.Total -eq 1 -and $efectos.Count -eq 0 `
      -and $nacks.Count -eq 1 -and $acks.Count -eq 0 -and (-not $requeue) `
      -and $death -eq 1 -and $deltaEventos -ge 0 -and $deltaEventos -lt 500)
    Resultado 'permanente' $ok ("principal={0} dlq={1} efectos={2} nack={3} requeue={4} x-death.count={5} recibido->nack={6}ms (proceso={7}ms, espera configurada=1000ms)" -f
      $principal.Total, $muerta.Total, $efectos.Count, $nacks.Count, $requeue, $death,
      $deltaEventos, $cronometro.ElapsedMilliseconds)
  } catch {
    ResultadoError 'permanente' $_.Exception.Message
  }

  # 5) CASO 3: RuntimeException -> 3 reintentos reales de ~1 s y DLQ ---------
  Escribir '----------------------------------------------------------------------------'
  Escribir 'CASO 3/4 transitorio: RuntimeException -> 3 reintentos con espera real de ~1 s y DLQ'
  try {
    Purgar $cola; Purgar $dlq; Purgar $auditQueue
    $orden = [guid]::NewGuid().ToString()
    Invoke-Nativo java @("@$argsMil", 'publicar', 'transitorio', 'transitorio', $orden) | Out-Null
    $cronometro = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Nativo java @("@$argsMil", 'consumir', 'transitorio') | Out-Null
    $cronometro.Stop()
    $evs = @(ObtenerAuditoria 'transitorio' $orden)
    $efectos = @($evs | Where-Object { $_.event -eq 'efecto' } | Sort-Object { [long]$_.tag })
    $nacks = @($evs | Where-Object { $_.event -eq 'nack' })
    $principal = EstadoColaTras $cola 0
    $muerta = EstadoColaTras $dlq 1
    $mensajesDlq = @(ObtenerDlq)
    $death = if ($mensajesDlq.Count -gt 0) { $mensajesDlq[0].DeathCount } else { -1 }
    $deltas = @()
    for ($i = 1; $i -lt $efectos.Count; $i++) {
      $deltas += ([long]$efectos[$i].ts - [long]$efectos[$i - 1].ts)
    }
    $esperasCorrectas = ($deltas.Count -eq 3)
    foreach ($d in $deltas) {
      if ($d -lt 850 -or $d -gt 2500) { $esperasCorrectas = $false }
    }
    $requeue = if ($nacks.Count -gt 0) { [bool]$nacks[0].requeue } else { $true }
    $ok = ($principal.Total -eq 0 -and $muerta.Total -eq 1 -and $efectos.Count -eq 4 `
      -and $esperasCorrectas -and $nacks.Count -eq 1 -and (-not $requeue) `
      -and $death -eq 1)
    Resultado 'transitorio' $ok ("principal={0}(ready={1}/unack={2}) dlq={3} efectos(intentos)={4} esperas=[{5}]ms nack={6} requeue={7} x-death.count={8} consumo={9}ms" -f
      $principal.Total, $principal.Listos, $principal.SinConfirmar, $muerta.Total,
      $efectos.Count, ($deltas -join ','), $nacks.Count, $requeue, $death,
      $cronometro.ElapsedMilliseconds)
  } catch {
    ResultadoError 'transitorio' $_.Exception.Message
  }

  # 6) CASO 4: caída y rearranque con mensaje sin confirmar ------------------
  Escribir '----------------------------------------------------------------------------'
  Escribir 'CASO 4/4 crash: caída antes de confirmar -> redelivery, un solo efecto (espera-ms=0: sin reintentos)'
  try {
    Purgar $cola; Purgar $dlq; Purgar $auditQueue
    $orden = [guid]::NewGuid().ToString()
    Invoke-Nativo java @("@$argsCero", 'publicar', 'crash', 'valido', $orden) | Out-Null

    $codigo = Invoke-Nativo java @("@$argsCero", 'consumir', 'crash', 'crash') @(23)
    Escribir "  primer consumidor finalizado con halt 23 (esperado): $codigo"
    $recibidosPrimero = @(ObtenerAuditoria 'crash' $orden | Where-Object { $_.event -eq 'recibido' })
    $antes = EstadoColaTras $cola 1
    $efectosPrimero = @(ObtenerAuditoria 'crash' $orden | Where-Object { $_.event -eq 'efecto' })

    # Rearranque del consumidor: el mensaje sin confirmar debe reentregarse.
    Invoke-Nativo java @("@$argsCero", 'consumir', 'crash') | Out-Null
    $todos = @(ObtenerAuditoria 'crash' $orden)
    $recibidos = @($todos | Where-Object { $_.event -eq 'recibido' })
    $redeliver = @($recibidos | Where-Object { [bool]$_.redeliver })
    $efectos = @($todos | Where-Object { $_.event -eq 'efecto' })
    $acks = @($todos | Where-Object { $_.event -eq 'ack' })
    $principal = EstadoColaTras $cola 0
    $muerta = EstadoColaTras $dlq 0
    $ok = ($recibidosPrimero.Count -eq 1 -and $efectosPrimero.Count -eq 0 `
      -and $redeliver.Count -ge 1 -and $efectos.Count -eq 1 -and $acks.Count -eq 1 `
      -and $principal.Total -eq 0 -and $muerta.Total -eq 0)
    Resultado 'crash' $ok ("recibidos={0} redelivery={1} efectos_totales={2} (antes del rearranque={3}) ack={4} principal={5} dlq={6} sin_confirmar_antes={7}" -f
      $recibidos.Count, $redeliver.Count, $efectos.Count, $efectosPrimero.Count,
      $acks.Count, $principal.Total, $muerta.Total, $antes.SinConfirmar)
  } catch {
    ResultadoError 'crash' $_.Exception.Message
  }

  Escribir '----------------------------------------------------------------------------'
  Escribir ("TOTALES: ejecutadas={0} fallidas={1} errores={2} omitidas={3}" -f
    $script:ejecutadas, $script:fallidas, $script:errores, $script:omitidas)
  if ($script:fallidas -gt 0 -or $script:errores -gt 0) { exit 1 }
} finally {
  Escribir '----------------------------------------------------------------------------'
  if ($containerCreado) {
    try {
      Invoke-Nativo docker @('rm', '-f', $container) | Out-Null
      Escribir "LIMPIEZA: contenedor $container eliminado (docker rm -f)."
    } catch {
      Escribir "LIMPIEZA: NO se pudo eliminar $container -> $($_.Exception.Message)"
    }
  } else {
    Escribir 'LIMPIEZA: no había contenedor que eliminar.'
  }
  if (Test-Path $tmp) {
    Remove-Item -Recurse -Force -Path $tmp -ErrorAction SilentlyContinue
    Escribir "LIMPIEZA: temporales $tmp eliminados."
  }
  Escribir 'LIMPIEZA: sin colas, exchanges ni vhosts persistentes fuera del contenedor.'
}

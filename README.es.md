<p align="center"><a href="README.md">English</a> · <a href="README.es.md"><strong>Español</strong></a></p>

<p align="center"><img src="docs/assets/linqavo-cover.svg" width="100%" alt="Linqavo — comparte Internet con Android y Windows"></p>

<p align="center">
<a href="https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.2.0/Linqavo-1.2.0-android-arm64-debug.apk"><img src="https://img.shields.io/badge/Descargar_Android_APK-ARM64-67A6FF?style=for-the-badge&amp;logo=android&amp;logoColor=white&amp;labelColor=182231" alt="Descargar Android"></a>
<a href="https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.2.0/Linqavo-Setup-x64-1.2.0.exe"><img src="https://img.shields.io/badge/Descargar_Windows_Setup-x64-67A6FF?style=for-the-badge&amp;labelColor=182231" alt="Descargar Windows"></a>
</p>

<p align="center">Android 8+ · Windows 10 / 11 · WireGuard · Hasta 3 clientes<br>
<a href="#instalar-linqavo-120">Instalar la 1.2.0</a> · <a href="#conectar">Conectar</a> · <a href="#lo-que-añade-linqavo">Funciones</a> · <a href="#seguridad-y-confianza">Seguridad</a></p>

Comparte la conexión de un teléfono Android con tu **PC Windows, una tablet u otro teléfono Android**. Usa la misma Wi-Fi o el punto de acceso del teléfono. Windows también puede usar el enlace local de anclaje USB.

**Una app Android, dos roles.** Elige Share Internet en el teléfono que comparte y Connect to Internet en otro Android. Cada cliente tiene su propia conexión WireGuard y su identidad guardada.

## Instalar Linqavo 1.2.0

**Windows 10 u 11 (x64)** · [Descargar el instalador de Windows (.exe)](https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.2.0/Linqavo-Setup-x64-1.2.0.exe)

Ejecuta el instalador y abre **Linqavo** desde Inicio. Para desinstalarlo, ve a Configuración → Aplicaciones. El instalador se distribuye sin firma de editor, por lo que Windows puede mostrar un aviso de editor desconocido. Windows también puede pedir permisos de administrador al crear el adaptador de red.

**Android 8 o posterior (ARM64)** · [Descargar la app de Android (.apk)](https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.2.0/Linqavo-1.2.0-android-arm64-debug.apk)

Abre el APK y confirma la instalación. Con la misma app puedes compartir la conexión de tu teléfono o conectar otro dispositivo Android. La interfaz está disponible en inglés y persa. En Android puedes elegir el tema oscuro, claro o del sistema.

**¿Ya usas la 1.1.0?** Descarga la 1.2.0 manualmente esta vez. En Windows, sal de la versión portátil desde la bandeja del sistema antes de ejecutar el instalador. En Android, abre el APK sobre la instalación existente. Conserva el identificador de la app y el certificado de desarrollo.

**A partir de la 1.2.0**, Linqavo consulta GitHub en primer plano para detectar versiones estables más recientes. Pulsa **Update** (Actualizar) para abrir la descarga oficial en el navegador. Después, ejecuta el instalador o abre el APK. También puedes buscar actualizaciones desde Ajustes con **Check for updates**.

[Notas de la versión](https://github.com/STENDSTUDIOS/Linqavo/releases/tag/v1.2.0) · [Sumas SHA-256](https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.2.0/SHA256SUMS.txt)

## Conectar

| 1 · Comparte | 2 · Elige el teléfono | 3 · Aprueba una vez |
| --- | --- | --- |
| Abre **Share Internet** e inicia la sesión. | En Windows, selecciona el teléfono, introduce su código o escanea el QR. En Android, abre **Connect to Internet** y selecciona el teléfono o escanea el QR. | Compara la huella completa cuando se solicite y aprueba el dispositivo nuevo en el teléfono que comparte. |

Los equipos de confianza conservan su identidad al reiniciar Compartir. Un dispositivo nuevo o una identidad revocada necesitan aprobación. Android solicita permiso de VPN para recibir Internet.

## Lo que añade Linqavo

| | |
| --- | --- |
| **Android → Android** | Comparte con una tablet u otro teléfono mediante el cliente VPN. Ambos roles están en el mismo APK. |
| **Laptop y tablet a la vez** | Autoriza hasta tres equipos con claves y direcciones IPv4 independientes. Gestiona el acceso de cada uno. |
| **Dispositivos de confianza** | Aprueba una vez y permite la reconexión. Administra los equipos recordados en Ajustes. |
| **Escritorio compacto** | Panel principal, Ajustes aparte, mini ventana de estado y menú de bandeja con el estilo de Linqavo. |
| **Diseño oscuro y azul** | Paneles azul marino, detalles legibles y pantallas Android de emparejamiento y Ajustes con la misma identidad. |
| **Tráfico y estado** | Velocidad, totales y estado que considera por separado el tráfico reenviado y la presencia autenticada. |

## Dentro de la app

<table>
<tr><th>Empareja una vez</th><th>Gestiona tus dispositivos</th></tr>
<tr>
<td align="center"><img src="docs/screenshots/android-pairing.png" width="240" alt="Panel azul de Android con QR de emparejamiento"></td>
<td align="center"><img src="docs/screenshots/android-settings.png" width="240" alt="Ajustes Android y dispositivos de confianza"></td>
</tr>
</table>

<sub>Capturas reales del emulador Android con datos ilustrativos. La portada es una ilustración de la interfaz.</sub>

## Seguridad y confianza

- **Emparejamiento TLS con certificado fijado.** Verifica el teléfono antes de intercambiar la clave pública. La primera conexión necesita aprobación.
- **Identidades WireGuard independientes** y reglas de dirección /32 por cliente.
- **Claves locales protegidas.** Android usa Keystore y Windows cifra la identidad para el usuario actual.
- **Control del acceso.** Desactivar conserva la autorización. Revocar invalida la clave. Olvidar exige otra aprobación desde la próxima sesión de Compartir.
- **Protección de rutas.** La VPN Android enruta IPv4 y bloquea IPv6 mientras está activa. Windows ofrece filtros DNS/IPv6 y mantener la ruta si se pierde el teléfono.
- **Operación local.** Sin cuentas de Linqavo, servidores intermediarios ni telemetría. Los diagnósticos se comparten por decisión del usuario.

Mantener la ruta en Windows tiene límites si falla el auxiliar o durante el establecimiento de la conexión. La comprobación física de fugas DNS/IPv6 sigue pendiente. El teléfono reenvía mediante su red predeterminada, según la política de Android.

[Emparejamiento](shared/enrollment-v2.md) · [Política de reenvío](shared/forwarding-policy.json) · [Cliente Android](shared/android-client.md)

## Validación

En la 1.2.0 pasaron **255 pruebas de Windows y 192 pruebas de Android en la JVM**. Android Lint informó de cero errores y 25 advertencias. En un entorno limpio de Windows se ejecutó el instalador dos veces, se comprobó la entrada en Aplicaciones instaladas y el acceso desde Inicio, se verificaron los 452 archivos instalados y se probó la desinstalación. [Prueba del instalador](https://github.com/STENDSTUDIOS/Linqavo/actions/runs/44964452817)

En pruebas físicas anteriores, un Galaxy S25 Ultra compartió Internet simultáneamente con una Galaxy Tab A11+ y un portátil Windows. Siguen pendientes la actualización y la conexión en equipos físicos con la 1.2.0, tres clientes simultáneos, la protección de Windows ante cierres abruptos y las pruebas físicas de fugas de DNS e IPv6.

## Desarrollado con IA

**Linqavo ha sido vibecoded.** Se utilizaron herramientas de IA para escribir y revisar código, diseñar la interfaz y preparar pruebas. Si te incomoda ese método de desarrollo, te recomendamos que no uses Linqavo.

## Créditos y licencia

Basado en **[Relay](https://github.com/Mahdi-mortazavi/relay)** de **Mahdi Mortazavi y sus colaboradores**. Su trabajo en Android, Windows y WireGuard hizo posible este proyecto.

El fork añade cliente Android, autorización independiente de varios equipos, confianza persistente, mejoras del ciclo de conexión y la identidad Linqavo. También utiliza WireGuard, wireguard-go, gVisor y Wintun. El paquete de Windows conserva las licencias de Wintun y del proyecto original.

Distribuido bajo la [licencia GNU GPL](LICENSE). [Compilar el proyecto](docs/BUILD.md).

# Linqavo

Comparte la conexión de un teléfono Android con una **tablet, otro teléfono Android o un PC Windows**. El teléfono usa su conexión habitual a Internet y cada cliente se conecta a él mediante WireGuard. Puedes autorizar hasta tres dispositivos y gestionar el acceso de cada uno por separado. Funciona sin root, cuentas de Linqavo ni servidores intermediarios.

Linqavo es un proyecto independiente basado en **Relay**, de **Mahdi Mortazavi y sus colaboradores**. De Relay vienen la base de las aplicaciones para Android y Windows y el túnel WireGuard. Linqavo añade el cliente Android, el uso simultáneo de varios dispositivos, la autorización persistente por equipo y una nueva interfaz. [Proyecto original](https://github.com/Mahdi-mortazavi/relay)

## Descargas · 1.1.0

| Plataforma | Archivo | Cómo empezar |
| --- | --- | --- |
| Android ARM64 · Android 8 o posterior | [APK de prueba](https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.1.0/Linqavo-1.1.0-android-arm64-debug.apk) | El mismo APK incluye **Share Internet** y **Connect to Internet**. |
| Windows x64 · Windows 10 y 11 | [ZIP portátil](https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.1.0/Linqavo-1.1.0-windows-x64.zip) | Extrae toda la carpeta y abre **Linqavo.exe**. Deja el auxiliar y las DLL junto al ejecutable. Windows puede pedir permiso para crear el adaptador. |

Esta primera publicación incluye un APK firmado con clave de depuración y una aplicación de Windows sin firma de editor. Las actualizaciones se descargan manualmente. Puedes comprobar la huella SHA-256 de cada archivo antes de abrirlo. [Sumas de verificación](https://github.com/STENDSTUDIOS/Linqavo/releases/download/v1.1.0/SHA256SUMS.txt)

## Conectar por primera vez

1. En el teléfono que tiene Internet, abre **Share Internet** e inicia la sesión. Conecta el otro dispositivo a la misma Wi-Fi o al punto de acceso del teléfono. Windows también puede usar el enlace local por USB con el anclaje del teléfono activado.
2. **En Android**, abre **Connect to Internet**, selecciona el teléfono o escanea el QR y concede el permiso de VPN. También puedes cargar el QR desde una imagen. **En Windows**, selecciona el teléfono detectado, usa su código de emparejamiento o escanea el QR.
3. Si la aplicación te pide verificar una huella, compara la huella completa con la del otro dispositivo. En el teléfono que comparte, aprueba el nuevo equipo. La primera aprobación lo añade a **Settings → Trusted devices**.
4. Para conectar un segundo cliente, repite el emparejamiento en ese dispositivo. Cada equipo conserva su propia identidad. El teléfono puede compartir o actuar como cliente en una sesión.

<table>
<tr><th>Compartir y emparejar</th><th>Ajustes y dispositivos de confianza</th></tr>
<tr><td><img src="docs/screenshots/android-pairing.png" width="280" alt="Pantalla de Android con el código de dos dígitos y el QR de emparejamiento"></td><td><img src="docs/screenshots/android-settings.png" width="280" alt="Ajustes de Android con límite de clientes, dispositivos de confianza, tema y diagnóstico"></td></tr>
</table>

Capturas del emulador con datos de muestra. El QR de la captura es ilustrativo.

## Opciones de la aplicación

| Dónde | Qué puedes hacer |
| --- | --- |
| Android que comparte | Ajustar el máximo de uno a tres clientes con el servicio detenido, consultar los equipos autorizados y su estado, mostrar el QR durante una sesión y detener el servicio. El widget y el mosaico de ajustes rápidos dan acceso a Compartir. |
| Dispositivos de confianza | Reconectar un equipo aprobado, incluso tras reiniciar Compartir. **Desactivar** suspende el acceso y permite habilitarlo de nuevo. **Revocar** corta el acceso e invalida la clave. **Olvidar** en Ajustes exige otra aprobación a partir de la próxima sesión de Compartir. |
| Android cliente | Recibir Internet a través de la VPN del sistema, consultar el estado y el tráfico y desconectarse desde la aplicación. |
| Windows | Ver dispositivos, velocidad, totales y latencia en la ventana principal. Consultar la conexión en una ventana compacta desde la bandeja del sistema, desconectarse o habilitar el inicio con Windows. |
| Apariencia y diagnóstico | Android ofrece tema claro, oscuro o del sistema. Ambas aplicaciones incluyen inglés y persa. Los registros son locales y se comparten por acción del usuario. |

## Seguridad y alcance

- **Emparejamiento autenticado.** El código corto ayuda a encontrar el teléfono. El cliente verifica su certificado TLS antes de enviar su clave pública. Cada dispositivo nuevo requiere aprobación. Android conserva sus claves mediante Android Keystore y Windows protege la identidad del cliente con el cifrado del usuario de Windows. [Contrato de emparejamiento](shared/enrollment-v2.md)
- **Separación entre clientes.** Cada clave WireGuard tiene una dirección IPv4 individual y reglas de acceso. El teléfono reenvía TCP, UDP y DNS a través de su red predeterminada. Se aplican restricciones a destinos locales y límites de recursos por cliente. [Política de reenvío](shared/forwarding-policy.json)
- **Estado de conexión.** La aplicación distingue la presencia autenticada de la evidencia de tráfico. Los eventos de parada, desactivación y revocación se comunican dentro del túnel. [Protocolo de presencia](shared/peer-presence.json)
- **Rutas y protección frente a fugas.** El cliente Android enruta IPv4 y bloquea IPv6 mientras su VPN está activa. Windows ofrece filtros para DNS e IPv6 y la opción de mantener la ruta si el teléfono deja de responder. Durante un fallo del auxiliar o el establecimiento de la conexión puede quedar disponible la ruta física. Las pruebas físicas de fugas IPv6 y DNS siguen pendientes. [Comportamiento del cliente Android](shared/android-client.md)

En una prueba física anterior, un Galaxy S25 Ultra compartió Internet al mismo tiempo con una Galaxy Tab A11+ y un portátil Windows. Para **1.1.0** se verificó en emulador la reconexión de un dispositivo aprobado después de reiniciar Compartir. La validación física de esta versión con tres clientes simultáneos queda pendiente.

## Desarrollo, créditos y licencia

**Este proyecto ha sido *vibecoded*.** Se han utilizado herramientas de IA para escribir y revisar código, diseñar la interfaz y preparar pruebas. Si ese método de desarrollo te incomoda, te recomendamos que no uses Linqavo.

Gracias a Mahdi Mortazavi y a quienes contribuyeron a Relay por la base que hizo posible este proyecto. El transporte también utiliza WireGuard, wireguard-go, gVisor y Wintun. Conservamos la licencia del proyecto original y el aviso de Wintun acompaña al paquete de Windows. [Relay](https://github.com/Mahdi-mortazavi/relay) · [Licencia GPL](LICENSE) · [Compilar desde el código](docs/BUILD.md)

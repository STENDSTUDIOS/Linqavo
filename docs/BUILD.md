# Compilar Linqavo 1.1.0

El código de la aplicación publicado corresponde a la revisión de desarrollo `4dd4b54ecfe78022fb771db25e17878e23a3a270`. La rama pública comienza con una copia del código de esa revisión. Las dependencias están declaradas en Gradle, los proyectos .NET y `wg/go.mod`.

## Android

Necesitas JDK 17 o compatible, Android SDK 37, NDK r27 y la versión de Go indicada en la directiva `toolchain` de `wg/go.mod`. Configura `ANDROID_HOME` y `ANDROID_NDK_HOME`. Desde la raíz, en Linux o macOS:

```sh
bash scripts/build-wg-aar.sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PrelayRequireWg=true
```

En Windows puedes usar `scripts/build-wg-aar.ps1` con el SDK, NDK y herramientas gomobile preparados, y después `gradlew.bat`. El APK ARM64 se genera en `android/app/build/outputs/apk/debug/`. Una compilación propia utiliza la clave de depuración del entorno que la genera.

## Windows x64

En Windows se necesitan .NET SDK 8, las herramientas de compilación de Visual Studio para WinUI y Windows SDK, además de Go. Desde la raíz, en PowerShell:

```powershell
dotnet test windows/Relay.App.Tests/Relay.App.Tests.csproj -c Release
dotnet publish windows/Relay.App/Relay.App.csproj -c Release -r win-x64 -p:Platform=x64 --self-contained true -p:WindowsAppSDKSelfContained=true -o out/windows-x64
Push-Location wg
go build -trimpath -o ../out/windows-x64/linqavo-wg.exe ./cmd/linqavo-wg
Pop-Location
```

Añade a esa carpeta la biblioteca amd64 de Wintun 0.14.1 y su licencia. El SHA-256 del ZIP de Wintun utilizado es `07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51`. Conserva los archivos publicados por .NET y WinUI junto al ejecutable. [Distribución original de Wintun](https://www.wintun.net/builds/wintun-0.14.1.zip)

## Pruebas del transporte

Ejecuta `go test ./...` desde `wg/`. Las pruebas que ejercitan un adaptador y filtros reales de Windows requieren un entorno aislado con permisos de administrador. La prueba de conexión con los dispositivos físicos es independiente de las pruebas de escritorio y de emulador.

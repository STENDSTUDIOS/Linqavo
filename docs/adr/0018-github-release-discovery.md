# 0018. Discover Linqavo releases and hand downloads to the browser

When the Android activity resumes or the Windows dashboard opens, the app may
read the public latest release of STENDSTUDIOS/Linqavo. Limit foreground checks
to one per six hours per running UI instance. Settings also offers a manual
check. GitHub sees the source IP and a common Linqavo user agent. No account,
device identifier, connection state or peer information is sent.

Require a stable canonical version above the installed version, the project’s
exact tag page and an uploaded asset for the running architecture. The only
automatic request is to the fixed GitHub API endpoint. Reject HTTP redirects,
timeout and oversized responses. Reconnecting, sharing and background services
never trigger a check. Rate limits and connection errors are retryable.

The Update button opens the exact versioned github.com download in the default
browser. Android users open the APK and confirm the OS installation. Future
APKs need the current package ID, higher versionCode and compatible signing key
for an in-place upgrade. Windows users run the per-user Linqavo Setup. It uses the same installer ID
across upgrades and registers its uninstaller in Windows Settings. Users of the
old ZIP should exit that copy before launching Setup.

The app never downloads or executes an installer itself. The requirements for
in-app executable delivery in shared/update-policy.json still apply. Existing
1.1.0 installations need one manual upgrade to gain release discovery.

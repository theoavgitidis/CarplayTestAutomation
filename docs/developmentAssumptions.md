# Development And Security Assumptions

TraceMate is for controlled engineering and test environments. It is not a production security architecture or a general-purpose remote-administration tool.

## Implemented Assumptions

| Assumption | Current behavior | Risk/required control |
| --- | --- | --- |
| SSH host identity | Android JSch disables strict host-key checking | Use only a controlled network; host-key pinning is a high-priority next step. |
| Head-unit SSH authentication | Configured password authentication | Restrict access to authorized test users and protect device credentials. |
| Mac SSH authentication | Configured password authentication over Android-selected Ethernet | Restrict macOS Remote Login to the dedicated CAPTURE_TOOL_PLACEHOLDER user. |
| ADB exposure | The app performs target-specific scoped firewall preparation | Validate the exact target firewall policy; never treat a documented range as portable. |
| Local Mac-agent authorization | Agent RPC is loopback-only; remote control is through SSH-run CLI | Keep the RPC listener local and do not add arbitrary remote execution. |
| Secrets at rest | Android passwords use Keystore AES-GCM; Mac state/output are owner-restricted | Do not commit credentials, private keys, DEVICE_ID_PLACEHOLDERs, addresses, or captures. |

## Configuration Boundaries

Head-unit host, SSH port/user/password, Mac host, Mac SSH port/user/password, and ADB endpoint values are deployment configuration. The app has code defaults for some fields, but those defaults are not portable setup guidance and are intentionally omitted from current operational documents.

The following implementation constraints require target validation:

- Head-unit firewall table/chain names and rules.
- Head-unit ADB service and port.
- Expected diagnostic data paths and available shell tools.
- USB mount layout, permissions, capacity, and checksum tools.
- CAPTURE_TOOL_PLACEHOLDER executable location, MobileDevicePlaceholder trust/profiles, and macOS graphical-login/power policy.

See [Architecture](architecture.md#trust-boundaries), [Operations](operations.md), and [Limitations](limitations.md).

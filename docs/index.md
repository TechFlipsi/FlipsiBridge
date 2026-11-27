---
summary: "Index of all docs — what exists and when to read each."
read_when:
  - "Starting a session and orienting in the repo"
  - "Looking for which doc covers a topic"
---

# Documentation Index

Each doc carries YAML frontmatter (`summary` + `read_when`). Read the one whose `read_when` matches your task.

| Doc | Summary | Read when |
|-----|---------|-----------|
| [INSTALL-GUIDE.md](INSTALL-GUIDE.md) | Illustrated server autoinstallation + granular uninstall (`install-relay.sh`). | Installing or removing the server side — human or agent |
| [SECURITY-SETUP.md](SECURITY-SETUP.md) | Maximum-security setup, verify command per step (TLS, mTLS, agent rules). | Hardening a production deployment |
| [AGENT_SETUP.md](AGENT_SETUP.md) | Full installation delegated to an AI agent, copy-paste prompt included. | Letting your agent set everything up |
| [quickstart.md](quickstart.md) | Get a phone controlled in ~5 minutes. | First-time setup; demoing the system |
| [install.md](install.md) | Full reference install: plugin + bridge APK + dev setup. | Installing from scratch; debugging install |
| [architecture.md](architecture.md) | Kotlin bridge ↔ Python toolset ↔ WebSocket relay; 58 `android_*` tools. | Changing relay/bridge/tool wiring; understanding data flow |
| [configuration.md](configuration.md) | All env vars and config options. | Changing ports, URLs, tokens, timeouts |
| [aaos.md](aaos.md) | Running the bridge on Android Automotive car head units. | Installing on an AAOS head unit |
| [troubleshooting.md](troubleshooting.md) | Known issues + workarounds. | Connection/permission/tool failures |
| [manual-tests.md](manual-tests.md) | On-device manual test procedures. | Before a release; after bridge changes |
| [RELEASING.md](RELEASING.md) | Release checklist: pyproject version + APK `latest-build` tag flow. | Cutting a release |
| [spec.md](spec.md) | Goals, non-goals, compatibility commitments. | Scoping features; deciding what belongs |

**Outside `docs/`:** [../README.md](../README.md) (landing + quickstart) · [../SECURITY.md](../SECURITY.md) (security model) · [../CHANGELOG.md](../CHANGELOG.md) (every release) · [../CONTRIBUTING.md](../CONTRIBUTING.md) · [../deployment/README.md](../deployment/README.md) (hardened systemd migration) · [../skills/](../skills/) (Hermes agent skills: `flipsibridge` + `android`).

**For AI agents:** install the skills from `skills/` — `skills/flipsibridge` covers the whole software (no private data), `skills/android` covers driving the phone.

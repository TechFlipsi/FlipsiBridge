import pytest
import responses


@pytest.fixture
def bridge_url():
    return "http://localhost:8766"


@pytest.fixture(autouse=True)
def mock_env(monkeypatch, bridge_url):
    monkeypatch.setenv("ANDROID_BRIDGE_URL", bridge_url)
    monkeypatch.setenv("ANDROID_BRIDGE_TIMEOUT", "5")


# W26-Test-Isolation (Relay-State je Test) lebt REPO-WEIT in der Root-conftest,
# damit auch contrib/-Testläufe niemals die Live-Prod-Ablage anfassen.


@pytest.fixture
def mock_bridge(bridge_url):
    """Activate responses mock and return the bridge_url for registering mocks."""
    with responses.RequestsMock() as rsps:
        yield rsps, bridge_url

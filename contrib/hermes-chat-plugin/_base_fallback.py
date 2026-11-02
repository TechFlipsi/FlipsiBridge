"""Minimal-Fallback der BasePlatformAdapter-Schnittstelle.

NUR für den Repo-Skeleton-Betrieb (contrib/, Selbst-Test ohne Hermes-Installation):
Läuft der Adapter in einer echten Hermes-Installation, importiert adapter.py die
ECHTE Basis aus ``gateway/platforms/base.py`` (connect/disconnect/send/send_typing/
send_image/get_chat_info) und dieser Fallback wird nie benutzt.

Der Fallback bildet ausschließlich die sechs Schnittstellen-Methoden + SendResult
ab, damit test_adapter.py den Adapter mit einer Mock-WS treiben kann.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class SendResult:
    """Ergebnis eines send() (Spiegel von gateway/platforms/base.py)."""

    success: bool
    message_id: Optional[str] = None
    error: Optional[str] = None
    raw_response: Any = None
    retryable: bool = False


class BasePlatformAdapter(ABC):
    """Schnittstellen-Spiegel der echten BasePlatformAdapter-ABC."""

    def __init__(self, config: Any = None) -> None:
        # Im Skeleton-Modus: Inbound-Texte gehen an diesen Callback
        # (in der echten Basis übernimmt handle_message() die Gateway-Pipeline).
        self.message_callback = None

    @abstractmethod
    async def connect(self, *, is_reconnect: bool = False) -> bool:
        """Verbinden + Empfang starten; True bei Erfolg."""

    @abstractmethod
    async def disconnect(self) -> None:
        """Verbindung trennen."""

    @abstractmethod
    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[dict] = None) -> SendResult:
        """Text an einen Chat senden."""

    @abstractmethod
    async def send_typing(self, chat_id: str) -> None:
        """Tipp-Indikator senden."""

    @abstractmethod
    async def send_image(self, chat_id: str, image_url: str,
                         caption: Optional[str] = None) -> SendResult:
        """Bild senden."""

    @abstractmethod
    def get_chat_info(self, chat_id: str) -> dict:
        """{'name', 'type', 'chat_id'} für einen Chat."""
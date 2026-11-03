"""PH-3-T4: KDF-Fixtures-Tests — jede Tabelle aus secrets-docs/PH3-KDF-FIXTURES-v1.md
wird gegen die LEBENDE Implementierung (contrib/hermes-chat-plugin/adapter.py) exakt
geprüft. Das ist der Cross-Impl-Beweis-Anker: Kotlin (Welle 2) implementiert gegen
dieselben Werte — weichen beide Implementierungen von diesen Bytes ab, schlägt hier
der Test, bevor ein Drill überhaupt losläuft.
"""

import os
import pathlib
import sys
import time

import pytest

sys.path.insert(
    0, str(pathlib.Path(__file__).resolve().parent.parent / "contrib" / "hermes-chat-plugin"))
sys.modules.setdefault("gateway", None)

import adapter as A  # noqa: E402

FIXTURES_DOC = (
    pathlib.Path(__file__).resolve().parent.parent / "secrets-docs" / "PH3-KDF-FIXTURES-v1.md"
)


def h(hexstr: str) -> bytes:
    return bytes.fromhex(hexstr)


# ── 1. HKDF-Anchor: RFC 5869 (SHA-256), Original-Vektoren aus dem RFC-Text ────

class TestHkdfRfc5869:
    def test_case_1_basic(self):
        okm = A.hkdf_sha256(
            h("000102030405060708090a0b0c"), h("0b" * 22), h("f0f1f2f3f4f5f6f7f8f9"), 42)
        assert okm.hex() == (
            "3cb25f25faacd57a90434f64d0362f2a"
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
            "34007208d5b887185865")
        prk = A.hkdf_extract(h("000102030405060708090a0b0c"), h("0b" * 22))
        assert prk.hex() == "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"

    def test_case_1_expand_only_matches_full_hkdf(self):
        prk = A.hkdf_extract(h("000102030405060708090a0b0c"), h("0b" * 22))
        assert A.hkdf_expand(prk, h("f0f1f2f3f4f5f6f7f8f9"), 42) == A.hkdf_sha256(
            h("000102030405060708090a0b0c"), h("0b" * 22), h("f0f1f2f3f4f5f6f7f8f9"), 42)

    def test_case_2_longer_inputs(self):
        ikm = h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
                "404142434445464748494a4b4c4d4e4f")
        salt = h("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
                 "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
                 "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
        info = h("b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf"
                 "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef"
                 "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        assert A.hkdf_sha256(salt, ikm, info, 82).hex() == (
            "b11e398dc80327a1c8e7f78c596a4934"
            "4f012eda2d4efad8a050cc4c19afa97c"
            "59045a99cac7827271cb41c65e590e09"
            "da3275600c2f09b8367793a9aca3db71"
            "cc30c58179ec3e87c14c01d5c1f3434f1d87")

    def test_case_3_zero_salt_info(self):
        okm = A.hkdf_sha256(b"", h("0b" * 22), b"", 42)
        assert okm.hex() == (
            "8da4e775a563c18f715f802a063c5a31"
            "b8a11f5c5ee1879ec3454e5f3c738d2d"
            "9d201395faa4b61a96c8")
        prk = A.hkdf_extract(b"", h("0b" * 22))
        assert prk.hex() == "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"


# ── 2. X25519-Anchor: RFC 7748 ────────────────────────────────────────────────

class TestX25519Rfc7748:
    """§6.1 + §5.2 Funktionsvektoren (Original-Hex aus dem RFC-Text)."""

    def test_ecdh_alice_bob_shared_secret(self):
        alice_priv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        alice_pub = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        bob_priv = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        bob_pub = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assert A._x25519_dh(h(alice_priv), h(bob_pub)).hex() == shared
        assert A._x25519_dh(h(bob_priv), h(alice_pub)).hex() == shared  # kommutativ

    @pytest.mark.parametrize("scalar,u,out", [
        ("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
         "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c",
         "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
        ("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d",
         "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493",
         "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"),
    ])
    def test_x25519_function_vectors(self, scalar, u, out):
        assert A._x25519_dh(h(scalar), h(u)).hex() == out


# ── 3. AES-256-GCM Cross-Vektoren (Python-erzeugt; Kotlin muss replizieren) ──

class TestAesGcmCrossVectors:
    KEY = bytes.fromhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    NONCE = bytes.fromhex("cafebabefacedbaddecaf888")

    def test_g1_empty_plaintext(self):
        ct = A.AESGCM(self.KEY).encrypt(self.NONCE, b"", b"")
        assert ct.hex() == "5b0a77e3716cc7e86cf71b26dee7d5a4"

    def test_g2_plaintext_no_aad(self):
        ct = A.AESGCM(self.KEY).encrypt(
            self.NONCE, "FlipsiBridge PH-3 Cross-Vektor 2".encode(), b"")
        assert ct.hex() == (
            "cccfc956d9130d692f6f3ab85b4dc1123e008323b06a195918ba6f05d1fb45cd"
            "bd78c18745baecae4e7e60a6b1c40405")

    def test_g3_plaintext_with_aad(self):
        pt = bytes.fromhex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        aad = b"fb-v1|fb3demo-0001|chat-123|agent1"
        ct = A.AESGCM(self.KEY).encrypt(self.NONCE, pt, aad)
        assert ct.hex() == (
            "8aa2a225ae7f491c4e0257d6771087301d31d242cb0c7c6356c61e6aa2947be0"
            "8d71e365e5f16f450e7202431eae0e4a69659b6297bfbee71bab4b0782d9897d"
            "328c04f0224b457f63ec5982a4c750dd")

    def test_wrong_key_fails_without_plaintext_leak(self):
        from cryptography.exceptions import InvalidTag
        ct = A.AESGCM(self.KEY).encrypt(self.NONCE, b"geheimer text", b"")
        wrong = A.AESGCM(bytes.fromhex("ff" * 32))
        with pytest.raises(InvalidTag):
            wrong.decrypt(self.NONCE, ct, b"")


# ── 4. fb-v1-Ableitungskette (§5.5 Demo-Keys) — der Kotlin-Pflichtvektor ─────

IK_P = ("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
        "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
IK_A = ("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb",
        "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
SPK_P = ("08182a4c4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8192a3b4c5d6e",
         "1ed0dd56484104c33f79797c39f3f47c2a4153662cfcfc146e4f8a5062c51c72")
EK_P = ("98a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f",
        "7ee1114db727263624da899815825ce5a8b5ce3d2c0a60272feac2205f836634")
SPK_A = ("0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0",
         "52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e")
RA_PRIV = "1111111111111111111111111111111111111111111111111111111111111111"
RA_PUB = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
SESSION_ID = "fb3demo-0001"
AGENT_ID = "agent1"

IDENTITY = {"identity_priv": IK_A[0], "identity_pub": IK_A[1],
            "prekey_priv": SPK_A[0], "prekey_pub": SPK_A[1]}
HELLO = {"type": "crypto.hello", "v": 1, "session_id": SESSION_ID,
         "agent_id": AGENT_ID, "ik_pub": IK_P[1], "ek_pub": EK_P[1],
         "spk_pub": SPK_P[1], "ts": 0}


class TestFbV1DerivationChain:
    def test_dh_values_match_fixtures(self):
        # DH2 = X25519(IK_p, IK_a) — zugleich der RFC-7748-Shared-Secret-Vektor
        assert A._x25519_dh(h(IK_A[0]), h(EK_P[1])).hex() == \
            "74faf45844c64bac4576b625d9df7d611b806e193276eea42ebfd3cbbe698014"
        assert A._x25519_dh(h(IK_A[0]), h(IK_P[1])).hex() == \
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assert A._x25519_dh(h(SPK_A[0]), h(EK_P[1])).hex() == \
            "b67caf683bb48069685a0d9f0572a0d26c4037d1bacc49553054ded8b04ab44e"

    def test_root0_k_init_session_fp_match_fixtures(self):
        sess, _ = A.AgentCryptoSession.respond_to_hello(HELLO, IDENTITY)
        assert sess.fingerprint == \
            "2b3327cc77090e33fc235fdc49659c26507cfae7c1b843e6af7d150de3e8d52f"
        # root0/k_init indirekt: hello_ack-ct (§5.6) entschlüsselt mit k_init
        k_init = A.hkdf_expand(
            A.hkdf_extract(
                h("fcf3ffdd1ba183e7743b3cc0775441ec1ec9e6cef753a15477f34adfa37f2147"),
                h("74faf45844c64bac4576b625d9df7d611b806e193276eea42ebfd3cbbe698014"
                  "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
                  "b67caf683bb48069685a0d9f0572a0d26c4037d1bacc49553054ded8b04ab44e"
                  "07e64bf2f09f9520f3d0a4ee3d9007e0da93a389f40db451f16d9f9d38e79fa4"),
            ),
            # info = "fb-init" + context (konkateniert)
            b"fb-init" + h(IK_P[1]) + h(EK_P[1]) + h(SPK_P[1]) + h(IK_A[1])
            + h(SPK_A[1]) + AGENT_ID.encode() + SESSION_ID.encode(),
            32,
        )
        assert k_init.hex() == "1a531068f5603c0056763523e67e12827572c84dbb786b4ea8e8efff9803cb82"

    def test_hello_ack_verify_vector(self):
        """§5.6: hello_ack-ct mit fixem Nonce muss exakt dem Fixture entsprechen
        (AES-256-GCM über k_init aus der Fixture-Kette, PT = Verify-String)."""
        ack_nonce = bytes.fromhex("00112233445566778899aabb")
        ack_ct = A.AESGCM(bytes.fromhex(
            "1a531068f5603c0056763523e67e12827572c84dbb786b4ea8e8efff9803cb82")
        ).encrypt(ack_nonce, b"flipsi-verify-v1|" + SESSION_ID.encode(),
                  f"fb-v1|{SESSION_ID}|{AGENT_ID}".encode())
        assert ack_ct.hex() == (
            "2dacd90dd0fa8c92b42c199dc64f7f5af1f67add37e69ccb4a6c7a2897eef035"
            "0aac6292cd9fa18bf298169c19")
        # Gegenprobe: Phone-seitige Verify-Entschlüsselung mit k_init gelingt —
        # fälscht man EIN Byte des ct, scheitert der GCM-Tag (Wrong-Key-Pfad).
        k_init = bytes.fromhex(
            "1a531068f5603c0056763523e67e12827572c84dbb786b4ea8e8efff9803cb82")
        pt = A.AESGCM(k_init).decrypt(
            ack_nonce, ack_ct, f"fb-v1|{SESSION_ID}|{AGENT_ID}".encode())
        assert pt == b"flipsi-verify-v1|" + SESSION_ID.encode()
        bad = bytearray(ack_ct)
        bad[0] ^= 0xFF
        from cryptography.exceptions import InvalidTag
        with pytest.raises(InvalidTag):
            A.AESGCM(k_init).decrypt(ack_nonce, bytes(bad),
                                     f"fb-v1|{SESSION_ID}|{AGENT_ID}".encode())

    def test_ratchet_chain_vectors(self):
        """§5.7: RK1/CKs1/CK'/MK_0/MsgAES — fixe RA-Priv gegen Fixture-EK."""
        kdf = A._fb_v1_kdf(
            A._x25519_dh(h(IK_A[0]), h(EK_P[1])),
            A._x25519_dh(h(IK_A[0]), h(IK_P[1])),
            A._x25519_dh(h(SPK_A[0]), h(EK_P[1])),
            b"", SESSION_ID, AGENT_ID,
            h(IK_P[1]), h(EK_P[1]), h(SPK_P[1]), h(IK_A[1]), h(SPK_A[1]))
        root0 = bytes.fromhex(
            "c92a15332695cc9878aeecd1060916fd7729746286a7852e5317b99cfaeb68aa")
        assert kdf["root0"] == root0
        rk1, cks1 = A._kdf_rk(root0, A._x25519_dh(h(RA_PRIV), h(EK_P[1])))
        assert rk1.hex() == "21da3b1d06e44bd94b113c6b091282e4804cc57685ed20aa16dccb33a102cc86"
        assert cks1.hex() == "ead4a92a2da607d8848e641e999a5fd82ef86b9f54b57b76738aa2c4327218e7"
        ck_next, mk0 = A._kdf_ck(cks1)
        assert ck_next.hex() == "f03904549576fcb0f6da724c8493c7bfb43bac1578b29651894d00e812e3913a"
        assert mk0.hex() == "62a14aa5a1f1fa806b2d557d16f01fcadeba281215f24f5797105eb67197bcfd"
        assert A._msg_aes_key(mk0).hex() == \
            "01fd0b5bcd5e609aeaa5bcc3944d026b05eb89baba94788d9be9ba87533a5ca5"

    def test_message_zero_ct_vector(self):
        """§5.7 Nachricht 0: PT/Nonce/AAD → ct‖tag exakt (deterministische Kette)."""
        # Kette deterministisch aufbauen: root0 aus Fixture, RA fix
        root0 = bytes.fromhex(
            "c92a15332695cc9878aeecd1060916fd7729746286a7852e5317b99cfaeb68aa")
        ratchet = A.DoubleRatchet(root0)
        ratchet.dh_priv, ratchet.dh_pub = h(RA_PRIV), h(RA_PUB)
        ratchet.dh_r = h(EK_P[1])
        ratchet.rk, ratchet.ck_s = A._kdf_rk(root0, A._x25519_dh(h(RA_PRIV), h(EK_P[1])))
        ratchet.ns = 0
        ratchet.pn = 0
        ratchet.nonce_prefix = bytes.fromhex("aabbccdd")
        pt = A._json_payload({"msg_id": "m0", "text": "Hallo vom Agent"})
        assert pt == b'{"msg_id":"m0","text":"Hallo vom Agent"}'
        header, nonce, ct = ratchet.encrypt(
            pt, f"fb-v1|{SESSION_ID}|chat-123|{AGENT_ID}".encode())
        assert header == {"rk": RA_PUB, "n": 0, "pn": 0}
        assert nonce.hex() == "aabbccdd0000000000000000"
        assert ct.hex() == (
            "de187bf40ddbdc2c4b0b0403e75bffa64c2f31e71e7818c32cbc6c7f004b375a"
            "dcf37399f14ea6ec4d7b54a7f56fe1ec600dbb63cec59c75")

    def test_phone_first_send_chain_vector(self):
        """§5.7 Gegenprobe: Phone-Sendekette aus RP fix."""
        rk1 = bytes.fromhex(
            "21da3b1d06e44bd94b113c6b091282e4804cc57685ed20aa16dccb33a102cc86")
        rp_priv = "2222222222222222222222222222222222222222222222222222222222222222"
        rk2, cks_phone = A._kdf_rk(rk1, A._x25519_dh(h(rp_priv), h(RA_PUB)))
        assert rk2.hex() == "249d2fde5c77a3c978855906086559b839031a1e1c92735b79804fff6ed3db24"
        assert cks_phone.hex() == \
            "5a95f954b581b55c27880fb7ab3ed152d42b66e98c9b2ccf80574815a5259157"
        # Agent-Seite derselben Kette: X25519(RA_priv, RP_pub)
        rp_pub = A.X25519PrivateKey.from_private_bytes(h(rp_priv)).public_key() \
            .public_bytes(A.Encoding.Raw, A.PublicFormat.Raw)
        rk2_a, ckr_agent = A._kdf_rk(rk1, A._x25519_dh(h(RA_PRIV), rp_pub))
        assert (rk2_a, ckr_agent) == (rk2, cks_phone)


# ── 5. Fingerprints (§5.8) ────────────────────────────────────────────────────

class TestFingerprints:
    def test_agent_fingerprint_of_pub_vectors(self):
        assert A.fingerprint_of_pub(h(IK_A[1])) == \
            "b350365cbeb36d16524a2be7979ba6992de20a0950768f1d70e135d32c890b9d"
        assert A.fingerprint_of_pub(h(IK_P[1])) == \
            "6a2ec0a42bc556598854fe9f67f2d575a0c2d970f911c4beb9eaffbe556ebb7e"

    def test_session_fingerprint_both_sides_equal(self):
        peer = A.PhoneCryptoPeer(AGENT_ID, IK_A[1], SPK_A[1])
        peer.ik_priv, peer.ik_pub = h(IK_P[0]), h(IK_P[1])
        peer.spk_priv, peer.spk_pub = h(SPK_P[0]), h(SPK_P[1])
        hello_from_phone = peer.make_hello(SESSION_ID)
        sess, _ = A.AgentCryptoSession.respond_to_hello(hello_from_phone, IDENTITY)
        assert peer.fingerprint == sess.fingerprint

    def test_fingerprint_display_format(self):
        fp = "2b3327cc77090e33fc235fdc49659c26507cfae7c1b843e6af7d150de3e8d52f"
        assert A.fingerprint_display(fp) == (
            "2B3327CC 77090E33 FC235FDC 49659C26 "
            "507CFAE7 C1B843E6 AF7D150D E3E8D52F")

    def test_psk_binding_changes_keys(self):
        """§5.5 Negativ-Vektor (Zusatz-Order 02.11.2026): psk≠b\"\" ändert die
        Keys — exakt diese Abweichung war Bug 1 (Adapter mit PSK, App ohne).
        LIVE gilt ausschließlich psk=b\"\" (Adapter-Codepfad entfernt); dieser
        Test pinnst die Abweichungs-Eigenschaft der KDF, nicht den Live-Pfad."""
        sess_a, _ = A.AgentCryptoSession.respond_to_hello(HELLO, IDENTITY, psk=b"")
        sess_b, _ = A.AgentCryptoSession.respond_to_hello(HELLO, IDENTITY, psk=b"PAIRCODE12")
        assert sess_a.fingerprint != sess_b.fingerprint


# ── 6. Dok-Konsistenz: das Fixtures-Doc enthält die verifizierten Werte ──────

class TestFixturesDocConsistency:
    def test_doc_exists_and_contains_anchor_vectors(self):
        text = FIXTURES_DOC.read_text(encoding="utf-8")
        for expected in [
            # RFC 5869
            "3cb25f25faacd57a90434f64d0362f2a",
            "b11e398dc80327a1c8e7f78c596a4934",
            "8da4e775a563c18f715f802a063c5a31",
            # RFC 7748
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            # AES-GCM
            "5b0a77e3716cc7e86cf71b26dee7d5a4",
            # fb-v1-Kette
            "2b3327cc77090e33fc235fdc49659c26507cfae7c1b843e6af7d150de3e8d52f",
            "c92a15332695cc9878aeecd1060916fd7729746286a7852e5317b99cfaeb68aa",
            "21da3b1d06e44bd94b113c6b091282e4804cc57685ed20aa16dccb33a102cc86",
            "ead4a92a2da607d8848e641e999a5fd82ef86b9f54b57b76738aa2c4327218e7",
            "de187bf40ddbdc2c4b0b0403e75bffa64c2f31e71e7818c32cbc6c7f004b375a",
        ]:
            assert expected in text, f"Fixtures-Doc verliert den Vektor {expected}"

    def test_doc_declares_frozen_protocol_parameters(self):
        text = FIXTURES_DOC.read_text(encoding="utf-8")
        assert "FP-ABGLEICH: " in text          # Sirs Revision (iii)
        assert "Fingerprint-Abgleich: OK — Code ist gleich" in text
        assert "Fingerprint-Abgleich: FEHLGESCHLAGEN" in text
        assert "fb-root" in text and "fb-init" in text and "fb-fp" in text
        assert "24 h" in text and "0,25 s" in text  # Session/Parameter-Tabelle

# ── 5. PH-3 Jäger-Fixrunde (F2 pn-Cap + F3 ts-Pflicht) — Parität Kotlin ───────

class TestPh3JaegerFixrunde:
    """PH-3 FIX-WELLE nach 4-Jäger-Abnahme: die Guards der Ratchet-/Session-
    Schicht gegen die lebende Python-Referenz (Kotlin spiegelt identisch)."""

    ROOT0 = "c92a15332695cc9878aeecd1060916fd7729746286a7852e5317b99cfaeb68aa"

    def test_pn_cap_blockt_angreifer_kontrolliertes_pn(self):
        """F2: hdr.pn ist angreifer-kontrolliert (bis 2³¹) — decrypt weist
        pn > nr + 100 ab, sonst leitet _skip_message_keys Milliarden
        Message-Keys ab (CPU/RAM-DoS). Kein Zustands-Konsum bei Abweisung."""
        agent = A.DoubleRatchet(h(self.ROOT0))
        agent.init_responder(h(EK_P[1]))
        phone = A.DoubleRatchet(h(self.ROOT0))
        phone.init_initiator(h(EK_P[0]), h(EK_P[1]), agent.dh_pub)
        aad = b"fb-v1|s|c|a"

        hdr, nonce, ct = phone.encrypt(b"p0", aad)
        agent.decrypt(hdr, nonce, ct, aad)
        hdr1, nonce1, ct1 = agent.encrypt(b"a0", aad)
        phone.decrypt(hdr1, nonce1, ct1, aad)

        # Runde 2: Agent-Antwort trägt die FRESHE Ratchet-Pub — Angriff:
        # pn auf 2³¹-nah verdreht → Abweisung VOR jedem Key-Derive-Schritt.
        hdr2, nonce2, ct2 = phone.encrypt(b"p1", aad)
        agent.decrypt(hdr2, nonce2, ct2, aad)
        hdr3, nonce3, ct3 = agent.encrypt(b"a1", aad)
        evil = dict(hdr3)
        evil["pn"] = 2 ** 31 - 1
        with pytest.raises(A.CryptoFrameError, match="pn über Fenster"):
            phone.decrypt(evil, nonce3, ct3, aad)
        # KEIN Zustands-Konsum: der unverfälschte Frame entschlüsselt danach.
        assert phone.decrypt(hdr3, nonce3, ct3, aad) == b"a1"

    def test_pn_cap_grenze_nr_plus_fenster(self):
        """F2-Grenze: pn == nr + 100 passiert den Cap (Fehler erst später im
        Fenster-Check — andere Meldung), pn == nr + 101 wird als pn-Cap
        abgewiesen (genau diese Grenze, nicht mehr)."""
        agent = A.DoubleRatchet(h(self.ROOT0))
        agent.init_responder(h(EK_P[1]))
        phone = A.DoubleRatchet(h(self.ROOT0))
        phone.init_initiator(h(EK_P[0]), h(EK_P[1]), agent.dh_pub)
        aad = b"fb-v1|s|c|a"

        hdr, nonce, ct = phone.encrypt(b"p0", aad)
        agent.decrypt(hdr, nonce, ct, aad)
        hdr1, nonce1, ct1 = agent.encrypt(b"a0", aad)
        phone.decrypt(hdr1, nonce1, ct1, aad)
        hdr2, nonce2, ct2 = phone.encrypt(b"p1", aad)
        agent.decrypt(hdr2, nonce2, ct2, aad)
        hdr3, nonce3, ct3 = agent.encrypt(b"a1", aad)

        # phone.nr == 1 → Grenze = 101. Erst EINS darüBER: pn-Cap schlägt
        # VOR jedem Key-Derive zu (kein Zustands-Konsum, s. Grenz-Fall danach).
        over_limit = dict(hdr3)
        over_limit["pn"] = phone.nr + A._REPLAY_WINDOW + 1
        with pytest.raises(A.CryptoFrameError, match="pn über Fenster"):
            phone.decrypt(over_limit, nonce3, ct3, aad)
        # pn AM Limit passiert den Cap — die übersprungenen Keys (Lücke ≤
        # Fenster) landen im Cache, der Frame entschlüsselt normal (kein
        # False-Positive des Guards, Grenze ist scharf).
        at_limit = dict(hdr3)
        at_limit["pn"] = phone.nr + A._REPLAY_WINDOW
        assert phone.decrypt(at_limit, nonce3, ct3, aad) == b"a1"

    def test_decrypt_frame_verwirft_enc_frame_ohne_ts(self):
        """F3: ts ist PFLICHT — ohne ts übersprang der Frame die 10-min-
        Fenster-Prüfung (Replay-Schutz umgehbar). Parität Kotlin."""
        peer = A.PhoneCryptoPeer(AGENT_ID, IK_A[1], SPK_A[1])
        hello = peer.make_hello("ph3-jaeger-f3-ts")
        session, ack = A.AgentCryptoSession.respond_to_hello(hello, IDENTITY)
        peer.consume_ack(ack)

        frame = session.encrypt_frame("chat.reply.enc", "chat-jaeger-f3",
                                     {"text": "ohne ts", "msg_id": "x"})
        frame.pop("ts", None)
        with pytest.raises(A.CryptoFrameError, match="ts fehlt"):
            peer.decrypt_frame(frame)

        # Kontrolle: MIT ts entschlüsselt derselbe Inhalt normal.
        ok = session.encrypt_frame("chat.reply.enc", "chat-jaeger-f3",
                                   {"text": "mit ts", "msg_id": "y"})
        assert peer.decrypt_frame(ok)["text"] == "mit ts"

    def test_decrypt_frame_verwirft_versalzenen_ts(self):
        """F3: ts vorhanden, aber älter als 10 min → verworfen (Fenster)."""
        peer = A.PhoneCryptoPeer(AGENT_ID, IK_A[1], SPK_A[1])
        hello = peer.make_hello("ph3-jaeger-f3-alt")
        session, ack = A.AgentCryptoSession.respond_to_hello(hello, IDENTITY)
        peer.consume_ack(ack)

        frame = session.encrypt_frame("chat.reply.enc", "chat-jaeger-alt",
                                     {"text": "versalzen", "msg_id": "z"})
        frame["ts"] = int(time.time() * 1000) - A._FRAME_MAX_AGE_MS - 1000
        with pytest.raises(A.CryptoFrameError, match="zu alt"):
            peer.decrypt_frame(frame)

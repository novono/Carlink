import hashlib
import hmac
import os
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from open_carlink_pc.auth import (
    AuthSession,
    _field_bytes,
    _field_varint,
    _load_raw_public_key,
    _parse_protobuf,
    _raw_public_key,
    decrypt_session_message,
    encrypt_session_message,
)
from open_carlink_pc.config import CarIdentity
from open_carlink_pc.ucar_protocol import FORMAT_PB3, MESSAGE_REQ, UCarHeader, _build_header


class AuthSessionTests(unittest.TestCase):
    def test_wireless_pin_override_is_used(self) -> None:
        identity = CarIdentity(car_id="d16f3db389dc")
        with tempfile.TemporaryDirectory() as temporary_directory:
            session = AuthSession(
                identity,
                Path(temporary_directory),
                pin_code="123456",
            )
            self.assertEqual(session.pin_code, "123456")
            self.assertIsNone(session.connection_info)

    def test_encrypted_message_round_trip_updates_header_length(self) -> None:
        body = b"test-control-body"
        message = _build_header(
            body_length=len(body),
            sequence_id=9,
            data_format=FORMAT_PB3,
            message_type=MESSAGE_REQ,
            category=1,
            method=24,
        ) + body
        key = os.urandom(16)
        encrypted = encrypt_session_message(key, message)
        self.assertGreater(len(encrypted), len(message))
        self.assertEqual(UCarHeader.parse(encrypted).length, len(encrypted))
        self.assertEqual(decrypt_session_message(key, encrypted), message)

    def test_normal_key_negotiation_matches_reference_algorithm(self) -> None:
        identity = CarIdentity(car_id="d16f3db389dc")
        phone_auth_key = ec.generate_private_key(ec.SECP256R1())
        phone_agreement_key = ec.generate_private_key(ec.SECP256R1())
        phone_auth_raw = _raw_public_key(phone_auth_key.public_key())
        phone_agreement_raw = _raw_public_key(phone_agreement_key.public_key())
        phone_nonce = os.urandom(32)
        pin = identity.pin_code.encode("utf-8")
        phone_hmac = hmac.new(
            hashlib.sha256(phone_nonce + pin).digest(),
            phone_auth_raw,
            hashlib.sha256,
        ).digest()
        phone_signature = phone_auth_key.sign(
            phone_agreement_raw + phone_nonce,
            ec.ECDSA(hashes.SHA256()),
        )
        body = b"".join(
            (
                _field_varint(1, 1),
                _field_bytes(2, phone_auth_raw),
                _field_bytes(3, phone_hmac),
                _field_bytes(4, phone_agreement_raw),
                _field_bytes(5, phone_signature),
                _field_bytes(6, phone_nonce),
                _field_bytes(7, b"phone-test-id"),
                _field_bytes(8, b"PKT110"),
            )
        )
        message = _build_header(
            body_length=len(body),
            sequence_id=42,
            data_format=FORMAT_PB3,
            message_type=MESSAGE_REQ,
            category=3,
            method=1,
        ) + body

        with tempfile.TemporaryDirectory() as temporary_directory:
            session = AuthSession(identity, Path(temporary_directory))
            description, response = session.handle(message)
            self.assertIn("首次密钥协商", description)
            self.assertIsNotNone(response)
            assert response is not None
            header = UCarHeader.parse(response)
            self.assertEqual(header.sequence_id, 42)
            self.assertEqual(header.category, 3)
            self.assertEqual(header.method, 2)

            fields = _parse_protobuf(response[20:])
            server_auth_raw = fields[2][-1]
            server_auth_hmac = fields[3][-1]
            server_agreement_raw = fields[4][-1]
            server_signature = fields[5][-1]
            server_nonce = fields[6][-1]
            assert isinstance(server_auth_raw, bytes)
            assert isinstance(server_auth_hmac, bytes)
            assert isinstance(server_agreement_raw, bytes)
            assert isinstance(server_signature, bytes)
            assert isinstance(server_nonce, bytes)

            expected_hmac = hmac.new(
                hashlib.sha256(server_nonce + phone_nonce + pin).digest(),
                server_auth_raw + phone_auth_raw,
                hashlib.sha256,
            ).digest()
            self.assertTrue(hmac.compare_digest(expected_hmac, server_auth_hmac))
            _load_raw_public_key(server_auth_raw).verify(
                server_signature,
                server_agreement_raw + phone_agreement_raw + server_nonce + phone_nonce,
                ec.ECDSA(hashes.SHA256()),
            )
            shared_secret = phone_agreement_key.exchange(
                ec.ECDH(),
                _load_raw_public_key(server_agreement_raw),
            )
            phone_session_key = HKDF(
                algorithm=hashes.SHA256(),
                length=16,
                salt=hashlib.sha256(phone_nonce + server_nonce).digest(),
                info=b"session_key",
            ).derive(shared_secret)
            self.assertEqual(session.session_key, phone_session_key)


if __name__ == "__main__":
    unittest.main()

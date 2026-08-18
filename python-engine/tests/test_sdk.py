import base64
import hashlib
import hmac
import os
import sys

SDK_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "sdk", "python")
sys.path.insert(0, SDK_PATH)

from agenthub_sdk import ToolPackage, gateway_signature


def test_tool_package_builds_private_registry_registration():
    package = ToolPackage(
        package_name="crm.lookup",
        version="1.2.3",
        artifact=b"zip-content",
        source_uri="registry://private/crm.lookup/1.2.3",
        manifest={"entrypoint": "crm:LookupTool", "permissions": []},
    )
    registration = package.registration()

    assert registration["visibility"] == "private"
    assert base64.b64decode(registration["artifactBase64"]) == b"zip-content"
    assert package.digest == hashlib.sha256(b"zip-content").hexdigest()


def test_gateway_signature_matches_canonical_hmac():
    secret = "developer-secret-at-least-32-characters-long"
    body = b'{"operation":"platform.echo","input":{"ok":true}}'
    signature = gateway_signature(secret, "POST", "/api/gateway/v1/invoke", 1800000000,
                                  "nonce-123456789", body)
    canonical = (
        "POST\n/api/gateway/v1/invoke\n1800000000\nnonce-123456789\n" + hashlib.sha256(body).hexdigest()
    ).encode()
    expected = base64.urlsafe_b64encode(hmac.new(secret.encode(), canonical, hashlib.sha256).digest()).decode().rstrip("=")

    assert signature == expected

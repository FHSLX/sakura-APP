"""Production image registration, exact bytes, rejected input and failure cleanup."""
import base64
import sys
import uuid
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'sakura_remote'))
from http_server import RemoteService

PNG = bytes.fromhex('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082')

class Artifacts:
    def __init__(self, root):
        self.path = root / 'image.png'
        self.released = 0
        self.allocated = 0
    def allocate(self, options):
        assert options == {'mediaType': 'image/png', 'suffix': '.png'}
        self.allocated += 1
        return {'artifactId': 'artifact_test', 'path': str(self.path)}
    def commit(self, identifier):
        assert identifier == 'artifact_test' and self.path.read_bytes() == PNG
        return {'artifactId': identifier, 'mediaType': 'image/png', 'byteLength': len(PNG)}
    def release(self, identifier):
        self.released += 1
        self.path.unlink(missing_ok=True)

class Mobile:
    def __init__(self, artifacts): self.artifacts = artifacts
    def begin(self, caller, character, text, image):
        assert image == {'artifactId': 'artifact_test', 'mediaType': 'image/png', 'byteLength': len(PNG)}
        assert self.artifacts.path.read_bytes() == PNG
        raise RuntimeError('host-rejected')

if True:
    root = Path(__file__).resolve().parents[1] / 'validation-opaque-region-20261013' / ('image-test-' + uuid.uuid4().hex)
    root.mkdir()
    artifacts = Artifacts(root)
    service = RemoteService(root, mobile_service=Mobile(artifacts), artifact_service=artifacts,
                            character_service=None, tts_service=None, logger=None)
    encoded = 'data:image/png;base64,' + base64.b64encode(PNG).decode()
    try:
        service.chat('Sakura', '看看截图', encoded)
    except RuntimeError as error:
        assert str(error) == 'host-rejected'
    else:
        raise AssertionError('expected host failure')
    assert artifacts.released == 1 and not artifacts.path.exists()
    for invalid in ('/cache/uploads/test.png', 'data:image/png;base64,invalid',
                    'data:image/png;base64,' + base64.b64encode(b'not-an-image').decode()):
        try: service.image_artifact(invalid)
        except ValueError: pass
        else: raise AssertionError('invalid image accepted')
    assert artifacts.allocated == 1
    root.rmdir()
print('PASS: actual image bytes committed, descriptor supplied to host, rejected begin released, invalid data blocked')

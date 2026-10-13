"""Verify the shipped service cannot retrieve UI, automate input or screenshot."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
base = root / 'phone_app/android/app/src/main'
ns = '{http://schemas.android.com/apk/res/android}'
config = ET.parse(base / 'res/xml/portrait_overlay_access.xml').getroot()
for capability in ('canRetrieveWindowContent', 'canPerformGestures',
                   'canRequestFilterKeyEvents', 'canRequestTouchExplorationMode',
                   'canTakeScreenshot'):
    assert config.get(ns + capability) == 'false', capability
assert config.get(ns + 'accessibilityEventTypes') in (None, '0')
assert config.get(ns + 'accessibilityFlags') in (None, '0')
manifest = ET.parse(base / 'AndroidManifest.xml').getroot()
service = next(s for s in manifest.findall('application/service')
               if s.get(ns + 'name') == '.PortraitOverlayAccess')
assert service.get(ns + 'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE'
source = (base / 'java/com/sakura/remote/PortraitOverlayAccess.java').read_text(encoding='utf-8')
for forbidden in ('getRootInActiveWindow(', 'getWindows(', 'dispatchGesture(',
                  'performGlobalAction(', 'takeScreenshot(', 'Settings.Secure.put'):
    assert forbidden not in source, forbidden
assert 'info.eventTypes = 0;' in source
assert 'info.flags = 0;' in source
print('PASS: service requires system binding; no content, events, gestures, key filtering or screenshots')

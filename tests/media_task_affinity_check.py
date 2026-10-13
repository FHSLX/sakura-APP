"""Helper tasks must not reuse the background MainActivity task on overlay entry."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
manifest = ET.parse(root / "phone_app/android/app/src/main/AndroidManifest.xml")
android = "{http://schemas.android.com/apk/res/android}"
activities = {item.get(android + "name"): item for item in manifest.findall("application/activity")}
main_affinity = activities[".MainActivity"].get(android + "taskAffinity", "${applicationId}")
affinities = []
for name in (".CapturePermissionActivity", ".FileChooserActivity"):
    item = activities[name]
    affinity = item.get(android + "taskAffinity", main_affinity)
    assert affinity and affinity != main_affinity, f"{name} still shares MainActivity affinity"
    assert item.get(android + "exported") == "false"
    assert item.get(android + "noHistory") != "true", "Activity result callbacks must survive"
    affinities.append(affinity)
assert len(set(affinities)) == 2, "Picker and capture must not reuse each other's helper task"
print("PASS: helper task isolation and result-lifecycle configuration")

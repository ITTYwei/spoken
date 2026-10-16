"""Apply the model-specific capacity check without changing other local patches."""
import sys
from pathlib import Path

root = Path(sys.argv[1])
path = root / "apps/api/routes/sessions.py"
source = path.read_text()
source = source.replace(
    "from opentalking.runtime.task_consumer import slot_is_occupied\n",
    "from opentalking.runtime.task_consumer import slot_is_occupied, flashhead_slot_is_occupied\n",
)
old = "if slot_is_occupied():"
new = 'if (flashhead_slot_is_occupied() if model == "flashhead" else slot_is_occupied()):'
if old in source:
    source = source.replace(old, new, 1)
elif 'if (flashhead_slot_is_occupied() if model ==  flashhead else slot_is_occupied()):' in source:
    source = source.replace(
        'if (flashhead_slot_is_occupied() if model ==  flashhead else slot_is_occupied()):', new, 1)
elif new not in source:
    raise RuntimeError("API source changed; review before applying")
compile(source, str(path), "exec")
path.write_text(source)

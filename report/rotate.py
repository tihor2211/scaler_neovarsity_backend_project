# Rotates wide diagrams a quarter turn so they can fill a portrait page lengthwise.
#   python rotate.py build/diagrams/er-diagram.png ...  -> build/diagrams/er-diagram-rotated.png ...
import sys
from pathlib import Path

from PIL import Image

for name in sys.argv[1:]:
    src = Path(name)
    with Image.open(src) as img:
        img.rotate(90, expand=True).save(src.with_name(f"{src.stem}-rotated.png"))

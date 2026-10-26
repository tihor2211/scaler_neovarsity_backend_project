# Applied project report

The report is written in Markdown (`chapters/`, one file per chapter, read in file-name order) and built
into a Word document and a PDF in the format of the Woolf report template.

```
chapters/       the text; meta.json holds the title-page details
diagrams/       PlantUML sources of every diagram (style.iuml is the shared look)
charts.py       benchmark charts, drawn from ../load-tests/results
build-report.js Markdown -> .docx (Times New Roman 12/14, 1.5 spacing, 1.25" side margins,
                captions numbered per chapter, title page, contents and lists)
find-pages.js   reads the rendered PDF to put real page numbers in the contents and lists
build.sh        runs everything: diagrams, charts, two passes, PDF
```

## Building

```bash
./build.sh      # -> build/DashCart-Report.docx and build/DashCart-Report.pdf
```

One-time setup (macOS):

```bash
brew install graphviz poppler && brew install --cask libreoffice
npm install
mkdir -p tools assets
curl -L -o tools/plantuml.jar \
  https://repo1.maven.org/maven2/net/sourceforge/plantuml/plantuml/1.2026.7/plantuml-1.2026.7.jar
python3 -m venv tools/venv && tools/venv/bin/pip install matplotlib
# Logos for the title page, from the Woolf template's word/media folder:
#   image3.jpg -> assets/woolf-logo.jpg, image2.png -> assets/scaler-logo.png
```

Markdown extras understood by the builder are listed at the top of `build-report.js`: figure and table
captions with `{#fig:id}` / `{#tbl:id}` labels, `@fig:id` cross-references, and `<<text>>` for
highlighted placeholders still to be written.

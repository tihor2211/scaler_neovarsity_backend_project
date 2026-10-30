#!/usr/bin/env bash
# Builds the report: diagrams -> charts -> Word document -> PDF, with real page numbers in the table of
# contents and the lists of tables and figures (two passes).
#
#   ./build.sh            -> build/DashCart-Report.docx and build/DashCart-Report.pdf
#
# Needs: Java 21, Node 20+, Graphviz (dot), LibreOffice (soffice), Poppler (pdftotext), Python 3 with
# matplotlib for the charts. First run: npm install, and PlantUML in tools/ (see README.md).
set -euo pipefail
cd "$(dirname "$0")"

JAVA=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}/bin/java
PROFILE="file://$PWD/build/lo-profile"   # separate LibreOffice profile, so an open LibreOffice doesn't interfere

mkdir -p build/diagrams build/charts
echo "Diagrams..."
"$JAVA" -Djava.awt.headless=true -jar tools/plantuml.jar -tpng -o "$PWD/build/diagrams" diagrams/*.puml
tools/venv/bin/python rotate.py build/diagrams/er-diagram.png build/diagrams/aws-deployment.png
echo "Charts..."
tools/venv/bin/python charts.py build/charts

to_pdf() {
  soffice -env:UserInstallation="$PROFILE" --headless --convert-to pdf --outdir build build/DashCart-Report.docx >/dev/null
}

echo "Pass 1..."
node build-report.js
to_pdf
node find-pages.js build/DashCart-Report.pdf > build/pages.json

# Page numbers take the same space as the placeholders, so pagination shouldn't move; check anyway.
for pass in 2 3; do
  echo "Pass $pass..."
  node build-report.js build/pages.json
  to_pdf
  node find-pages.js build/DashCart-Report.pdf > build/pages-check.json
  if cmp -s build/pages.json build/pages-check.json; then
    echo "Done: build/DashCart-Report.docx, build/DashCart-Report.pdf"
    exit 0
  fi
  mv build/pages-check.json build/pages.json
done
echo "Page numbers didn't settle after 3 passes" >&2
exit 1

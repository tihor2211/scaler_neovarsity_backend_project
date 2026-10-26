// Finds the page of every heading and caption in the rendered PDF, for the table of contents and the
// lists of tables and figures.
//
//   node find-pages.js build/DashCart-Report.pdf > build/pages.json
//
// Page numbers are physical pages: the report numbers pages from the title page on, as the template does.
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const pdf = process.argv[2];
const index = JSON.parse(fs.readFileSync(path.join(__dirname, 'build', 'index.json'), 'utf8'));
const text = execFileSync('pdftotext', ['-layout', pdf, '-'], { encoding: 'utf8', maxBuffer: 64 << 20 });
const pageLines = text.split('\f').map((page) => page.split('\n').map((l) => l.replace(/\s+/g, ' ').trim()));
const squash = (s) => s.replace(/\s+/g, '').replace(/[’']/g, "'");

// A heading sits on its own line (or wraps onto the next one); captions start their line.
function lineMatches(lines, i, entry) {
    const want = squash(entry.text);
    if (entry.kind === 'caption') return squash(lines[i]).startsWith(want);
    const one = squash(lines[i]);
    return one === want || (lines[i + 1] !== undefined && one.length > 10 && squash(lines[i] + lines[i + 1]) === want);
}

function findFrom(start, entry) {
    for (let p = start; p < pageLines.length; p++) {
        const lines = pageLines[p];
        for (let i = 0; i < lines.length; i++) {
            if (lineMatches(lines, i, entry)) return p;
        }
    }
    return -1;
}

const pages = {};
pages['list-of-tables'] = findFrom(0, { kind: 'heading', text: 'List of Tables' }) + 1;
pages['list-of-figures'] = findFrom(0, { kind: 'heading', text: 'List of Figures' }) + 1;

// Content starts after the last front-matter page, the last one with a "Page No." column header.
let contentStart = 0;
pageLines.forEach((lines, p) => { if (lines.some((l) => l.includes('Page No.'))) contentStart = p + 1; });

// Headings are in document order; captions are searched independently (each label is unique).
let cursor = contentStart;
const missing = [];
for (const entry of index) {
    const p = findFrom(entry.kind === 'heading' ? cursor : contentStart, entry);
    if (p < 0) { missing.push(entry.text); continue; }
    if (entry.kind === 'heading') cursor = p;
    pages[entry.key] = p + 1;
}
if (missing.length) {
    console.error(`Not found in PDF: ${missing.join(' | ')}`);
    process.exit(1);
}
console.log(JSON.stringify(pages, null, 2));
console.error(`${pageLines.filter((l) => l.some((x) => x)).length} pages`);

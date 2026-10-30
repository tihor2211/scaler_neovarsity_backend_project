// Builds the applied project report as a Word document in the Woolf template's format, from
// meta.json and the Markdown chapters in chapters/ (read in file-name order).
//
//   node build-report.js [pages.json]      -> build/DashCart-Report.docx
//
// pages.json (written by find-pages.js from a rendered PDF) supplies the page numbers for the table of
// contents and the lists of tables and figures; without it they show "0". build.sh runs both passes.
//
// Markdown supported: # / ## / ### headings, paragraphs, - bullets (indent 2 for level 2), 1. numbered
// lists, pipe tables, ``` code blocks, > notes, \newpage, **bold**, *italic*, `code`, [text](url),
// <<placeholder>> (highlighted, for text still to be written), ==text== (highlighted, to be confirmed).
//   Figure:   ![Caption](path.png){#fig:id width=6}
//   Table:    Table: Caption {#tbl:id widths=20,80}   on the line before the table
//   Listing:  Listing: Caption {#lst:id}               on the line before the code block
//   Cross-references in text: @fig:id, @tbl:id, @lst:id -> "Figure 5.02", ...
// Chapter numbers come from the H1 text ("5. Feature ..."; "Appendix A ..." -> A); captions are
// numbered per chapter as the template asks: second table of chapter 2 is Table 2.02.
const fs = require('fs');
const path = require('path');
const {
    AlignmentType, BorderStyle, Bookmark, Document, Footer, HeadingLevel, ImageRun, InternalHyperlink,
    LevelFormat, LeaderType, Packer, PageNumber, Paragraph, ShadingType, Tab, Table, TableCell, TableRow,
    TabStopType, TextRun, WidthType,
} = require('docx');

const ROOT = __dirname;
const OUT = path.join(ROOT, 'build', 'DashCart-Report.docx');
const meta = JSON.parse(fs.readFileSync(path.join(ROOT, 'meta.json'), 'utf8'));
const pages = process.argv[2] ? JSON.parse(fs.readFileSync(process.argv[2], 'utf8')) : {};

// US Letter, 1" top/bottom, 1.25" left/right (twips; 1440 = 1 inch).
const PAGE = { width: 12240, height: 15840 };
const MARGIN = { top: 1440, bottom: 1440, left: 1800, right: 1800 };
const TEXT_WIDTH = PAGE.width - MARGIN.left - MARGIN.right; // 8640 = 6 inches
const BODY_SIZE = 24; // half-points: 12 pt
const HEADING_SIZE = 28; // 14 pt
const TABLE_SIZE = 21; // 10.5 pt
const CODE_SIZE = 16; // 8 pt
const LINE_15 = 360; // 1.5 line spacing
const LINE_1 = 240;
const FONT = 'Times New Roman';
const MONO = 'Courier New';

// ---------- Markdown parsing ----------

function parseAttrs(text) {
    const attrs = {};
    const m = text.match(/\{([^}]*)\}\s*$/);
    if (!m) return { text: text.trim(), attrs };
    for (const part of m[1].trim().split(/\s+/)) {
        if (part.startsWith('#')) attrs.id = part.slice(1);
        else if (part.includes('=')) {
            const [k, v] = part.split('=');
            attrs[k] = v;
        }
    }
    return { text: text.slice(0, m.index).trim(), attrs };
}

function parseMarkdown(src, file) {
    const lines = src.replace(/\r/g, '').split('\n');
    const blocks = [];
    let para = [];
    let pendingCaption = null;
    const flush = () => {
        if (para.length) blocks.push({ type: 'para', text: para.join(' ') });
        para = [];
    };
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const trimmed = line.trim();
        if (trimmed === '') { flush(); continue; }
        if (trimmed.startsWith('<!--')) { // comment, possibly multi-line
            flush();
            while (i < lines.length && !lines[i].includes('-->')) i++;
            continue;
        }
        if (trimmed === '\\newpage') { flush(); blocks.push({ type: 'pagebreak' }); continue; }
        let m;
        if ((m = trimmed.match(/^(#{1,3})\s+(.*)$/))) {
            flush();
            const { text, attrs } = parseAttrs(m[2]);
            blocks.push({ type: 'heading', level: m[1].length, text, attrs });
            continue;
        }
        if ((m = trimmed.match(/^!\[(.*)\]\(([^)]+)\)(\{[^}]*\})?$/))) {
            flush();
            const { attrs } = parseAttrs(m[3] || '');
            blocks.push({ type: 'figure', caption: m[1], src: m[2], attrs });
            continue;
        }
        if ((m = trimmed.match(/^(Table|Listing):\s+(.*)$/))) {
            flush();
            pendingCaption = { kind: m[1], ...parseAttrs(m[2]) };
            continue;
        }
        if (trimmed.startsWith('```')) {
            flush();
            const code = [];
            for (i++; i < lines.length && !lines[i].trim().startsWith('```'); i++) code.push(lines[i]);
            const cap = pendingCaption && pendingCaption.kind === 'Listing' ? pendingCaption : null;
            blocks.push({ type: 'code', lines: code, caption: cap && cap.text, attrs: cap ? cap.attrs : {} });
            pendingCaption = null;
            continue;
        }
        if (trimmed.startsWith('|')) {
            flush();
            const rows = [];
            for (; i < lines.length && lines[i].trim().startsWith('|'); i++) rows.push(lines[i].trim());
            i--;
            const cells = rows.filter((r) => !/^\|[\s:|-]+\|$/.test(r))
                .map((r) => splitRow(r));
            const cap = pendingCaption && pendingCaption.kind === 'Table' ? pendingCaption : null;
            blocks.push({ type: 'table', header: cells[0], rows: cells.slice(1), caption: cap && cap.text,
                attrs: cap ? cap.attrs : {} });
            pendingCaption = null;
            continue;
        }
        if ((m = line.match(/^(\s*)([-*]|\d+\.)\s+(.*)$/))) {
            flush();
            const ordered = /\d/.test(m[2]);
            const items = [];
            for (; i < lines.length; i++) {
                const item = lines[i].match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
                if (item && /\d/.test(item[2]) === ordered || (item && item[1].length >= 2)) {
                    items.push({ level: item[1].length >= 2 ? 1 : 0, ordered: /\d/.test(item[2]), text: item[3] });
                } else if (lines[i].trim() !== '' && /^\s{2,}/.test(lines[i]) && items.length) {
                    items[items.length - 1].text += ' ' + lines[i].trim();
                } else {
                    break;
                }
            }
            i--;
            blocks.push({ type: 'list', ordered, items });
            continue;
        }
        if (trimmed.startsWith('>')) {
            flush();
            const note = [];
            for (; i < lines.length && lines[i].trim().startsWith('>'); i++) note.push(lines[i].trim().replace(/^>\s?/, ''));
            i--;
            blocks.push({ type: 'note', text: note.join(' ') });
            continue;
        }
        para.push(trimmed);
    }
    flush();
    if (pendingCaption) throw new Error(`${file}: caption "${pendingCaption.text}" has no table or listing after it`);
    return blocks;
}

function splitRow(row) {
    // Split on | that isn't escaped (\|) and isn't inside `code`.
    const cells = [];
    let cell = '';
    let inCode = false;
    for (let i = 1; i < row.length; i++) {
        const c = row[i];
        if (c === '`') inCode = !inCode;
        if (c === '\\' && row[i + 1] === '|') { cell += '|'; i++; continue; }
        if (c === '|' && !inCode) { cells.push(cell.trim()); cell = ''; continue; }
        cell += c;
    }
    return cells;
}

// ---------- Numbering and cross-references ----------

function chapterKey(headingText) {
    let m = headingText.match(/^(\d+)\.?\s/);
    if (m) return m[1];
    m = headingText.match(/^Appendix\s+([A-Z])\b/);
    return m ? m[1] : null;
}

const pad2 = (n) => String(n).padStart(2, '0');
const LABEL = { fig: 'Figure', tbl: 'Table', lst: 'Listing' };

function number(chapters) {
    const refs = {};
    const figures = [];
    const tables = [];
    const headings = [];
    let hid = 0;
    for (const blocks of chapters) {
        let chapter = null;
        const count = { fig: 0, tbl: 0, lst: 0 };
        for (const b of blocks) {
            if (b.type === 'heading') {
                if (b.level === 1) {
                    chapter = chapterKey(b.text);
                    count.fig = count.tbl = count.lst = 0;
                }
                b.bookmark = `h${++hid}`;
                if (b.level <= 2 && !b.attrs.notoc) headings.push(b);
                continue;
            }
            const kind = b.type === 'figure' ? 'fig' : b.type === 'table' && b.caption ? 'tbl'
                : b.type === 'code' && b.caption ? 'lst' : null;
            if (!kind) continue;
            if (!chapter) throw new Error(`"${b.caption}" is outside a numbered chapter`);
            b.label = `${LABEL[kind]} ${chapter}.${pad2(++count[kind])}`;
            if (b.attrs.id) refs[b.attrs.id] = b.label;
            if (kind === 'fig') figures.push(b);
            if (kind === 'tbl') tables.push(b);
        }
    }
    return { refs, figures, tables, headings };
}

function resolveRefs(text, refs) {
    return text.replace(/@((?:fig|tbl|lst):[\w-]+)/g, (all, id) => {
        if (!refs[id]) throw new Error(`Unknown reference ${all}`);
        return refs[id];
    });
}

// ---------- Inline text ----------

function runs(text, base = {}) {
    const out = [];
    const re = /\*\*(.+?)\*\*|\*(.+?)\*|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\)|<<(.+?)>>|==(.+?)==/g;
    let last = 0;
    let m;
    const plain = (t, extra = {}) => t && out.push(new TextRun({ text: t, ...base, ...extra }));
    while ((m = re.exec(text))) {
        plain(text.slice(last, m.index));
        if (m[1] !== undefined) out.push(...runs(m[1], { ...base, bold: true }));
        else if (m[2] !== undefined) out.push(...runs(m[2], { ...base, italics: true }));
        else if (m[3] !== undefined) plain(m[3], { font: MONO, size: Math.round((base.size || BODY_SIZE) * 0.85) });
        else if (m[4] !== undefined) plain(m[4]);
        else if (m[6] !== undefined) plain(`<${m[6]}>`, { highlight: 'yellow' });
        else if (m[7] !== undefined) plain(m[7], { highlight: 'yellow' });
        last = re.lastIndex;
    }
    plain(text.slice(last));
    return out;
}

const body = (children, opts = {}) => new Paragraph({
    alignment: AlignmentType.JUSTIFIED,
    spacing: { line: LINE_15, after: 120 },
    children,
    ...opts,
});

// The bibliography: single-spaced, left-aligned (URLs can't be justified), hanging indent.
const reference = (children) => new Paragraph({
    alignment: AlignmentType.LEFT,
    spacing: { line: LINE_1, after: 120 },
    indent: { left: 540, hanging: 540 },
    children,
});

// ---------- Blocks to Word ----------

function headingParagraph(b) {
    const level = [null, HeadingLevel.HEADING_1, HeadingLevel.HEADING_2, HeadingLevel.HEADING_3][b.level];
    return new Paragraph({
        heading: level,
        pageBreakBefore: b.level === 1,
        keepNext: true,
        keepLines: true,
        children: [new Bookmark({ id: b.bookmark, children: [new TextRun(b.text)] })],
    });
}

function pngSize(file) {
    const buf = fs.readFileSync(file);
    if (buf.toString('ascii', 1, 4) !== 'PNG') throw new Error(`${file} is not a PNG`);
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20), data: buf };
}

function figureParagraphs(b, refs) {
    const file = path.join(ROOT, b.src);
    const img = pngSize(file);
    // Diagrams and charts are rendered at 200 dpi: default to that natural size, capped at the text width
    // and at maxheight inches (default 7.5; a rotated full-page figure can use about 8.3).
    let widthIn = b.attrs.width ? parseFloat(b.attrs.width) : Math.min(6, img.width / 200);
    let heightIn = widthIn * img.height / img.width;
    const maxHeight = parseFloat(b.attrs.maxheight || '7.5');
    if (heightIn > maxHeight) { widthIn *= maxHeight / heightIn; heightIn = maxHeight; }
    return [
        new Paragraph({
            alignment: AlignmentType.CENTER,
            keepNext: true,
            spacing: { before: 120, after: 60, line: LINE_1 },
            children: [new ImageRun({ type: 'png', data: img.data,
                transformation: { width: Math.round(widthIn * 96), height: Math.round(heightIn * 96) } })],
        }),
        new Paragraph({
            alignment: AlignmentType.CENTER,
            spacing: { after: 240, line: LINE_1 },
            children: [new TextRun({ text: `${b.label}: `, bold: true }), ...runs(resolveRefs(b.caption, refs))],
        }),
    ];
}

function captionAbove(label, caption, refs) {
    return new Paragraph({
        alignment: AlignmentType.CENTER,
        keepNext: true,
        spacing: { before: 120, after: 80, line: LINE_1 },
        children: [new TextRun({ text: `${label}: `, bold: true }), ...runs(resolveRefs(caption, refs))],
    });
}

function columnWidths(header, rows, explicit) {
    if (explicit) {
        const parts = explicit.split(',').map(Number);
        const sum = parts.reduce((a, b) => a + b, 0);
        const widths = parts.map((p) => Math.floor(TEXT_WIDTH * p / sum));
        widths[widths.length - 1] += TEXT_WIDTH - widths.reduce((a, b) => a + b, 0);
        return widths;
    }
    const n = header.length;
    const weight = header.map((h, c) => {
        const lens = [h, ...rows.map((r) => r[c] || '')].map((t) => Math.min(t.length, 70));
        return Math.max(6, lens.reduce((a, b) => a + b, 0) / lens.length + Math.max(...lens) / 3);
    });
    const sum = weight.reduce((a, b) => a + b, 0);
    const widths = weight.map((w) => Math.floor(TEXT_WIDTH * w / sum));
    widths[n - 1] += TEXT_WIDTH - widths.reduce((a, b) => a + b, 0);
    return widths;
}

function cell(text, width, { header = false, refs, align, size = TABLE_SIZE, keepNext = false } = {}) {
    return new TableCell({
        width: { size: width, type: WidthType.DXA },
        margins: { top: 50, bottom: 50, left: 90, right: 90 },
        shading: header ? { fill: 'D9D9D9', type: ShadingType.CLEAR, color: 'auto' } : undefined,
        children: [new Paragraph({
            alignment: align || AlignmentType.LEFT,
            spacing: { line: LINE_1 },
            keepNext,
            children: runs(resolveRefs(text, refs), { size, bold: header || undefined }),
        })],
    });
}

// Tables of up to KEEP_TOGETHER_ROWS rows are kept on one page (keep-with-next on every row but the last).
const KEEP_TOGETHER_ROWS = 14;

function tableBlocks(b, refs) {
    const widths = columnWidths(b.header, b.rows, b.attrs.widths);
    const size = b.attrs.size ? Math.round(parseFloat(b.attrs.size) * 2) : TABLE_SIZE;
    const together = b.rows.length <= KEEP_TOGETHER_ROWS;
    const table = new Table({
        width: { size: TEXT_WIDTH, type: WidthType.DXA },
        columnWidths: widths,
        rows: [
            new TableRow({ tableHeader: true, cantSplit: true,
                children: b.header.map((h, c) => cell(h, widths[c], { header: true, refs, size, keepNext: true })) }),
            ...b.rows.map((r, i) => new TableRow({ cantSplit: true,
                children: b.header.map((_, c) => cell(r[c] || '', widths[c],
                    { refs, size, keepNext: together && i < b.rows.length - 1 })) })),
        ],
    });
    const out = [];
    if (b.caption) out.push(captionAbove(b.label, b.caption, refs));
    out.push(table, new Paragraph({ spacing: { after: 120, line: LINE_1 }, children: [] }));
    return out;
}

function codeBlocks(b, refs) {
    const out = [];
    if (b.caption) out.push(captionAbove(b.label, b.caption, refs));
    out.push(new Table({
        width: { size: TEXT_WIDTH, type: WidthType.DXA },
        columnWidths: [TEXT_WIDTH],
        rows: [new TableRow({ children: [new TableCell({
            width: { size: TEXT_WIDTH, type: WidthType.DXA },
            shading: { fill: 'F2F2F2', type: ShadingType.CLEAR, color: 'auto' },
            margins: { top: 80, bottom: 80, left: 120, right: 120 },
            children: b.lines.map((l) => new Paragraph({
                spacing: { line: LINE_1 },
                children: [new TextRun({ text: l.replace(/\t/g, '    ') || ' ', font: MONO, size: CODE_SIZE })],
            })),
        })] })],
    }), new Paragraph({ spacing: { after: 120, line: LINE_1 }, children: [] }));
    return out;
}

function listParagraphs(b, refs, instance) {
    return b.items.map((item) => new Paragraph({
        alignment: AlignmentType.JUSTIFIED,
        spacing: { line: LINE_15, after: 60 },
        numbering: item.ordered
            ? { reference: 'numbers', level: item.level, instance }
            : { reference: 'bullets', level: item.level },
        children: runs(resolveRefs(item.text, refs)),
    }));
}

function noteParagraph(b, refs) {
    return new Paragraph({
        alignment: AlignmentType.JUSTIFIED,
        spacing: { line: LINE_15, after: 120 },
        indent: { left: 360, right: 360 },
        border: { left: { style: BorderStyle.SINGLE, size: 12, color: '999999', space: 8 } },
        children: runs(resolveRefs(b.text, refs), { italics: true }),
    });
}

let listInstance = 0;
let inReferences = false;

function render(blocks, refs) {
    const out = [];
    for (const b of blocks) {
        if (b.type === 'heading' && b.level === 1) inReferences = b.text === 'References';
        switch (b.type) {
            case 'heading': out.push(headingParagraph(b)); break;
            case 'para': out.push(inReferences ? reference(runs(resolveRefs(b.text, refs)))
                : body(runs(resolveRefs(b.text, refs)))); break;
            case 'figure': out.push(...figureParagraphs(b, refs)); break;
            case 'table': out.push(...tableBlocks(b, refs)); break;
            case 'code': out.push(...codeBlocks(b, refs)); break;
            case 'list': out.push(...listParagraphs(b, refs, ++listInstance)); break;
            case 'note': out.push(noteParagraph(b, refs)); break;
            case 'pagebreak': out.push(new Paragraph({ pageBreakBefore: true, children: [] })); break;
            default: throw new Error(`Unknown block ${b.type}`);
        }
    }
    return out;
}

// ---------- Front matter ----------

const centered = (children, opts = {}) => new Paragraph({ alignment: AlignmentType.CENTER, children, ...opts });
const blank = (n = 1) => Array.from({ length: n }, () => new Paragraph({ children: [] }));
const t = (text, opts = {}) => runs(text, opts);

function logo(file, widthIn, heightIn) {
    return centered([new ImageRun({ type: file.endsWith('.png') ? 'png' : 'jpg',
        data: fs.readFileSync(path.join(ROOT, 'assets', file)),
        transformation: { width: Math.round(widthIn * 96), height: Math.round(heightIn * 96) } })]);
}

function frontHeading(text) {
    return centered([new TextRun({ text, bold: true, size: HEADING_SIZE })],
        { pageBreakBefore: true, spacing: { after: 360 } });
}

function titlePage() {
    return [
        logo('woolf-logo.jpg', 2.2, 1.15),
        centered(t(meta.title, { bold: true, size: HEADING_SIZE }), { spacing: { before: 240 } }),
        ...blank(1),
        centered(t('By')),
        ...blank(1),
        centered(t(meta.studentName, { size: HEADING_SIZE })),
        ...blank(1),
        centered(t('A Master’s Project Report submitted to Scaler Neovarsity - Woolf in partial fulfillment of '
            + 'the requirements for the degree of Master of Science in Computer Science', { bold: true }),
        { spacing: { line: LINE_15 } }),
        ...blank(1),
        centered(t(meta.submissionMonth, { size: HEADING_SIZE })),
        logo('scaler-logo.png', 2.42, 1.36),
        ...[`Scaler Mentee Email ID : ${meta.scalerEmail}`, `Thesis Supervisor : ${meta.supervisor}`,
            `Date of Submission : ${meta.submissionDate}`]
            .map((line) => new Paragraph({ spacing: { line: LINE_15 }, children: t(line, { bold: true }) })),
    ];
}

function certificationPage() {
    return [
        frontHeading('Certification'),
        body(t('I confirm that I have overseen / reviewed this applied project and, in my judgment, it adheres to '
            + 'the appropriate standards of academic presentation. I believe it satisfactorily meets the criteria, '
            + 'in terms of both quality and breadth, to serve as an applied project report for the attainment of '
            + 'Master of Science in Computer Science degree. This applied project report has been submitted to '
            + 'Woolf and is deemed sufficient to fulfill the prerequisites for the Master of Science in Computer '
            + 'Science degree.')),
        ...blank(5),
        new Paragraph({ children: t(meta.supervisor) }),
        new Paragraph({ children: t('…………………') }),
        new Paragraph({ children: t('Project Guide / Supervisor') }),
    ];
}

function declarationPage() {
    return [
        frontHeading('DECLARATION'),
        body(t('I confirm that this project report, submitted to fulfill the requirements for the Master of Science '
            + `in Computer Science degree, completed by me from ${meta.moduleStart} to ${meta.moduleEnd}, is the `
            + 'result of my own individual endeavor. The Project has been made on my own under the guidance of my '
            + 'supervisor with proper acknowledgement and without plagiarism. Any contributions from external '
            + 'sources or individuals, including the use of AI tools, are appropriately acknowledged through '
            + 'citation. By making this declaration, I acknowledge that any violation of this statement '
            + 'constitutes academic misconduct. I understand that such misconduct may lead to expulsion from the '
            + 'program and/or disqualification from receiving the degree.')),
        ...blank(3),
        new Paragraph({ children: t(meta.studentName) }),
        ...signatureImage(),
        new Paragraph({
            tabStops: [{ type: TabStopType.RIGHT, position: TEXT_WIDTH }],
            children: [...t(signatureImage().length ? 'Signature of the Candidate' : '<<Signature of the Candidate>>'),
                new TextRun({ children: [new Tab()] }), ...t(`Date: ${meta.declarationDate}`)],
        }),
    ];
}

// The candidate's signature, if assets/signature.png exists (kept out of git: it's personal).
function signatureImage() {
    const file = path.join(ROOT, 'assets', 'signature.png');
    if (!fs.existsSync(file)) return [];
    const img = pngSize(file);
    const widthIn = 1.6;
    return [new Paragraph({
        spacing: { line: LINE_1, before: 120 },
        children: [new ImageRun({ type: 'png', data: img.data,
            transformation: { width: Math.round(widthIn * 96), height: Math.round(widthIn * 96 * img.height / img.width) } })],
    })];
}

function acknowledgmentPage(blocks, refs) {
    return [frontHeading('ACKNOWLEDGMENT'), ...render(blocks, refs)];
}

function pageOf(key) {
    return pages[key] !== undefined ? String(pages[key]) : '0';
}

function tocPage(headings) {
    const entry = (text, key, indent, bold, anchor) => new Paragraph({
        spacing: { line: LINE_1, after: indent ? 40 : 100, before: indent ? 0 : 60 },
        indent: { left: indent },
        tabStops: [{ type: TabStopType.RIGHT, position: TEXT_WIDTH, leader: LeaderType.DOT }],
        children: [anchor
            ? new InternalHyperlink({ anchor, children: [new TextRun({ text, bold }),
                new TextRun({ children: [new Tab(), pageOf(key)], bold })] })
            : new TextRun({ text: `${text}`, bold }),
        ...(anchor ? [] : [new TextRun({ children: [new Tab(), pageOf(key)], bold })])],
    });
    return [
        frontHeading('Table of Contents'),
        entry('List of Tables', 'list-of-tables', 0, true),
        entry('List of Figures', 'list-of-figures', 0, true),
        ...headings.map((h) => entry(h.text, h.bookmark, h.level === 1 ? 0 : 400, h.level === 1, h.bookmark)),
    ];
}

function listPage(title, items, kind) {
    const widths = [1500, 5940, 1200];
    const head = [`${kind} No.`, 'Title', 'Page No.'];
    const row = (cells, header) => new TableRow({
        tableHeader: header, cantSplit: true,
        children: cells.map((c, i) => new TableCell({
            width: { size: widths[i], type: WidthType.DXA },
            margins: { top: 40, bottom: 40, left: 90, right: 90 },
            shading: header ? { fill: 'D9D9D9', type: ShadingType.CLEAR, color: 'auto' } : undefined,
            children: [new Paragraph({
                alignment: i === 1 ? AlignmentType.LEFT : AlignmentType.CENTER,
                spacing: { line: LINE_1 },
                children: runs(c, { size: TABLE_SIZE, bold: header || undefined }),
            })],
        })),
    });
    return [
        frontHeading(title),
        new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 },
            children: [new TextRun({ text: '(In the order they appear in the text)', italics: true, size: 22 })] }),
        new Table({
            width: { size: TEXT_WIDTH, type: WidthType.DXA },
            columnWidths: widths,
            rows: [row(head, true), ...items.map((b) => row(
                [b.label.split(' ')[1], b.caption.replace(/\s*\(.*?\)\s*$/, ''), pageOf(b.label)], false))],
        }),
    ];
}

// ---------- Document ----------

function build() {
    const dir = path.join(ROOT, 'chapters');
    const files = fs.readdirSync(dir).filter((f) => f.endsWith('.md')).sort();
    const parsed = files.map((f) => ({ file: f, blocks: parseMarkdown(fs.readFileSync(path.join(dir, f), 'utf8'), f) }));
    const ack = parsed.find((p) => p.file.includes('acknowledgment'));
    const chapters = parsed.filter((p) => p !== ack);
    const { refs, figures, tables, headings } = number(chapters.map((c) => c.blocks));

    const content = chapters.flatMap((c) => render(c.blocks, refs));
    const children = [
        ...titlePage(),
        ...certificationPage(),
        ...declarationPage(),
        ...acknowledgmentPage(ack ? ack.blocks : [], refs),
        ...tocPage(headings),
        ...listPage('List of Tables', tables, 'Table'),
        ...listPage('List of Figures', figures, 'Figure'),
        ...content,
    ];

    const doc = new Document({
        creator: meta.studentName,
        title: meta.projectTitle,
        styles: {
            default: {
                document: { run: { font: FONT, size: BODY_SIZE, color: '000000' }, paragraph: { spacing: { line: LINE_15 } } },
                heading1: { run: { font: FONT, size: HEADING_SIZE, bold: true, color: '000000' },
                    paragraph: { alignment: AlignmentType.CENTER, spacing: { before: 0, after: 360, line: LINE_15 } } },
                heading2: { run: { font: FONT, size: HEADING_SIZE, bold: true, color: '000000' },
                    paragraph: { spacing: { before: 280, after: 120, line: LINE_15 } } },
                heading3: { run: { font: FONT, size: BODY_SIZE, bold: true, color: '000000' },
                    paragraph: { spacing: { before: 200, after: 80, line: LINE_15 } } },
            },
        },
        numbering: {
            config: [
                { reference: 'bullets', levels: [
                    { level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
                        style: { paragraph: { indent: { left: 540, hanging: 300 } } } },
                    { level: 1, format: LevelFormat.BULLET, text: '–', alignment: AlignmentType.LEFT,
                        style: { paragraph: { indent: { left: 1000, hanging: 300 } } } },
                ] },
                { reference: 'numbers', levels: [
                    { level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
                        style: { paragraph: { indent: { left: 540, hanging: 360 } } } },
                    { level: 1, format: LevelFormat.LOWER_LETTER, text: '%2)', alignment: AlignmentType.LEFT,
                        style: { paragraph: { indent: { left: 1000, hanging: 360 } } } },
                ] },
            ],
        },
        sections: [{
            properties: { titlePage: true, page: { size: PAGE, margin: { ...MARGIN, header: 720, footer: 720 } } },
            footers: {
                first: new Footer({ children: [centered(t(`© The project report of ${meta.studentName} is approved, `
                    + 'and it is acceptable in quality and form for publication electronically', { size: 20 }))] }),
                default: new Footer({ children: [centered([new TextRun({ children: [PageNumber.CURRENT] })])] }),
            },
            children,
        }],
    });

    // Everything find-pages.js needs to locate entries in the rendered PDF, in document order.
    const index = [
        ...headings.map((h) => ({ key: h.bookmark, kind: 'heading', text: h.text })),
        ...figures.map((f) => ({ key: f.label, kind: 'caption', text: `${f.label}:` })),
        ...tables.map((tb) => ({ key: tb.label, kind: 'caption', text: `${tb.label}:` })),
    ];
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    fs.writeFileSync(path.join(ROOT, 'build', 'index.json'), JSON.stringify(index, null, 2));
    return Packer.toBuffer(doc).then((buf) => {
        fs.writeFileSync(OUT, buf);
        console.log(`Wrote ${path.relative(process.cwd(), OUT)}: ${headings.length} headings, `
            + `${tables.length} tables, ${figures.length} figures`);
    });
}

build().catch((e) => {
    console.error(e.message);
    process.exit(1);
});

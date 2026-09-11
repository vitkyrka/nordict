const fs = require('fs');
const path = require('path');

const jsonPath = process.argv[2];
const outPath = process.argv[3];

if (!jsonPath) {
    console.error("Usage: node cli.js <path-to-json> [output-html-path]");
    process.exit(1);
}

const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const words = Array.isArray(data) && data.length > 0 ? data : [data];

const assetsDir = path.join(__dirname, '../../main/assets');
const templatePath = path.join(assetsDir, 'word_template.html');
let html = fs.readFileSync(templatePath, 'utf8');

// Inline assets for browser viewing
const inlineScript = (id, filename) => {
    let content = fs.readFileSync(path.join(assetsDir, filename), 'utf8');
    // Escape </script> tags to avoid breaking the inline script block
    content = content.replace(/<\/script>/g, '<\\/script>');

    const tag = `<script id="${id}" src='${filename}'></script>`;
    html = html.replace(tag, () => `<script>${content}</script>`);
};

const inlineStyle = (id, filename) => {
    const content = fs.readFileSync(path.join(assetsDir, filename), 'utf8');
    const tag = `<link id="${id}" rel='stylesheet' type='text/css' href='${filename}'>`;
    html = html.replace(tag, () => `<style>${content}</style>`);
};

inlineStyle('main-css', 'renderer.css');
inlineScript('jq-js', 'jquery.min.js');
inlineScript('renderer-js', 'renderer.js');
inlineScript('word-js', 'word.js');

// Load one headword; for multiple headwords the list is that dictionary's
// homonym set (the parser-output shape), so render them the way the app does:
// a single word carrying mHomonymEntries draws all entries as one page with
// the per-heading nav rows. (renderer.js is inlined as a classic script, so
// `renderWord` is in global scope here.)
const json = JSON.stringify(words).replace(/<\/script>/g, '<\\/script>');
const loadScript = words.length === 1 ? `
    loadWord(words[0]);
` : `
    $(document).ready(() => {
        const homs = words.map(w => ({
            mTitle: w.mTitle,
            ref: (w.xrefs && w.xrefs[0]) || '',
            dictionary: w.dictionary,
            conjugation: w.conjugation || '',
            participle: w.participle || '',
            etymology: w.etymology || '',
            definitions: w.definitions || [],
            idioms: w.idioms || [],
            audio: w.audio || []
        }));
        renderWord(Object.assign({}, words[0], { mHomonymEntries: homs }));
        createLinks(document.getElementById('content'));
    });
`;

html = html.replace('</body>', () => `
    <script>
        const words = ${json};
        ${loadScript}
    </script>
    </body>
`);

if (outPath) {
    fs.writeFileSync(path.resolve(outPath), html);
    console.log(`Successfully rendered ${words.length} headword${words.length === 1 ? '' : 's'} to ${outPath}`);
} else {
    process.stdout.write(html);
}
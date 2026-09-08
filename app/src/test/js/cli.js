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

// Load one headword; for multiple headwords append one template article each.
// (renderer.js exposes `template()` as a top-level const in the inline script.)
const json = JSON.stringify(words).replace(/<\/script>/g, '<\\/script>');
const loadScript = words.length === 1 ? `
    loadWord(words[0]);
` : `
    $(document).ready(() => {
        const content = document.getElementById('content');
        words.forEach((word, i) => {
            if (i > 0) {
                content.insertAdjacentHTML('beforeend', '<hr class="cli-word-sep">');
            }
            content.insertAdjacentHTML('beforeend', template(word));
        });
        createLinks(content);
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
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');

const jsonPath = process.argv[2];
if (!jsonPath) {
    console.error("Usage: node cli.js <path-to-json>");
    process.exit(1);
}

const absolutePath = path.resolve(jsonPath);
if (!fs.existsSync(absolutePath)) {
    console.error(`File not found: ${absolutePath}`);
    process.exit(1);
}

const data = JSON.parse(fs.readFileSync(absolutePath, 'utf8'));
const word = Array.isArray(data) ? data[0] : data;

const dom = new JSDOM('<!DOCTYPE html><html><body><div id="content"></div></body></html>');
global.window = dom.window;
global.document = dom.window.document;
global.$ = require('jquery')(dom.window);

const { renderWord } = require('../../main/assets/renderer.js');
renderWord(word);

console.log(dom.window.document.body.innerHTML);

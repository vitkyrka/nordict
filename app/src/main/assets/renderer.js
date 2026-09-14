// Gender values carried in the Word JSON (`gender` field on each gloss of
// Definition/Idiom). Mirrors Genders.kt (app/src/main/java/se/whitchurch/nordict/Genders.kt).
// Keep both in sync.
const GENDERS = {
    FEMININE: 'femenino',
    MASCULINE: 'masculino'
};

const genderClass = (gender) =>
    gender === GENDERS.FEMININE ? 'feminine' :
    gender === GENDERS.MASCULINE ? 'masculine' : '';

const renderGlosses = (glosses) => (glosses || []).map(gloss => `
    <div class="gloss">
        ${gloss.headword ? `<span class="headword">${gloss.headword}</span> ` : ''}
        ${gloss.grammar ? `<span class="grammar ${genderClass(gloss.gender)}">${gloss.grammar}</span> ` : ''}
        <span class="definition">${gloss.definition}</span>
        ${gloss.examples && gloss.examples.length > 0 ? `
            <ul class="examples">
                ${gloss.examples.map(ex => `<li>${ex}</li>`).join('')}
            </ul>
        ` : ''}
        ${renderCollinsIdioms(gloss.idioms)}
        ${renderCollinsPhrases(gloss.phrases)}
    </div>
`).join('');

// Structured synonyms carry the source's link target (`id`, a RAE DLE
// data-id) and optionally a DLE `abbr.sin_alert` marker (`plev`, e.g.
// "malsonante"). Legacy dictionaries still emit plain strings.
const renderSynonym = (s) => typeof s === 'string'
    ? `<span class="synonym">${s}</span>`
    : `<a class="synonym" href="${s.href}">${s.text}</a>` +
      (s.plev ? `<span class="synonym-plev" title="${s.plev}">⚠️</span>` : '');

// Collins per-definition idioms and phrases (bilingual dictionaries).
const renderCollinsIdioms = (idioms) => (idioms || []).length === 0 ? '' : `
    <ul class="def-idioms">
        ${(idioms || []).map(idiom => `
            <li>
                <span class="idiom-marker">▪</span>
                <b class="idiom-headword">${idiom.headword}</b>
                ${idiom.translation ? `: ${idiom.translation}` : ''}
                ${idiom.examples && idiom.examples.length > 0 ? `
                    <ul class="def-examples">
                        ${idiom.examples.map(ex => `<li>${ex}</li>`).join('')}
                    </ul>
                ` : ''}
            </li>
        `).join('')}
    </ul>
`;

const renderCollinsPhrases = (phrases) => (phrases || []).length === 0 ? '' : `
    <ul class="def-phrases">
        ${(phrases || []).map(phrase => `
            <li>
                <b class="phrase-headword">${phrase.headword}</b>
                ${phrase.translation ? ` ${phrase.translation}` : ''}
                ${phrase.examples && phrase.examples.length > 0 ? `
                    <ul class="def-examples">
                        ${phrase.examples.map(ex => `<li>${ex}</li>`).join('')}
                    </ul>
                ` : ''}
            </li>
        `).join('')}
    </ul>
`;

// Compare two number-bearing senses by their senseNumber ("1.4", "13").
// Hierarchical page numbering is preserved numerically so cross-nested
// locutions (idioms are interleaved with definitions on diccionari.cat
// pages) come out in the same order the source page lists them.
const senseKey = (s) => (s || '').split('.').map(n => parseInt(n, 10) || 0);
const compareSenses = (a, b) => {
    const ka = senseKey(a.senseNumber);
    const kb = senseKey(b.senseNumber);
    const len = Math.max(ka.length, kb.length);
    for (let i = 0; i < len; i++) {
        const d = (ka[i] || 0) - (kb[i] || 0);
        if (d !== 0) return d;
    }
    return 0;
};

const renderDefinitionBlock = (def) => `
    <li>
        ${def.senseNumber ? `<span class="sense-number">${def.senseNumber}</span> ` : ''}
        ${def.pos ? `<span class="pos">${def.pos}</span> ` : ''}
        ${def.register ? `<span class="register">${def.register}</span> ` : ''}
        ${def.domain ? `<span class="domain">${def.domain}</span> ` : ''}
        ${def.geo ? `<span class="geo">${def.geo}</span> ` : ''}
        ${def.plev ? `<span class="plev">${def.plev}</span> ` : ''}
        ${renderGlosses(def.glosses)}
        ${def.synonyms && def.synonyms.length > 0 ? `
            <div class="synonyms">
                <span class="synonyms-label">→ </span>${def.synonyms.map(renderSynonym).join(', ')}
            </div>
        ` : ''}
        ${def.antonyms && def.antonyms.length > 0 ? `
            <div class="antonyms">
                <span class="antonyms-label">↛ </span>${def.antonyms.map(a => `<span class="antonym">${a}</span>`).join(', ')}
            </div>
        ` : ''}
        ${renderCollinsIdioms(def.idioms)}
        ${renderCollinsPhrases(def.phrases)}
    </li>
`;

const renderIdiomBlock = (idiom) => `
    <li>
        ${idiom.senseNumber ? `<span class="sense-number">${idiom.senseNumber}</span> ` : ''}
        <b class="idiom-name">${idiom.idiom}</b>:
        ${idiom.register ? `<span class="register">${idiom.register}</span> ` : ''}
        ${idiom.domain ? `<span class="domain">${idiom.domain}</span> ` : ''}
        ${idiom.geo ? `<span class="geo">${idiom.geo}</span> ` : ''}
        ${idiom.plev ? `<span class="plev">${idiom.plev}</span> ` : ''}
        ${renderGlosses(idiom.glosses)}
    </li>
`;

const template = (word) => {
    const defs = word.definitions || [];
    const idioms = word.idioms || [];

    // diccionari.cat and DIDAC keep locutions interleaved with the senses in
    // the source page (no separate "locuciones" section), so their idioms
    // carry a senseNumber. Render them inline in the single numbered list,
    // sorted back into page order. Legacy dictionaries (DLE/EST) have
    // unnumbered idioms and keep the separate locutions section below.
    const numberedIdioms = idioms.some(i => i.senseNumber);
    const senses = numberedIdioms
        ? [...defs, ...idioms].sort(compareSenses)
        : defs;

    // Collins bilingual entries number each sense themselves with a
    // `.sensenum` marker inside the gloss HTML and carry no `senseNumber`, so
    // their <ol> must not be auto-numbered. A combined multi-dictionary page
    // labels every entry with its dictionary tag too (e.g. "DLE"/"EST") for
    // the sub-heading, which is a label, not a bilingual flag: entries without
    // inline sense markers keep the <ol>'s numbering.
    const hasSensenum = (senses) => senses.some(s =>
        (s.glosses || []).some(g => (g.definition || '').indexOf('sensenum') !== -1));

    return `
    <article>
        <header>
            ${word.dictionary ? `<div class="dictionary-label">${word.dictionary}</div>` : ''}
            <h1>${word.mTitle}</h1>
            ${word.pronunciation ? `<div class="pronunciation"><a class="normalx">${word.pronunciation}</a></div>` : ''}
            ${word.conjugation || word.participle ? `
                <div class="morphology">
                    ${word.conjugation ? `<span class="conjugation">conjug. <i>${word.conjugation}</i></span>` : ''}
                    ${word.conjugation && word.participle ? '; ' : ''}
                    ${word.participle ? `<span class="participle">part. <b>${word.participle}</b></span>` : ''}
                </div>
            ` : ''}
            ${word.etymology ? `<div class="etymology">${word.etymology}</div>` : ''}
        </header>
        ${senses.length > 0 ? `
            <ol class="definitions${hasSensenum(senses) ? ' bilingual' : ''}${senses.some(s => s.senseNumber) ? ' sense-numbered' : ''}">
                ${senses.map(s => s.idiom ? renderIdiomBlock(s) : renderDefinitionBlock(s)).join('')}
            </ol>
        ` : ''}
        ${!numberedIdioms && idioms.length > 0 ? `
            <section class="idioms">
                <h3>Locuciones</h3>
                <ul class="idiom-list">
                    ${idioms.map(renderIdiomBlock).join('')}
                </ul>
            </section>
        ` : ''}
    </article>
`;
};

// ---- Homonym page ----
//
// When a JSON dictionary page carries several entries (RAE homographs and
// .sols sub-entries, Collins POS-group homs), every entry's full renderable
// data is attached to the word under `mHomonymEntries` (page order, entry
// included). The renderer draws all entries stacked on one page with a nav
// row above each heading, each entry identified by an in-page anchor
// (`#hom-N`). Each nav row shows the entry that follows it as bold text and
// the others as `#hom-N` links. All labels are anchors (the current one
// self-links) so word.js auto-linking skips them.

// Label each entry; entries with a title that appears more than once get a
// RAE-style ordinal (frente¹, frente², ...) so same-name homographs are
// distinguishable in the nav. On a combined multi-dictionary page (entries
// span more than one distinct `dictionary` label) every label is prefixed
// with its dictionary tag, so a shared word rendered by DLE and EST reads
// "DLE frente¹ | EST frente¹" with ordinals grouped per dictionary.
const homonymLabels = (entries) => {
    const mixed = new Set(entries.map(e => e.dictionary)).size > 1;
    const counts = {};
    entries.forEach(e => {
        const k = e.dictionary + '|' + e.mTitle;
        counts[k] = (counts[k] || 0) + 1;
    });

    const seen = {};
    return entries.map(e => {
        const k = e.dictionary + '|' + e.mTitle;
        const t = e.mTitle;
        const n = (seen[k] = (seen[k] || 0) + 1);
        const prefix = mixed && e.dictionary ? e.dictionary + ' ' : '';
        return { title: prefix + t, ordinal: counts[k] > 1 ? n : '' };
    });
};

const renderHomonymNav = (entries, labels, currentIndex) => `
    <nav class="homonym-nav">
        ${entries.map((e, i) => `
            ${i > 0 ? '<span class="homonym-sep">|</span>' : ''}
            <a class="${i === currentIndex ? 'homonym-current' : 'homonym-link'}"
               href="#hom-${i + 1}">${labels[i].title}${labels[i].ordinal ? `<sup class="homonym-ordinal">${labels[i].ordinal}</sup>` : ''}</a>
        `).join('')}
    </nav>
`;

const renderHomonymPage = (entries) => {
    const labels = homonymLabels(entries);
    return entries.map((entry, i) => `
        <section id="hom-${i + 1}" class="homonym-entry">
            ${renderHomonymNav(entries, labels, i)}
            ${template(entry)}
        </section>
    `).join('');
};

function renderWord(word) {
    const entries = (word.mHomonymEntries && word.mHomonymEntries.length > 1)
        ? word.mHomonymEntries
        : null;

    $('#content').html(entries ? renderHomonymPage(entries) : template(word));
}

// For Node.js testing
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderWord, GENDERS };
}

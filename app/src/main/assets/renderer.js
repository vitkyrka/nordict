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

const template = (word) => `
    <article>
        <header>
            ${word.dictionary ? `<div class="dictionary-label">${word.dictionary}</div>` : ''}
            <h1>${word.mTitle}</h1>
            ${word.conjugation || word.participle ? `
                <div class="morphology">
                    ${word.conjugation ? `<span class="conjugation">conjug. <i>${word.conjugation}</i></span>` : ''}
                    ${word.conjugation && word.participle ? '; ' : ''}
                    ${word.participle ? `<span class="participle">part. <b>${word.participle}</b></span>` : ''}
                </div>
            ` : ''}
            ${word.etymology ? `<div class="etymology">${word.etymology}</div>` : ''}
        </header>
        ${word.definitions && word.definitions.length > 0 ? `
            <ol class="definitions${word.dictionary ? ' bilingual' : ''}">
                ${word.definitions.map(def => `
                    <li>
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
                `).join('')}
            </ol>
        ` : ''}
        ${word.idioms && word.idioms.length > 0 ? `
            <section class="idioms">
                <h3>Locuciones</h3>
                <ul class="idiom-list">
                    ${word.idioms.map(idiom => `
                        <li>
                            <b class="idiom-name">${idiom.idiom}</b>:
                            ${idiom.register ? `<span class="register">${idiom.register}</span> ` : ''}
                            ${idiom.domain ? `<span class="domain">${idiom.domain}</span> ` : ''}
                            ${idiom.geo ? `<span class="geo">${idiom.geo}</span> ` : ''}
                            ${idiom.plev ? `<span class="plev">${idiom.plev}</span> ` : ''}
                            ${renderGlosses(idiom.glosses)}
                        </li>
                    `).join('')}
                </ul>
            </section>
        ` : ''}
    </article>
`;

function renderWord(word) {
    $('#content').html(template(word));
}

// For Node.js testing
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderWord, GENDERS };
}

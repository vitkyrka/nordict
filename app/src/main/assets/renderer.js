// Gender values carried in the Word JSON (`gender` field on Definition/Idiom).
// Mirrors Genders.kt (app/src/main/java/se/whitchurch/nordict/Genders.kt).
// Keep both in sync.
const GENDERS = {
    FEMININE: 'femenino',
    MASCULINE: 'masculino'
};

const genderClass = (gender) =>
    gender === GENDERS.FEMININE ? 'feminine' :
    gender === GENDERS.MASCULINE ? 'masculine' : '';

const template = (word) => `
    <article>
        <header>
            <h1>${word.mTitle}</h1>
            ${word.conjugation || word.participle ? `
                <div class="morphology">
                    ${word.conjugation ? `<span class="conjugation">conjug. <i>${word.conjugation}</i></span>` : ''}
                    ${word.conjugation && word.participle ? '; ' : ''}
                    ${word.participle ? `<span class="participle">part. <b>${word.participle}</b></span>` : ''}
                </div>
            ` : ''}
        </header>
        ${word.definitions && word.definitions.length > 0 ? `
            <ol class="definitions">
                ${word.definitions.map(def => `
                    <li class="${genderClass(def.gender)}">
                        ${def.grammar ? `<span class="grammar">${def.grammar}</span> ` : ''}
                        ${def.register ? `<span class="register">${def.register}</span> ` : ''}
                        ${def.domain ? `<span class="domain">${def.domain}</span> ` : ''}
                        ${def.geo ? `<span class="geo">${def.geo}</span> ` : ''}
                        ${def.plev ? `<span class="plev">${def.plev}</span> ` : ''}
                        <span class="definition">${def.definition}</span>
                        ${def.note ? `<span class="note">${def.note}</span>` : ''}
                        ${def.examples && def.examples.length > 0 ? `
                            <ul class="examples">
                                ${def.examples.map(ex => `<li>${ex}</li>`).join('')}
                            </ul>
                        ` : ''}
                        ${def.synonyms && def.synonyms.length > 0 ? `
                            <div class="synonyms">
                                <span class="synonyms-label">→ </span>${def.synonyms.map(s => `<span class="synonym">${s}</span>`).join(', ')}
                            </div>
                        ` : ''}
                    </li>
                `).join('')}
            </ol>
        ` : ''}
        ${word.idioms && word.idioms.length > 0 ? `
            <section class="idioms">
                <h3>Locuciones</h3>
                <ul class="idiom-list">
                    ${word.idioms.map(idiom => `
                        <li class="${genderClass(idiom.gender)}">
                            <b class="idiom-name">${idiom.idiom}</b>:
                            ${idiom.grammar ? `<span class="grammar">${idiom.grammar}</span> ` : ''}
                            ${idiom.register ? `<span class="register">${idiom.register}</span> ` : ''}
                            ${idiom.geo ? `<span class="geo">${idiom.geo}</span> ` : ''}
                            ${idiom.plev ? `<span class="plev">${idiom.plev}</span> ` : ''}
                            <span class="idiom-definition">${idiom.definition}</span>
                            ${idiom.note ? `<span class="note">${idiom.note}</span>` : ''}
                            ${idiom.examples && idiom.examples.length > 0 ? `
                                <ul class="examples">
                                    ${idiom.examples.map(ex => `<li>${ex}</li>`).join('')}
                                </ul>
                            ` : ''}
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

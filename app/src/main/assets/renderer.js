const template = (word) => `
    <article>
        <header><h1>${word.mTitle}</h1></header>
        ${word.definitions && word.definitions.length > 0 ? `
            <ol class="definitions">
                ${word.definitions.map(def => `
                    <li>
                        ${def.grammar ? `<span class="grammar">${def.grammar}</span> ` : ''}
                        <span class="definition">${def.definition}</span>
                        ${def.examples && def.examples.length > 0 ? `
                            <ul class="examples">
                                ${def.examples.map(ex => `<li>${ex}</li>`).join('')}
                            </ul>
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
                        <li>
                            <b class="idiom-name">${idiom.idiom}</b>:
                            <span class="idiom-definition">${idiom.definition}</span>
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
    module.exports = { renderWord };
}
